package com.barrenskies.worldgen.sky;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

/**
 * Decides where sky islands sit and what shape they are. Both the terrain and the biomes are read from
 * this one place so the ground a player lands on always matches the biome the game reports.
 *
 * <p>Islands are carved from a 3D density field rather than drawn as a top and bottom surface. A height
 * field can only ever produce one ground level per column, which is why earlier versions came out as
 * pancakes however the noise was tuned. Working in three dimensions gives overhangs, arches, notched
 * cliffs and detached fragments, because the rock is decided per block rather than per column.
 */
public final class SkyIslandLayout {
    /**
     * Ground altitude that an island deck is mapped onto when reading terrain density. Around normal
     * surface level, so the top of an island follows real topography rather than deep stone.
     */
    private static final int TERRAIN_REFERENCE_Y = 76;

    /** How far the 3D noise can push rock beyond the smooth envelope, in blocks. */
    private static final double SURFACE_BAND = 18.0D;

    private final long seed;
    private final Settings settings;
    private final Map<Long, List<Island>> cellCache = new ConcurrentHashMap<>();

    /**
     * Tunable shape parameters, all config driven.
     *
     * @param bandBottom lowest Y an island may occupy
     * @param bandTop highest Y an island may occupy
     * @param density fraction of grid cells holding an island, scaled
     * @param minRadius smallest island radius in blocks
     * @param maxRadius largest island radius in blocks
     * @param spacing grid pitch for island centres; with the radii this sets how much sky is land
     */
    public record Settings(int bandBottom, int bandTop, double density, int minRadius, int maxRadius, int spacing) {
    }

    public SkyIslandLayout(long seed, Settings settings) {
        this.seed = seed;
        this.settings = settings;
    }

    public Settings settings() {
        return this.settings;
    }

    /** One island. {@code biomeSelector} is stable per island so the whole island reads as a single place. */
    public record Island(
        int centreX, int centreZ, int radius, int deckY, int thickness, int biomeSelector, int sampleOffsetX, int sampleOffsetZ
    ) {
        public double normalisedDistance(int x, int z) {
            double dx = (double) (x - this.centreX) / this.radius;
            double dz = (double) (z - this.centreZ) / this.radius;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** The smooth envelope of an island at one column, before 3D noise breaks it up. */
    public record Envelope(int top, int bottom) {
        public boolean isEmpty() {
            return this.top < this.bottom;
        }
    }

    /** The island covering this column, or null for open sky. Nearest wins where islands overlap. */
    public Island islandAt(int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, this.settings.spacing());
        int cellZ = Math.floorDiv(blockZ, this.settings.spacing());

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

    /**
     * The smooth top and bottom of an island at this column. This is only the envelope that the 3D field
     * is evaluated around; the actual rock surface is decided by {@link #isSolid}.
     */
    public Envelope envelope(Island island, int x, int z, TerrainProfile profile) {
        double distance = this.shorelineDistance(island, x, z);
        if (distance >= 1.0D) {
            return new Envelope(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        // Broad swells across the island: the layer that decides where the high ground is.
        double macro = this.fbm(101L, x / 190.0D, z / 190.0D, 4, 0.5D) * profile.macroRelief();
        // Ridged noise stacks into ridgelines and peaks rather than rounded bumps.
        double ridges = this.ridgedFbm(211L, x / 130.0D, z / 130.0D, 4, 0.55D) * profile.ridgeRelief();
        // Basins only ever subtract, scooping hollows that collect water.
        double basins = Math.max(0.0D, this.fbm(307L, x / 150.0D, z / 150.0D, 2, 0.5D)) * profile.basinCarve();
        double rough = this.fbm(409L, x / 34.0D, z / 34.0D, 3, 0.5D) * profile.roughness();

        // Relief is carried almost all the way to the rim. Flattening it early is what produced a level
        // deck ending in a sheer drop; letting it run means the edge height varies along the shoreline.
        double inland = Mth.clamp((1.0D - distance) * 6.0D, 0.0D, 1.0D);
        double relief = (macro + ridges - basins + rough) * (0.35D + 0.65D * inland);
        double dome = Math.cos(distance * Math.PI * 0.5D) * 4.0D;
        // Break the rim itself up and down so the cliff line is ragged rather than a clean circle.
        double cliffBand = Math.max(0.0D, 1.0D - Math.abs(distance - 0.82D) * 5.0D);
        double cliff = this.ridgedFbm(701L, x / 27.0D, z / 27.0D, 3, 0.55D) * cliffBand * profile.ridgeRelief() * 0.7D;
        int top = island.deckY() + (int) Math.round(relief + dome + cliff);

        double taper = Math.pow(Math.max(0.0D, 1.0D - Math.pow(distance, profile.edgeExponent())), 0.62D);
        // The underside gets far more relief than the top: hanging spurs, gouges and a rolling belly,
        // because a smooth shell is what made islands read as slabs from below.
        double belly3d = this.fbm(523L, x / 58.0D, z / 58.0D, 4, 0.55D) * island.thickness() * 0.55D
            + this.ridgedFbm(617L, x / 96.0D, z / 96.0D, 3, 0.5D) * island.thickness() * 0.65D
            + this.fbm(811L, x / 23.0D, z / 23.0D, 2, 0.5D) * 5.0D;
        int belly = (int) Math.round((island.thickness() + belly3d) * taper);
        int bottom = Math.min(island.deckY() - Math.max(profile.minimumThickness(), belly), top - profile.minimumThickness());

        return new Envelope(top, Math.max(bottom, this.settings.bandBottom() - 40));
    }

    /**
     * Whether there is rock at this block. Deep inside the envelope the answer is yes without touching the
     * noise; only within {@link #SURFACE_BAND} of a surface does the 3D field get evaluated, which is what
     * keeps this affordable while still producing overhangs and floating shards.
     */
    public boolean isSolid(Island island, int x, int y, int z, TerrainProfile profile, Envelope envelope, TerrainSampler terrain) {
        if (envelope.isEmpty()) {
            return false;
        }

        // Signed distance to the nearest envelope surface: positive inside, negative outside.
        boolean underside = y < island.deckY();
        double signed = y >= envelope.top()
            ? envelope.top() - y
            : y <= envelope.bottom() ? y - envelope.bottom() : Math.min(y - envelope.bottom(), envelope.top() - y);

        // The underside is allowed a wider working band than the top, so spurs and gouges can reach
        // further than surface detail does.
        double band = underside ? SURFACE_BAND * 1.7D : SURFACE_BAND;
        if (signed > band) {
            return true;
        }
        if (signed < -band) {
            return false;
        }

        // Terrain density from the world's own noise router, read from a distant place and from normal
        // ground altitude, then lifted here. With a terrain mod installed this is that mod's shaping, so
        // islands inherit its character instead of looking like generic noise.
        double lifted = terrain.density(
            x + island.sampleOffsetX(), y - island.deckY() + TERRAIN_REFERENCE_Y, z + island.sampleOffsetZ()
        );
        double borrowed = Mth.clamp(lifted, -1.0D, 1.0D) * band * profile.terrainInfluence();

        // Our own 3D detail on top, which keeps things broken up where the router is smooth.
        double detail = this.fbm3D(733L, x / 30.0D, y / 21.0D, z / 30.0D, 3, 0.5D);
        double veins = this.ridgedFbm3D(839L, x / 52.0D, y / 34.0D, z / 52.0D, 2, 0.5D);
        double push = (detail * 0.72D + veins * 0.38D) * band * profile.overhang() * (underside ? 1.45D : 1.0D);

        return signed + push + borrowed > 0.0D;
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
        if (unitFloat(cellSeed) > Math.min(1.0D, this.settings.density())) {
            return List.of();
        }

        int spacing = this.settings.spacing();
        int radius = this.settings.minRadius()
            + (int) (unitFloat(mix(cellSeed, 1L, 0L)) * Math.max(1, this.settings.maxRadius() - this.settings.minRadius()));
        int x = cellX * spacing + spacing / 2 + (int) (signedFloat(cellSeed, 2L) * spacing * 0.38D);
        int z = cellZ * spacing + spacing / 2 + (int) (signedFloat(cellSeed, 3L) * spacing * 0.38D);

        // Spread islands through the whole band so they sit on genuinely different levels.
        int span = Math.max(0, this.settings.bandTop() - this.settings.bandBottom());
        int deckY = this.settings.bandBottom() + (int) (unitFloat(mix(cellSeed, 4L, 0L)) * span);
        // Thickness scales with radius so small islands are not slabs and large ones are not wafers.
        int thickness = (int) (radius * (0.16D + unitFloat(mix(cellSeed, 5L, 0L)) * 0.16D)) + 10;

        // Each island reads terrain density from a different, distant place, so one island lifts a
        // mountainside and its neighbour a plain rather than every island sharing one shape.
        int sampleX = (int) (signedFloat(cellSeed, 6L) * 400000.0D);
        int sampleZ = (int) (signedFloat(cellSeed, 7L) * 400000.0D);

        return List.of(new Island(x, z, radius, deckY, thickness, (int) (cellSeed >>> 24 & 0xFFFF), sampleX, sampleZ));
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

    private double fbm3D(long salt, double x, double y, double z, int octaves, double persistence) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            sum += this.valueNoise3D(salt + i * 151L, x * frequency, y * frequency, z * frequency) * amplitude;
            total += amplitude;
            amplitude *= persistence;
            frequency *= 2.0D;
        }
        return sum / total;
    }

    private double ridgedFbm3D(long salt, double x, double y, double z, int octaves, double persistence) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            double folded = 1.0D - Math.abs(this.valueNoise3D(salt + i * 173L, x * frequency, y * frequency, z * frequency));
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

    private double valueNoise3D(long salt, double x, double y, double z) {
        int x0 = Mth.floor(x);
        int y0 = Mth.floor(y);
        int z0 = Mth.floor(z);
        double tx = Mth.smoothstep(x - x0);
        double ty = Mth.smoothstep(y - y0);
        double tz = Mth.smoothstep(z - z0);

        double c000 = lattice3D(this.seed + salt, x0, y0, z0);
        double c100 = lattice3D(this.seed + salt, x0 + 1, y0, z0);
        double c010 = lattice3D(this.seed + salt, x0, y0 + 1, z0);
        double c110 = lattice3D(this.seed + salt, x0 + 1, y0 + 1, z0);
        double c001 = lattice3D(this.seed + salt, x0, y0, z0 + 1);
        double c101 = lattice3D(this.seed + salt, x0 + 1, y0, z0 + 1);
        double c011 = lattice3D(this.seed + salt, x0, y0 + 1, z0 + 1);
        double c111 = lattice3D(this.seed + salt, x0 + 1, y0 + 1, z0 + 1);

        double x00 = Mth.lerp(tx, c000, c100);
        double x10 = Mth.lerp(tx, c010, c110);
        double x01 = Mth.lerp(tx, c001, c101);
        double x11 = Mth.lerp(tx, c011, c111);
        return Mth.lerp(tz, Mth.lerp(ty, x00, x10), Mth.lerp(ty, x01, x11));
    }

    private static double lattice3D(long salt, int x, int y, int z) {
        return signedFloat(mix(mix(salt, x, z), y, 977L), 0L);
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
