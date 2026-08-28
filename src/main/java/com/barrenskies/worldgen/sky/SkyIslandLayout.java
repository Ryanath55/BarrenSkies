package com.barrenskies.worldgen.sky;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

/**
 * Decides where sky islands sit and what shape they are. Both the terrain and the biomes are read from
 * this one place so the ground a player lands on always matches the biome the game reports.
 *
 * <p>Outlines come from a thresholded noise field rather than from a radius around a centre. A radius
 * can only ever make a circle, and perturbing it only makes a wobbly circle, which is why earlier
 * islands read as round however much noise was added. Here an anchor contributes a broad falloff, noise
 * contributes as much again, and land is wherever the sum clears zero, which gives bays, spits,
 * isthmuses and holes.
 *
 * <p>Rock is then decided per block from a 3D field around that surface, so cliffs are notched and
 * undersides are ragged rather than smooth shells.
 */
public final class SkyIslandLayout {
    /**
     * Ground altitude an island deck maps onto when reading terrain density, around normal surface level
     * so island tops follow real topography rather than deep stone.
     */
    public static final int TERRAIN_REFERENCE_Y = 76;

    /** How much of the outline is decided by noise rather than by the anchor falloff. Above about 0.8 the
     * shapes stop reading as islands and start breaking into unconnected debris. */
    private static final double SHAPE_NOISE = 0.72D;

    /**
     * How much landness is spent tapering from full thickness down to nothing at the shore. Larger values
     * give long sloping headlands, smaller ones give abrupt cliffs.
     */
    private static final double SHORE_TAPER = 0.34D;

    /**
     * How far past its own shoreline an island still influences the blended height of its neighbours.
     * Large enough that islands which merely approach each other meet at a slope rather than a step.
     */
    private static final double BLEND_REACH = 0.55D;

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
     * @param spacing grid pitch for island anchors; with the radii this sets how much sky is land
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

    /** One island anchor. {@code biomeSelector} is stable so a whole landmass reads as a single place. */
    public record Island(
        int centreX, int centreZ, int radius, int deckY, int thickness, int biomeSelector, int sampleOffsetX, int sampleOffsetZ
    ) {
    }

    /** The smooth surface of an island at one column, before the 3D field breaks it up. */
    public record Envelope(int top, int bottom, double landness) {
        public boolean isEmpty() {
            return this.top < this.bottom;
        }
    }

    /**
     * How strongly this column belongs to an island: above zero is land, and larger means further from
     * the shore. This is the field the whole shape is built on.
     */
    private double landness(Island island, int x, int z) {
        double dx = (double) (x - island.centreX()) / island.radius();
        double dz = (double) (z - island.centreZ()) / island.radius();
        double falloff = 1.0D - Math.sqrt(dx * dx + dz * dz);

        // Several octaves at island scale, so the coastline has features at every size.
        double shape = this.fbm(29L, x / 220.0D, z / 220.0D, 5, 0.55D) * 0.62D
            + this.fbm(37L, x / 78.0D, z / 78.0D, 3, 0.5D) * 0.26D
            + this.fbm(41L, x / 29.0D, z / 29.0D, 2, 0.5D) * 0.12D;

        return falloff + shape * SHAPE_NOISE;
    }

    /**
     * What a column is made of: which island owns it, how far inland it is, and the height it sits at.
     *
     * <p>{@code deckY} is blended across every island claiming the column rather than taken from the
     * winner alone. Taking the winner's height meant two overlapping islands at different altitudes met
     * along a line where the ground jumped hundreds of blocks in one step, which is where the worst
     * cliffs came from.
     */
    public record Column(Island island, double landness, int deckY) {
    }

    /** The column at this position, or null for open sky. */
    public Column columnAt(int blockX, int blockZ) {
        int cellX = Math.floorDiv(blockX, this.settings.spacing());
        int cellZ = Math.floorDiv(blockZ, this.settings.spacing());

        Island best = null;
        double bestLandness = 0.0D;
        double weightedDeck = 0.0D;
        double totalWeight = 0.0D;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Island island : this.cell(cellX + dx, cellZ + dz)) {
                    double landness = this.landness(island, blockX, blockZ);
                    // Height blending reaches past the shoreline, so an island still pulls on the height of
                    // a neighbour it merely approaches. Without that reach, two islands that meet without
                    // their fields overlapping produce a step of hundreds of blocks at the join.
                    if (landness <= -BLEND_REACH) {
                        continue;
                    }
                    // Squared weighting keeps an island's interior at its own height and confines the
                    // blending to the seam between them.
                    double weight = (landness + BLEND_REACH) * (landness + BLEND_REACH);
                    weightedDeck += weight * island.deckY();
                    totalWeight += weight;
                    if (landness > bestLandness) {
                        bestLandness = landness;
                        best = island;
                    }
                }
            }
        }

        return best == null ? null : new Column(best, bestLandness, (int) Math.round(weightedDeck / totalWeight));
    }

    /** The smooth top and bottom of an island at this column. */
    public Envelope envelope(Column column, int x, int z, TerrainProfile profile) {
        double landness = column.landness();
        Island island = column.island();

        // Broad swells, ridgelines, scooped basins and fine roughness, each scaled by the profile.
        double macro = this.fbm(101L, x / 190.0D, z / 190.0D, 4, 0.5D) * profile.macroRelief();
        double ridges = this.ridgedFbm(211L, x / 130.0D, z / 130.0D, 4, 0.55D) * profile.ridgeRelief();
        double basins = Math.max(0.0D, this.fbm(307L, x / 150.0D, z / 150.0D, 2, 0.5D)) * profile.basinCarve();
        double rough = this.fbm(409L, x / 34.0D, z / 34.0D, 3, 0.5D) * profile.roughness();

        double inland = Mth.clamp(landness * 3.0D, 0.0D, 1.0D);
        double relief = (macro + ridges - basins + rough) * (0.45D + 0.55D * inland);

        // Cliff shelves: quantising the edge height into steps gives ledges and benches down a face
        // instead of a single sheer wall.
        double shelfBand = Math.max(0.0D, 1.0D - landness * 4.0D);
        double shelfNoise = this.ridgedFbm(701L, x / 34.0D, z / 34.0D, 3, 0.55D);
        double shelf = Math.round(shelfNoise * 2.2D) * profile.shelfHeight() * shelfBand;

        double bellyRelief = this.fbm(523L, x / 58.0D, z / 58.0D, 4, 0.55D) * island.thickness() * 0.55D
            + this.ridgedFbm(617L, x / 96.0D, z / 96.0D, 3, 0.5D) * island.thickness() * 0.7D
            + this.fbm(811L, x / 23.0D, z / 23.0D, 2, 0.5D) * 6.0D;

        // Both surfaces close on each other as the shore is approached, so the island thins to nothing at
        // its outline instead of ending in a wall of minimum thickness. Widening SHORE_TAPER makes for
        // longer, gentler headlands; narrowing it brings the cliffs back.
        double shore = Mth.clamp(landness / SHORE_TAPER, 0.0D, 1.0D);
        shore = shore * shore * (3.0D - 2.0D * shore);
        // The taper is applied about the deck, so the top slopes down to meet the rising underside.
        double centre = column.deckY() + (relief + shelf) * shore;
        int top = (int) Math.round(centre);
        int bottom = (int) Math.round(centre - Math.max(1.0D, (island.thickness() + bellyRelief) * shore));

        return new Envelope(top, Math.max(bottom, this.settings.bandBottom() - 48), landness);
    }

    /**
     * Whether there is rock at this block. Deep inside the envelope the answer is yes without touching
     * noise; only near a surface does the 3D field get evaluated, which is what keeps this affordable
     * while still producing overhangs, notches and floating shards.
     */
    public boolean isSolid(Column column, int x, int y, int z, TerrainProfile profile, Envelope envelope, TerrainSampler terrain) {
        if (envelope.isEmpty()) {
            return false;
        }

        Island island = column.island();
        boolean underside = y < column.deckY();
        double signed = y >= envelope.top()
            ? envelope.top() - y
            : y <= envelope.bottom() ? y - envelope.bottom() : Math.min(y - envelope.bottom(), envelope.top() - y);

        // The working band scales with how thick the island is here, so a tall cliff face gets a
        // proportionally deep band to be chewed into rather than a fixed sliver.
        double thickness = envelope.top() - envelope.bottom();
        double band = Mth.clamp(thickness * 0.55D, 10.0D, 46.0D) * (underside ? 1.4D : 1.0D);
        if (signed > band) {
            return true;
        }
        if (signed < -band) {
            return false;
        }

        double lifted = terrain.density(
            x + island.sampleOffsetX(), y - column.deckY() + TERRAIN_REFERENCE_Y, z + island.sampleOffsetZ()
        );
        double borrowed = Mth.clamp(lifted, -1.0D, 1.0D) * band * profile.terrainInfluence();

        double detail = this.fbm3D(733L, x / 30.0D, y / 19.0D, z / 30.0D, 3, 0.5D);
        double veins = this.ridgedFbm3D(839L, x / 52.0D, y / 30.0D, z / 52.0D, 2, 0.5D);
        // Near the shore the field is pushed harder, which is what turns a smooth wall into a broken
        // cliff with ledges, notches and detached stacks.
        double edge = 1.0D + Math.max(0.0D, 1.0D - envelope.landness() * 3.0D) * 0.9D;
        double push = (detail * 0.72D + veins * 0.38D) * band * profile.overhang() * edge * (underside ? 1.35D : 1.0D);

        return signed + push + borrowed > 0.0D;
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
        int x = cellX * spacing + spacing / 2 + (int) (signedFloat(cellSeed, 2L) * spacing * 0.42D);
        int z = cellZ * spacing + spacing / 2 + (int) (signedFloat(cellSeed, 3L) * spacing * 0.42D);

        int span = Math.max(0, this.settings.bandTop() - this.settings.bandBottom());
        int deckY = this.settings.bandBottom() + (int) (unitFloat(mix(cellSeed, 4L, 0L)) * span);
        int thickness = (int) (radius * (0.20D + unitFloat(mix(cellSeed, 5L, 0L)) * 0.22D)) + 10;

        // Each island reads terrain density from a different distant place, so one lifts a mountainside
        // and its neighbour a plain rather than every island sharing one shape.
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
        long s = this.seed + salt;

        double x00 = Mth.lerp(tx, lattice3D(s, x0, y0, z0), lattice3D(s, x0 + 1, y0, z0));
        double x10 = Mth.lerp(tx, lattice3D(s, x0, y0 + 1, z0), lattice3D(s, x0 + 1, y0 + 1, z0));
        double x01 = Mth.lerp(tx, lattice3D(s, x0, y0, z0 + 1), lattice3D(s, x0 + 1, y0, z0 + 1));
        double x11 = Mth.lerp(tx, lattice3D(s, x0, y0 + 1, z0 + 1), lattice3D(s, x0 + 1, y0 + 1, z0 + 1));
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
