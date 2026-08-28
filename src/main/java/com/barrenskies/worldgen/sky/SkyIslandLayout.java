package com.barrenskies.worldgen.sky;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

/**
 * Decides where sky islands sit and what shape they are. Both the terrain and the biomes are read from
 * this one place so the ground a player lands on always matches the biome the game reports.
 *
 * <p>Islands are large rafts on a jittered grid, sized so they cover roughly half the sky at the default
 * density. They are allowed to overlap: where two rafts meet at different heights they fuse into one
 * stepped landmass, which is where the more interesting shapes come from.
 */
public final class SkyIslandLayout {
    /**
     * Grid pitch for island centres. This and the radius range together set how much sky is land, roughly
     * {@code occupancy * pi * meanRadius^2 / SPACING^2}. Tuning one without the other is what previously
     * left the sky about 98% empty, so keep them in step.
     */
    private static final int SPACING = 520;
    /** Fraction of grid cells holding an island at density 1.0, giving about half the sky as land. */
    private static final double BASE_OCCUPANCY = 0.80D;
    private static final int MIN_RADIUS = 200;
    private static final int MAX_RADIUS = 380;
    /** Vertical spread of raft heights above the layer floor. */
    private static final int ALTITUDE_SPREAD = 48;
    /** Rolling hill amplitude on top of a raft. */
    private static final int HILL_HEIGHT = 14;

    private final long seed;
    private final int floorY;
    private final double density;
    private final Map<Long, List<Island>> cellCache = new ConcurrentHashMap<>();

    public SkyIslandLayout(long seed, int floorY, double density) {
        this.seed = seed;
        this.floorY = floorY;
        this.density = density;
    }

    /** One raft. {@code biomeSelector} is stable per island so the whole raft reads as a single place. */
    public record Island(int centreX, int centreZ, int radius, int deckY, int thickness, int biomeSelector) {
        public double normalisedDistance(int x, int z) {
            double dx = (double) (x - this.centreX) / this.radius;
            double dz = (double) (z - this.centreZ) / this.radius;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** The island covering this column, or null for open sky. Nearest wins where rafts overlap. */
    public Island islandAt(int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, SPACING);
        int cellZ = Math.floorDiv(blockZ, SPACING);

        Island best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Island island : this.cell(cellX + dx, cellZ + dz)) {
                    double distance = this.perturbedDistance(island, blockX, blockZ);
                    if (distance < 1.0D && distance < bestDistance) {
                        bestDistance = distance;
                        best = island;
                    }
                }
            }
        }
        return best;
    }

    /** Top of the raft at this column: a flat deck carrying rolling hills, or MIN_VALUE outside it. */
    public int surfaceY(Island island, int x, int z) {
        double distance = this.perturbedDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }
        // Hills fade towards the rim so the edge stays a clean raft edge rather than a ragged slope.
        double rimFade = Mth.clamp((1.0D - distance) * 3.0D, 0.0D, 1.0D);
        double hills = (this.noise(x, z, 37L) * 0.7D + this.noise(x * 2, z * 2, 53L) * 0.3D) * HILL_HEIGHT * rimFade;
        return island.deckY() + (int) Math.round(hills);
    }

    /** Underside of the raft: shallow and gently rounded, or MIN_VALUE outside it. */
    public int bottomY(Island island, int x, int z) {
        double distance = this.perturbedDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }
        double belly = island.thickness() * Math.sqrt(Math.max(0.0D, 1.0D - distance * distance));
        return island.deckY() - (int) Math.round(belly) - 1;
    }

    /** Distance from the centre with the rim pushed in and out, so rafts are not perfect discs. */
    private double perturbedDistance(Island island, int x, int z) {
        double wobble = this.noise(x, z, 13L) * 0.16D + this.noise(x * 3, z * 3, 29L) * 0.06D;
        return island.normalisedDistance(x, z) - wobble;
    }

    private List<Island> cell(int cellX, int cellZ) {
        return this.cellCache.computeIfAbsent((long) cellX << 32 ^ (cellZ & 0xFFFFFFFFL), key -> this.buildCell(cellX, cellZ));
    }

    private List<Island> buildCell(int cellX, int cellZ) {
        long cellSeed = mix(this.seed, cellX, cellZ);
        if (unitFloat(cellSeed) > Math.min(1.0D, BASE_OCCUPANCY * this.density)) {
            return List.of();
        }

        int radius = MIN_RADIUS + (int) (unitFloat(mix(cellSeed, 1L, 0L)) * (MAX_RADIUS - MIN_RADIUS));
        int x = cellX * SPACING + SPACING / 2 + (int) (signedFloat(cellSeed, 2L) * SPACING * 0.38D);
        int z = cellZ * SPACING + SPACING / 2 + (int) (signedFloat(cellSeed, 3L) * SPACING * 0.38D);
        int deckY = this.floorY + (int) (unitFloat(mix(cellSeed, 4L, 0L)) * ALTITUDE_SPREAD);
        int thickness = 15 + (int) (unitFloat(mix(cellSeed, 5L, 0L)) * 15.0D);

        return List.of(new Island(x, z, radius, deckY, thickness, (int) (cellSeed >>> 24 & 0xFFFF)));
    }

    /** Cheap value noise in the range -1..1, continuous enough for hills and rim wobble. */
    private double noise(int x, int z, long salt) {
        int x0 = Math.floorDiv(x, 32);
        int z0 = Math.floorDiv(z, 32);
        double tx = Mth.smoothstep((x - x0 * 32) / 32.0D);
        double tz = Mth.smoothstep((z - z0 * 32) / 32.0D);
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
