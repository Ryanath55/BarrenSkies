package com.barrenskies.worldgen.sky;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

/**
 * Decides where sky islands sit and what shape they are. Both the terrain and the biomes are read from
 * this one place so the ground a player lands on always matches the biome the game reports.
 *
 * <p>Shape comes from layered fractal noise rather than one smooth falloff: broad relief, ridged detail,
 * carved basins and fine roughness, each scaled by the island's {@link TerrainProfile}. That is what makes
 * the result read as landscape instead of a lump, and it is why a mountain island looks nothing like a
 * plains one.
 */
public final class SkyIslandLayout {
    /**
     * Grid pitch for island centres. This and the radius range together set how much sky is land, roughly
     * {@code occupancy * pi * meanRadius^2 / SPACING^2}. Tuning one without the other once left the sky
     * about 98% empty, so keep them in step and re-measure.
     */
    private static final int SPACING = 560;
    /** Fraction of grid cells holding an island at density 1.0. */
    private static final double BASE_OCCUPANCY = 0.55D;
    private static final int MIN_RADIUS = 190;
    private static final int MAX_RADIUS = 360;
    /** Vertical spread of island base heights above the layer floor. */
    private static final int ALTITUDE_SPREAD = 40;

    private final long seed;
    private final int floorY;
    private final double density;
    private final Map<Long, List<Island>> cellCache = new ConcurrentHashMap<>();

    public SkyIslandLayout(long seed, int floorY, double density) {
        this.seed = seed;
        this.floorY = floorY;
        this.density = density;
    }

    /** One island. {@code biomeSelector} is stable per island so the whole island reads as a single place. */
    public record Island(int centreX, int centreZ, int radius, int deckY, int thickness, int biomeSelector) {
        public double normalisedDistance(int x, int z) {
            double dx = (double) (x - this.centreX) / this.radius;
            double dz = (double) (z - this.centreZ) / this.radius;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** The island covering this column, or null for open sky. Nearest wins where islands overlap. */
    public Island islandAt(int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, SPACING);
        int cellZ = Math.floorDiv(blockZ, SPACING);

        Island best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Island island : this.cell(cellX + dx, cellZ + dz)) {
                    double distance = this.shorelineDistance(island, blockX, blockZ);
                    if (distance < 1.0D && distance < bestDistance) {
                        bestDistance = distance;
                        best = island;
                    }
                }
            }
        }
        return best;
    }

    /** Top of the island at this column, or MIN_VALUE outside it. */
    public int surfaceY(Island island, int x, int z, TerrainProfile profile) {
        double distance = this.shorelineDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }

        // Broad swells across the island: the layer that decides where the high ground is.
        double macro = this.fbm(101L, x / 190.0D, z / 190.0D, 4, 0.5D) * profile.macroRelief();
        // Ridged noise stacks into ridgelines and peaks rather than rounded bumps.
        double ridges = this.ridgedFbm(211L, x / 130.0D, z / 130.0D, 4, 0.55D) * profile.ridgeRelief();
        // Basins only ever subtract, scooping hollows that collect water.
        double basins = Math.max(0.0D, this.fbm(307L, x / 150.0D, z / 150.0D, 2, 0.5D)) * profile.basinCarve();
        double rough = this.fbm(409L, x / 34.0D, z / 34.0D, 3, 0.5D) * profile.roughness();

        // Relief flattens towards the rim so the edge stays an edge rather than a ragged slope.
        double inland = Mth.clamp((1.0D - distance) * 2.6D, 0.0D, 1.0D);
        double relief = (macro + ridges - basins + rough) * inland;
        // A shallow dome so the middle of an island sits a little proud of its shore.
        double dome = Math.cos(distance * Math.PI * 0.5D) * 4.0D;

        return island.deckY() + (int) Math.round(relief + dome);
    }

    /** Underside of the island, or MIN_VALUE outside it. */
    public int bottomY(Island island, int x, int z, TerrainProfile profile) {
        double distance = this.shorelineDistance(island, x, z);
        if (distance >= 1.0D) {
            return Integer.MIN_VALUE;
        }

        // Thickness follows an ellipsoid so the island thins towards its rim, sharpened by the profile so
        // eroded islands end in cliffs while flat ones taper gently.
        double taper = Math.pow(Math.max(0.0D, 1.0D - Math.pow(distance, profile.edgeExponent())), 0.62D);
        // Break the underside up so it is not a smooth machined shell.
        double lumps = this.fbm(523L, x / 44.0D, z / 44.0D, 3, 0.55D) * 5.0D
            + this.ridgedFbm(617L, x / 78.0D, z / 78.0D, 2, 0.5D) * 4.0D;

        int belly = (int) Math.round(island.thickness() * taper + lumps * taper);
        int surface = this.surfaceY(island, x, z, profile);
        int bottom = Math.min(island.deckY() - Math.max(profile.minimumThickness(), belly), surface - profile.minimumThickness());
        // Never let an underside hang into the range the ground terrain can reach, or islands clip peaks.
        return Math.max(bottom, this.floorY - 24);
    }

    /**
     * Distance from the centre with the outline pushed in and out by noise. Layering noise here is what
     * stops islands reading as circles, which no amount of surface detail can hide.
     */
    private double shorelineDistance(Island island, int x, int z) {
        double wobble = this.fbm(29L, x / 130.0D, z / 130.0D, 3, 0.5D) * 0.24D
            + this.fbm(37L, x / 46.0D, z / 46.0D, 2, 0.5D) * 0.07D;
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
        int thickness = 15 + (int) (unitFloat(mix(cellSeed, 5L, 0L)) * 18.0D);

        return List.of(new Island(x, z, radius, deckY, thickness, (int) (cellSeed >>> 24 & 0xFFFF)));
    }

    /** Fractal brownian motion: several octaves of value noise, roughly -1..1. */
    private double fbm(long salt, double x, double z, int octaves, double persistence) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            sum += this.valueNoise(salt + i * 131L, x * frequency, z * frequency) * amplitude;
            total += amplitude;
            amplitude *= persistence;
            frequency *= 2.0D;
        }
        return sum / total;
    }

    /** Ridged fractal noise: folds each octave about zero so peaks form creases rather than bumps. */
    private double ridgedFbm(long salt, double x, double z, int octaves, double persistence) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            double folded = 1.0D - Math.abs(this.valueNoise(salt + i * 197L, x * frequency, z * frequency));
            sum += folded * folded * amplitude;
            total += amplitude;
            amplitude *= persistence;
            frequency *= 2.0D;
        }
        return sum / total * 2.0D - 1.0D;
    }

    /** Smooth value noise on a unit lattice, in the range -1..1. */
    private double valueNoise(long salt, double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        double tx = Mth.smoothstep(x - x0);
        double tz = Mth.smoothstep(z - z0);
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
