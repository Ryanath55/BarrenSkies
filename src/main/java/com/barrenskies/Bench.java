package com.barrenskies;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Generates a block of chunks on startup and reports what it cost. Off unless asked for.
 *
 * <p>Run with -Dbarrenskies.bench=N, or gradlew runServer -Pbench=N, to generate an N by N block of chunks
 * well away from spawn, so the spawn area.s own generation is not measured a second time. Reports wall
 * time and time a chunk.
 *
 * <p>It does the same over three regions far apart, and reports what each leaves behind once collected.
 * Generated chunks stay loaded, so some retention is expected and correct; what matters is whether the
 * three numbers are level or climbing. Climbing means something is kept per world without bound.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = BarrenSkies.MOD_ID)
public final class Bench {
    private Bench() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        int side = Integer.getInteger("barrenskies.bench", 0);
        if (side <= 0) {
            return;
        }
        ServerLevel level = event.getServer().overworld();

        // Three separate regions, far apart and far from spawn. The timings come from the first; the other
        // two are there for the heap. Anything that keeps per-world state without bound grows with each
        // one, and generated chunks stay loaded either way, so what matters is whether the growth is the
        // chunks or something else on top of them.
        long[] retained = new long[3];
        List<Long> perChunk = new ArrayList<>(side * side);
        long took = 0L;
        long heapBefore = 0L;
        long heapAfter = 0L;
        long heapCollected = 0L;

        for (int pass = 0; pass < 3; pass++) {
            int originX = 2000 + pass * 4000;
            int originZ = 2000 + pass * 4000;
            System.gc();
            sleep();
            long before = used();
            long began = System.nanoTime();
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    long one = System.nanoTime();
                    level.getChunk(originX + x, originZ + z, ChunkStatus.FULL, true);
                    if (pass == 0) {
                        perChunk.add(System.nanoTime() - one);
                    }
                }
            }
            long elapsed = System.nanoTime() - began;
            long after = used();
            System.gc();
            sleep();
            long collected = used();
            retained[pass] = (collected - before) >> 20;
            if (pass == 0) {
                took = elapsed;
                heapBefore = before;
                heapAfter = after;
                heapCollected = collected;
            }
        }

        perChunk.sort(null);
        BarrenSkies.LOG.info(
            "[bench] {} chunks ({}x{}) in {} ms. Per chunk: mean {}, median {}, p90 {}, worst {} ms. "
                + "Heap {} MB before, {} MB after, {} MB once collected.",
            side * side, side, side, took / 1_000_000L,
            String.format("%.1f", took / 1_000_000.0 / (side * side)),
            String.format("%.1f", perChunk.get(perChunk.size() / 2) / 1_000_000.0),
            String.format("%.1f", perChunk.get(perChunk.size() * 9 / 10) / 1_000_000.0),
            String.format("%.1f", perChunk.getLast() / 1_000_000.0),
            heapBefore >> 20, heapAfter >> 20, heapCollected >> 20
        );
        BarrenSkies.LOG.info(
            "[bench] retained after a collection, three separate regions in turn: {} MB, {} MB, {} MB. "
                + "Level growth here is the loaded chunks; growth beyond that is not.",
            retained[0], retained[1], retained[2]
        );

        // Section count is the other half of the story: a taller world writes more of them per chunk,
        // which is what a save costs on disk and what a load costs coming back.
        var chunk = level.getChunk(2000, 2000, ChunkStatus.FULL, true);
        BarrenSkies.LOG.info("[bench] world is Y {} to {}, {} sections a chunk, dimension {}.",
            level.getMinBuildHeight(), level.getMaxBuildHeight(), chunk.getSections().length,
            level.dimension().location());
    }

    private static void sleep() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
