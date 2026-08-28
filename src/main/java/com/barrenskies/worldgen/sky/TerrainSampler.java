package com.barrenskies.worldgen.sky;

/**
 * Reads terrain density from whatever noise router the world is using. With a terrain mod installed
 * these are that mod's density functions, so islands can be shaped by the same maths that shapes its
 * ground rather than by noise of our own invention.
 */
@FunctionalInterface
public interface TerrainSampler {
    /** Density at a point: positive is rock, negative is air. Roughly -1..1 after clamping. */
    double density(int x, int y, int z);

    /** Used when no router is available, such as in offline shape tests. */
    TerrainSampler NONE = (x, y, z) -> 0.0D;
}
