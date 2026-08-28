package com.barrenskies.worldgen.sky;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

/**
 * Decides where sky islands sit and what shape they are. Both the terrain and the biomes are read from
 * this one place so the ground a player lands on always matches the biome the game reports.
 *
 * <p>Islands are grouped into archipelago clusters: a coarse grid of cluster cells, most of them empty,
 * each populated cell holding a handful of islands. That gives short hops within a cluster and long
 * crossings between them.
 */
public final class SkyIslandLayout {
    /** Edge length of one cluster cell. Only a fraction of cells hold a cluster. */
    private static final int CLUSTER_SPACING = 1280;
    /** How far islands in a cluster spread from its centre. */
    private static final int CLUSTER_RADIUS = 360;
    private static final int MIN_ISLANDS_PER_CLUSTER = 3;
    private static final int MAX_ISLANDS_PER_CLUSTER = 6;
    private static final int MIN_RADIUS = 30;
    private static final int MAX_RADIUS = 75;
    /** Vertical spread of islands above the layer floor. */
    private static final int ALTITUDE_SPREAD = 72;

    private final long seed;
    private final int floorY;
    private final double density;
    private final Map<Long, List<Island>> clusterCache = new ConcurrentHashMap<>();

    public SkyIslandLayout(long seed, int floorY, double density) {
        this.seed = seed;
        this.floorY = floorY;
        this.density = density;
    }

    /**
     * One island. Radius and keel depth are in blocks; {@code biomeSelector} is a stable value used to
     * pick this island's biome, so the whole island reads as a single place.
     */
    public record Island(int centreX, int centreZ, int radius, int topY, int keelDepth, int thickness, int biomeSelector) {
        public double normalisedDistance(int x, int z) {
            double dx = (double) (x - this.centreX) / this.radius;
            double dz = (double) (z - this.centreZ) / this.radius;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** The island covering this column, or null for open sky. */
    public Island islandAt(int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, CLUSTER_SPACING);
        int cellZ = Math.floorDiv(blockZ, CLUSTER_SPACING);

        Island best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Island island : this.cluster(cellX + dx, cellZ + dz)) {
                    double distance = island.normalisedDistance(blockX, blockZ);
                    // The rim is pushed outwards by noise, so allow a margin before discarding the island.
                    if (distance < 1.35D && distance < bestDistance) {
                        bestDistance = distance;
                        best = island;
                    }
                }
            }
        }
        return best;
    }

    /** Height of the island's upper surface at this column, or {@link Integer#MIN_VALUE} outside it. */
    public int surfaceY(Island island, int x, int z) {
        double distance = this.perturbedDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }
        // A few broad steps rather than a smooth dome, so islands read as terraced from the side.
        double roll = this.noise(island.centreX() + x, island.centreZ() + z, 37L);
        int terrace = (int) (Math.round(roll * 2.0D) * 3.0D);
        return island.topY() + terrace - (int) (distance * distance * 6.0D);
    }

    /** Height of the island's underside at this column, or {@link Integer#MIN_VALUE} outside it. */
    public int bottomY(Island island, int x, int z) {
        double distance = this.perturbedDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }
        int surface = this.surfaceY(island, x, z);
        // A lens-shaped body, plus a keel that plunges far below the centre and tapers away at the rim.
        double body = island.thickness() * Math.sqrt(Math.max(0.0D, 1.0D - distance * distance));
        double taper = 1.0D - distance;
        double keel = island.keelDepth() * taper * taper * taper;
        // Break the keel tip into spurs so it frays rather than ending in a cone.
        double spur = this.noise(x, z, 91L) * 8.0D * Math.max(0.0D, 1.0D - distance * 2.0D);
        return surface - (int) (body + keel + spur) - 1;
    }

    /** Distance from the centre with the rim pushed in and out, so islands are not discs. */
    private double perturbedDistance(Island island, int x, int z) {
        double wobble = this.noise(x, z, 13L) * 0.22D + this.noise(x * 3, z * 3, 29L) * 0.07D;
        return island.normalisedDistance(x, z) - wobble;
    }

    private List<Island> cluster(int cellX, int cellZ) {
        return this.clusterCache.computeIfAbsent((long) cellX << 32 ^ (cellZ & 0xFFFFFFFFL), key -> this.buildCluster(cellX, cellZ));
    }

    private List<Island> buildCluster(int cellX, int cellZ) {
        long cellSeed = mix(this.seed, cellX, cellZ);
        // Most cells are empty sky. Density scales how many hold a cluster at all.
        if (unitFloat(cellSeed) > 0.32D * this.density) {
            return List.of();
        }

        int centreX = cellX * CLUSTER_SPACING + CLUSTER_SPACING / 2 + (int) (signedFloat(cellSeed, 1L) * CLUSTER_SPACING * 0.42D);
        int centreZ = cellZ * CLUSTER_SPACING + CLUSTER_SPACING / 2 + (int) (signedFloat(cellSeed, 2L) * CLUSTER_SPACING * 0.42D);
        int count = MIN_ISLANDS_PER_CLUSTER
            + (int) (unitFloat(mix(cellSeed, 3L, 0L)) * (MAX_ISLANDS_PER_CLUSTER - MIN_ISLANDS_PER_CLUSTER + 1));
        count = Math.min(count, MAX_ISLANDS_PER_CLUSTER);

        List<Island> islands = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long islandSeed = mix(cellSeed, 100L + i, 0L);
            int radius = MIN_RADIUS + (int) (unitFloat(islandSeed) * (MAX_RADIUS - MIN_RADIUS));
            int x = centreX + (int) (signedFloat(islandSeed, 11L) * CLUSTER_RADIUS);
            int z = centreZ + (int) (signedFloat(islandSeed, 12L) * CLUSTER_RADIUS);
            int topY = this.floorY + (int) (unitFloat(mix(islandSeed, 13L, 0L)) * ALTITUDE_SPREAD);
            // Bigger islands hang deeper, which keeps the silhouette proportionate.
            int keelDepth = (int) (radius * (0.9D + unitFloat(mix(islandSeed, 14L, 0L)) * 0.8D));
            int thickness = 8 + (int) (radius * 0.28D);

            Island candidate = new Island(x, z, radius, topY, keelDepth, thickness, (int) (islandSeed >>> 24 & 0xFFFF));
            if (islands.stream().noneMatch(other -> overlaps(other, candidate))) {
                islands.add(candidate);
            }
        }
        return List.copyOf(islands);
    }

    /** Islands may sit close together but should not merge into a single mass. */
    private static boolean overlaps(Island a, Island b) {
        double dx = a.centreX() - b.centreX();
        double dz = a.centreZ() - b.centreZ();
        return Math.sqrt(dx * dx + dz * dz) < (a.radius() + b.radius()) * 1.15D;
    }

    /** Cheap value noise in the range -1..1, continuous enough for rim wobble. */
    private double noise(int x, int z, long salt) {
        int x0 = Math.floorDiv(x, 24);
        int z0 = Math.floorDiv(z, 24);
        double tx = Mth.smoothstep((x - x0 * 24) / 24.0D);
        double tz = Mth.smoothstep((z - z0 * 24) / 24.0D);
        double n00 = signedFloat(mix(this.seed + salt, x0, z0), 0L);
        double n10 = signedFloat(mix(this.seed + salt, x0 + 1, z0), 0L);
        double n01 = signedFloat(mix(this.seed + salt, x0, z0 + 1), 0L);
        double n11 = signedFloat(mix(this.seed + salt, x0 + 1, z0 + 1), 0L);
        return Mth.lerp(tz, Mth.lerp(tx, n00, n10), Mth.lerp(tx, n01, n11));
    }

    private static long mix(long seed, long a, long b) {
        long h = seed ^ a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ h >>> 33;
    }

    private static double unitFloat(long value) {
        return (value >>> 11) / (double) (1L << 53);
    }

    private static double signedFloat(long value, long salt) {
        return unitFloat(mix(value, salt, 7L)) * 2.0D - 1.0D;
    }
}
