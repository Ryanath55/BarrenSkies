package com.barrenskies.worldgen.sky;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Reads the world noise router on a coarse lattice and interpolates between the samples.
 *
 * <p>Calling a density function per block is what made islands expensive: a terrain mod's final density
 * is a deep tree, and the island band asks for it thousands of times per chunk. Minecraft itself never
 * does that, it evaluates on a cell grid and interpolates, so this does the same. Terrain features at
 * island scale are far larger than a cell, so the shape that survives is the same one.
 *
 * <p>Cells are cached rather than preallocated because each island reads from its own distant region of
 * the noise field, so the addresses touched are scattered rather than contiguous.
 */
public final class CoarseTerrainSampler implements TerrainSampler {
    private static final int CELL_XZ = 8;
    private static final int CELL_Y = 6;

    private final DensityFunction density;
    private final double influence;
    private final Long2DoubleOpenHashMap cache = new Long2DoubleOpenHashMap();

    public CoarseTerrainSampler(DensityFunction density, double influence) {
        this.density = density;
        this.influence = influence;
        this.cache.defaultReturnValue(Double.NaN);
    }

    @Override
    public double density(int x, int y, int z) {
        int cx = Math.floorDiv(x, CELL_XZ);
        int cy = Math.floorDiv(y, CELL_Y);
        int cz = Math.floorDiv(z, CELL_XZ);
        double tx = (x - cx * CELL_XZ) / (double) CELL_XZ;
        double ty = (y - cy * CELL_Y) / (double) CELL_Y;
        double tz = (z - cz * CELL_XZ) / (double) CELL_XZ;

        double x00 = Mth.lerp(tx, this.at(cx, cy, cz), this.at(cx + 1, cy, cz));
        double x10 = Mth.lerp(tx, this.at(cx, cy + 1, cz), this.at(cx + 1, cy + 1, cz));
        double x01 = Mth.lerp(tx, this.at(cx, cy, cz + 1), this.at(cx + 1, cy, cz + 1));
        double x11 = Mth.lerp(tx, this.at(cx, cy + 1, cz + 1), this.at(cx + 1, cy + 1, cz + 1));
        return Mth.lerp(tz, Mth.lerp(ty, x00, x10), Mth.lerp(ty, x01, x11)) * this.influence;
    }

    private double at(int cx, int cy, int cz) {
        long key = (long) cx & 0x1FFFFF | ((long) cz & 0x1FFFFF) << 21 | ((long) cy & 0x3FFFFF) << 42;
        double cached = this.cache.get(key);
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double value = this.density.compute(new DensityFunction.SinglePointContext(cx * CELL_XZ, cy * CELL_Y, cz * CELL_XZ));
        this.cache.put(key, value);
        return value;
    }
}
