package com.barrenskies.worldgen.sky;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out one layout per world. Terrain and biomes are generated in separate passes and must agree,
 * so they have to be reading the same island placement rather than each building their own.
 */
public final class SkyIslandLayouts {
    private static final Map<String, SkyIslandLayout> CACHE = new ConcurrentHashMap<>();

    public static SkyIslandLayout forSeed(long seed, SkyIslandLayout.Settings settings) {
        return CACHE.computeIfAbsent(seed + ":" + settings, key -> new SkyIslandLayout(seed, settings));
    }

    private SkyIslandLayouts() {
    }
}
