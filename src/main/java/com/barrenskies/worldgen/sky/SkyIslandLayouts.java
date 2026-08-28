package com.barrenskies.worldgen.sky;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out one layout per world. Terrain and biomes are generated in separate passes and must agree,
 * so they have to be reading the same island placement rather than each building their own.
 */
public final class SkyIslandLayouts {
    private static final Map<String, SkyIslandLayout> CACHE = new ConcurrentHashMap<>();

    public static SkyIslandLayout forSeed(long seed, int floorY, double density) {
        return CACHE.computeIfAbsent(seed + ":" + floorY + ":" + density, key -> new SkyIslandLayout(seed, floorY, density));
    }

    private SkyIslandLayouts() {
    }
}
