package com.barrenskies.worldgen;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;

/**
 * Two skies instead of one, split at the island floor.
 *
 * <p>Minecraft's sky light is a flood fill rather than a ray. Every block with an unbroken column of air
 * above it is a source at full strength, and from those sources light spreads losing one level a block in
 * every direction. Nothing about that is wrong until terrain starts floating: an island is an unbroken lid
 * over everything beneath it, so every column under one stops being a source, and the only light that can
 * reach the ground has to walk in sideways from the open sky at the island's edge. Over an island a few
 * hundred blocks across that walk is far longer than fifteen blocks, so the light never arrives.
 *
 * <p>Measured rather than assumed: sky light 0 in open air, continuously, from an island's underside down
 * through the empty gap and on past the sea floor, against 15 at every one of those heights 512 blocks
 * away. The ground under an island is as dark as a cave at noon, and every system that reads light -- what
 * spawns, what grows, where snow settles -- treats it as one.
 *
 * <p>So the column is cut in two. Below the split the ground is lit as though the sky above it were clear;
 * above it the islands light themselves exactly as before. Both halves are real sky light computed by the
 * real engine, which is the whole point of doing it here rather than in the renderer: a darkened pixel
 * fools the eye and nothing else, while this is the number every rule actually reads.
 *
 * <p>What makes it possible is that the two never meet. An island cannot reach below islandFloor and the
 * barren ground cannot reach above it, so a single height separates them cleanly and no column is ever in
 * both halves at once.
 */
public final class IslandLight {
    private IslandLight() {
    }

    /**
     * Whether the two passes are running at all.
     *
     * <p>Read on the hot path of the light engine, so it is a plain config lookup and nothing more. With
     * the shadow left on this returns false everywhere and every hook falls through to vanilla.
     */
    public static boolean split() {
        return !BarrenSkiesConfig.ISLAND_SHADOW.get();
    }

    /**
     * The height the two skies are separated at.
     *
     * <p>The same island floor everything else in the mod uses, so the split moves when the islands do.
     * A block below this is lit by the lower sky and cannot be shaded by anything above it; a block at or
     * above it is lit by the upper sky and sees the islands as they are.
     */
    public static int splitAt() {
        return SkyIslandDensity.islandFloor();
    }

    /**
     * Which section the engine is filling right now, while it fills a chunk a section at a time.
     *
     * <p>A note passed between two hooks in the same method, because the one that knows the height and the
     * one that has to answer for a column are not the same call and the engine hands neither of them both
     * facts. Thread local because chunks light on worker threads, several at once.
     */
    private static final ThreadLocal<Integer> FILLING = ThreadLocal.withInitial(() -> Integer.MAX_VALUE);

    public static void fillingSection(int topBlockY) {
        FILLING.set(topBlockY);
    }

    public static int fillingSection() {
        return FILLING.get();
    }

    /**
     * Where the sky starts for a column, for whichever of the two skies this height belongs to.
     *
     * @param sources the column's real sky sources, which also carry the ground-only copy
     * @param y the height being asked about, which is what decides which sky answers
     * @param vanilla what vanilla would have said, returned unchanged whenever this is off
     */
    public static int lowestSourceY(ChunkSkyLightSources sources, int x, int z, int y, int vanilla) {
        if (sources == null || !split() || y >= splitAt()) {
            return vanilla;
        }
        if (!(sources instanceof GroundSky ground)) {
            return vanilla;
        }
        int below = ground.barrenskies$groundLowestSourceY(x, z);
        return below == GroundSky.UNSET ? vanilla : below;
    }

    /**
     * The ground-only copy of a chunk's sky sources, carried on the vanilla object itself.
     *
     * <p>A second heightmap rather than a second object, because the light engine reaches its sources
     * through the chunk and there is only one hook to hand it something. Filled by the same scan vanilla
     * uses, started at the split instead of at the top of the world.
     */
    public interface GroundSky {
        int UNSET = Integer.MIN_VALUE;

        int barrenskies$groundLowestSourceY(int x, int z);
    }
}
