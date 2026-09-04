package com.barrenskies.worldgen;

import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * How high a column is counting only the ground, with whatever is hanging above it ignored.
 *
 * <p>Worked out from the blocks rather than from the noise. By the time anything asks this the blocks are
 * there, and they are what a structure is going to stand on: an aquifer's water, the sand a surface rule
 * put down, the floor of a cave the noise did not know it was going to open. Reading them costs a walk
 * down the column, which is a hundred or so lookups in a chunk already in memory, and it is asked only
 * while a structure that must not be given an island is being built.
 */
public final class GroundHeight {
    private GroundHeight() {
    }

    /**
     * The same number the world would report for this column if the islands were not in it.
     *
     * <p>Same convention as the world's own answer, which is the first free block above the surface rather
     * than the surface itself, and the same test for what counts as surface -- the heightmap's own, so
     * asking for the ocean floor still sees through water and asking for the world surface does not.
     *
     * @return the floor of the world if the column has nothing solid in it at all
     */
    public static int of(ChunkAccess chunk, Heightmap.Types type, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bottom = chunk.getMinBuildHeight();
        for (int y = SkyIslandDensity.islandFloor() - 1; y >= bottom; y--) {
            if (type.isOpaque().test(chunk.getBlockState(pos.set(x, y, z)))) {
                return y + 1;
            }
        }
        return bottom;
    }
}
