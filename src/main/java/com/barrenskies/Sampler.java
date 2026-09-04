package com.barrenskies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sampling profiler, for answering where a chunk's time actually goes.
 *
 * <p>Built rather than reached for because the question is narrow and the answer has to survive being
 * quoted: which of this mod's own methods are on the stack while chunks generate, and how much of the
 * time is spent in each. A wall clock around the whole generate says 135 milliseconds a chunk and nothing
 * about which part, and every optimisation argued from reading the code rather than from a measurement on
 * this project has so far been argued about the wrong method.
 *
 * <p>Samples every thread rather than one, since generation runs on the worker pool and the server thread
 * only waits on it. Two counts per frame, and the difference between them is the whole point: self is how
 * often a method was the innermost frame, which is time actually spent in its own code; total is how often
 * it appeared anywhere on the stack, which is time spent underneath it. A method with a large total and a
 * small self is a router, and speeding it up means changing what it calls.
 */
public final class Sampler {
    private final Map<String, int[]> counts = new HashMap<>();
    private volatile boolean running;
    private Thread thread;
    private int samples;

    /** Frames worth naming: this mod, plus the vanilla worldgen and lighting it stands on. */
    private static boolean interesting(String className) {
        return className.startsWith("com.barrenskies")
            || className.startsWith("net.minecraft.world.level.levelgen")
            || className.startsWith("net.minecraft.world.level.lighting")
            || className.startsWith("net.minecraft.world.level.chunk");
    }

    public void start(long everyMicros) {
        this.running = true;
        this.thread = new Thread(() -> {
            while (this.running) {
                sampleOnce();
                long until = System.nanoTime() + everyMicros * 1000L;
                while (System.nanoTime() < until && this.running) {
                    Thread.onSpinWait();
                }
            }
        }, "barrenskies-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void sampleOnce() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            StackTraceElement[] stack = entry.getValue();
            if (stack.length == 0 || entry.getKey() == this.thread) {
                continue;
            }
            // Only threads doing the work. A sleeping pool thread would otherwise drown everything.
            // The thread that asked for the chunk is not the thread generating it -- it is parked inside
            // getChunk waiting on the worker pool, with worldgen classes on its stack the whole time.
            // Counted, it swamped everything at forty four percent of samples and none of it was work.
            boolean working = false;
            for (StackTraceElement frame : stack) {
                if (frame.getClassName().startsWith("com.barrenskies.Bench")) {
                    working = false;
                    break;
                }
                if (interesting(frame.getClassName())) {
                    working = true;
                }
            }
            if (!working) {
                continue;
            }
            this.samples++;

            // Self time: the innermost frame worth naming, so a hot loop is credited to the method that
            // wrote it rather than to whichever JDK call it happened to end on.
            for (StackTraceElement frame : stack) {
                if (interesting(frame.getClassName())) {
                    at(name(frame))[0]++;
                    break;
                }
            }
            // Total time: each named method once per sample however many times recursion put it on.
            Set<String> seen = new HashSet<>();
            for (StackTraceElement frame : stack) {
                if (interesting(frame.getClassName()) && seen.add(name(frame))) {
                    at(name(frame))[1]++;
                }
            }
        }
    }

    private int[] at(String name) {
        return this.counts.computeIfAbsent(name, key -> new int[2]);
    }

    private static String name(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
    }

    public void stop() {
        this.running = false;
        try {
            this.thread.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The heaviest frames, self first, since self time is what an optimisation can actually remove. */
    public void report(String what, int rows) {
        List<Map.Entry<String, int[]>> bySelf = new ArrayList<>(this.counts.entrySet());
        bySelf.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
        StringBuilder self = new StringBuilder();
        for (int i = 0; i < Math.min(rows, bySelf.size()); i++) {
            Map.Entry<String, int[]> row = bySelf.get(i);
            if (row.getValue()[0] == 0) {
                break;
            }
            self.append(String.format("%n    %-46s self %5.1f%%  total %5.1f%%", row.getKey(),
                row.getValue()[0] * 100.0 / Math.max(1, this.samples),
                row.getValue()[1] * 100.0 / Math.max(1, this.samples)));
        }
        BarrenSkies.LOG.info("[bench] {}: {} samples over the generating threads.{}", what, this.samples, self);
    }
}
