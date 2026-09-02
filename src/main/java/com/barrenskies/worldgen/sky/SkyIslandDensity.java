package com.barrenskies.worldgen.sky;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Builds the sky island terrain out of the same density function primitives the game uses for its own
 * ground: a 2D noise mask saying where land is, splines turning that into a surface height, and a vertical
 * gradient turning the surface into solid rock.
 *
 * <p>The approach is adapted from Klinbee's Skylands over the Sea (MIT licensed), which builds floating
 * islands entirely as datapack density functions. Doing it with the same primitives rather than with noise
 * of our own is what makes the result read as Minecraft terrain, and it also means the whole surrounding
 * pipeline treats islands as ordinary ground.
 *
 * <p>Where this departs from that mod is composition. It ships a datapack that replaces the overworld
 * outright, which cannot coexist with another terrain mod owning the same file. This builds the equivalent
 * functions in code so they can be combined with whatever router the world already has.
 *
 * <p>Islands are stacked in several layers whose vertical spans overlap, and the layers are combined by
 * taking whichever is denser. Overlapping islands therefore merge into one mass or pass over each other
 * rather than averaging their heights.
 */
public final class SkyIslandDensity {
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLANDS = noise("islands");
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLAND_RIDGES = noise("island_ridges");
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLAND_DETAIL = noise("island_detail");
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLAND_CAVES = noise("island_caves");
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLAND_LANDFORM = noise("island_landform");

    /**
     * The waver laid over a stream's heading. Short wavelength, so the line loosens over a few blocks
     * rather than swinging the whole course one way; the slope decides where a channel goes, not this.
     */
    public static final ResourceKey<NormalNoise.NoiseParameters> ISLAND_STREAMS = noise("island_streams");

    /**
     * Fine surface texture, in density units. One unit moves the surface a whole layer reach, so this is
     * about a block and a half of undulation. The landform noise below is the same idea at landform scale.
     */
    private static final double DETAIL_STRENGTH = 0.038D;

    /** Extra reach given to the biome mask so island edges are never left reporting the ground biome. */
    public static final double BIOME_MASK_MARGIN = 0.08D;

    /**
     * How far the island field is scaled up before joining the world density. See where it is applied.
     */
    private static final double SCALE = 24.0D;

    /**
     * How sharply cave carving is faded out towards the island surface. Carving reaches full strength once
     * the island density passes one over this, so a larger number leaves a thinner skin of solid rock.
     * Eight works out at roughly five blocks.
     */
    private static final double SKIN_FADE = 8.0D;

    /**
     * Vertical distance from a layer to where its rock has completely faded out.
     *
     * <p>Islands therefore reach this far below the configured floor, which the biome side has to allow
     * for. Switching pools at the floor itself left the underside of every island taking barren biomes.
     */
    public static int layerReach() {
        return com.barrenskies.BarrenSkiesConfig.ISLAND_THICKNESS.get();
    }

    private SkyIslandDensity() {
    }

    /**
     * Whether any island layer claims this column, evaluated exactly as the density function does.
     *
     * <p>The biome pass runs before terrain and cannot ask what was generated, so without this it names a
     * sky biome for every column above the island floor, including open air between islands.
     */
    public static boolean hasIsland(NormalNoise noise, int x, int z, int layerCount, double threshold, double horizontalScale) {
        // A margin wider than the terrain mask, because the density is interpolated across cells and rock
        // bleeds slightly past where the mask alone says land. Without it those edge columns fall through
        // and report the barren biome from the ground below.
        return islandStrength(noise, x, z, layerCount, threshold, horizontalScale) + BIOME_MASK_MARGIN > 0.0D;
    }

    /**
     * How far inside an island a column is, as the mask sees it. Negative outside one.
     *
     * <p>The same number {@link #hasIsland} works from, without the margin that method adds for the biome
     * pass. Anything that needs to know whether there is actually rock here, rather than whether a biome
     * should be named as though there were, wants this one: the margin is deliberately generous, and asking
     * it for ground gives an answer that is wrong at exactly the edges where it matters.
     */
    public static double islandStrength(NormalNoise noise, int x, int z, int layerCount, double threshold, double horizontalScale) {
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < layerCount; i++) {
            double shift = i * 4096.0D;
            double mask = noise.getValue(x * horizontalScale + shift, 0.0D, z * horizontalScale + shift) - threshold;
            if (mask > best) {
                best = mask;
            }
        }
        return best;
    }

    /** The two things the shape splines read: how far inland a column is, and its ridge value. */
    public record Shape(double mask, double ridge) {
    }

    /** One of those two, as a spline coordinate, so the deck can be evaluated without a density function. */
    private record Axis(boolean ridge) implements ToFloatFunction<Shape> {
        @Override
        public float apply(Shape shape) {
            return (float) (this.ridge ? shape.ridge() : shape.mask());
        }

        @Override
        public float minValue() {
            return -1000.0F;
        }

        @Override
        public float maxValue() {
            return 1000.0F;
        }
    }

    /** The same deck the density is built from, as arithmetic over a mask and a ridge value. */
    private static final CubicSpline<Shape, Axis> DECK_SHAPE =
        SkyIslandDensity.<Shape, Axis>deck(new Axis(true), new Axis(false));

    /**
     * Where the island surface is at a column and how far inside an island that column is.
     *
     * @param surfaceY the top of the rock: of one named layer if one was named, otherwise of whichever
     *     layer stands highest here. Negative infinity where the layer in question has no ground at all
     * @param mask how far inside an island; again of the named layer, or the best of all of them
     * @param layer which layer is furthest inside here, whether or not a layer was named
     * @param covered whether some other layer has ground above the named one, which is to say that this
     *     column is under a second island rather than open to the sky
     */
    public record Ground(double surfaceY, double mask, int layer, boolean covered) {
        public boolean hasGround() {
            return this.surfaceY > Double.NEGATIVE_INFINITY;
        }
    }

    /**
     * The island surface from the height field alone, without the 3D terms that texture it.
     *
     * <p>This is what anything planning across a distance has to work from. The height field is noise and
     * answers for any coordinate; the surface the player actually walks on needs a chunk that has already
     * been generated, and a stream is ten chunks long. The 3D terms move the ground a few blocks either
     * way, which is texture; which way is downhill over a hundred and sixty blocks is this, and this is the
     * answer that matters.
     *
     * <p>Layers whose deck has not risen above zero are skipped rather than compared. Their spline runs
     * down past minus one, so an empty top layer sits about fifty blocks below its own centre, which is
     * still higher than a real island two layers further down: comparing them by height alone would put the
     * surface out in open sky.
     *
     * <p>Both the mask and the height are asked of one layer when one is named, and that is what keeps a
     * stream on the island it started on. Reporting whichever layer stands highest reads the wrong ground
     * entirely wherever two islands overlap: a channel running along one layer passes under an island a
     * layer up, the surface it is handed leaps a hundred blocks, and coming back out from under it the
     * surface falls the same hundred again. Measured, every run that ended anywhere other than an edge
     * ended on exactly that -- a drop of fifty four to a hundred and seventy four blocks in a single block
     * of travel, at a mask of 0.23 to 0.46, which is to say in the middle of an island and nowhere near
     * the end of one. What is true of such a column is not that the ground fell away but that there is a
     * second island over it, which is what {@code covered} says instead.
     *
     * @param layer which layer to answer for, or -1 for whichever stands highest
     */
    public static Ground ground(
        NormalNoise islands, NormalNoise ridges, double x, double z,
        int bandBottom, int bandTop, int layerCount, double threshold, double horizontalScale, int layer
    ) {
        double ridge = ridges.getValue(x, 0.0D, z);
        int reach = layerReach();
        int spacing = layerCount > 1 ? (bandTop - bandBottom) / (layerCount - 1) : 0;
        double bestY = Double.NEGATIVE_INFINITY;
        double namedY = Double.NEGATIVE_INFINITY;
        double topMask = Double.NEGATIVE_INFINITY;
        double named = Double.NEGATIVE_INFINITY;
        int topLayer = -1;

        for (int i = 0; i < layerCount; i++) {
            double shift = i * 4096.0D;
            double mask = islands.getValue(x * horizontalScale + shift, 0.0D, z * horizontalScale + shift) - threshold;
            if (mask > topMask) {
                topMask = mask;
                topLayer = i;
            }
            if (i == layer) {
                named = mask;
            }
            if (mask <= 0.0D) {
                continue;
            }
            double offset = DECK_SHAPE.apply(new Shape(mask, ridge));
            if (offset <= 0.0D) {
                continue;
            }
            double y = bandBottom + spacing * i + offset * reach;
            if (y > bestY) {
                bestY = y;
            }
            if (i == layer) {
                namedY = y;
            }
        }
        if (layer < 0) {
            return new Ground(bestY, topMask, topLayer, false);
        }
        return new Ground(namedY, named, topLayer, bestY > namedY);
    }

    /** The mask value alone, as a spline coordinate, for the underside and the cave carve. */
    private static final Axis ONLY_MASK = new Axis(false);

    private static final CubicSpline<Shape, Axis> BOWL_SHAPE =
        SkyIslandDensity.<Shape, Axis>underside(ONLY_MASK);

    private static final CubicSpline<Shape, Axis> CARVE_SHAPE =
        SkyIslandDensity.<Shape, Axis>carve(ONLY_MASK);

    /**
     * Every noise the island field is built from, bound to one world.
     *
     * <p>Enough to work out what the generator will actually put at a point, rather than only what the
     * height field says about a column. Those are different surfaces: the height field is smooth, and the
     * detail and landform noise move the ground several blocks either way on top of it. Planning against
     * the smooth one and cutting against the real one is what leaves a channel needing to be clamped where
     * the two disagree, and every one of those clamps is a step back up, a source in the wrong place, or a
     * channel that stops early.
     */
    public record Field(
        NormalNoise islands, NormalNoise ridges, NormalNoise detail, NormalNoise landform, NormalNoise caves,
        int bandBottom, int bandTop, int layerCount, double threshold, double horizontalScale,
        double landformStrength, double landformSquash, boolean landformOn, boolean cavesOn
    ) {
        public int reach() {
            return layerReach();
        }

        public int spacing() {
            return this.layerCount > 1 ? (this.bandTop - this.bandBottom) / (this.layerCount - 1) : 0;
        }

        public double centre(int layer) {
            return this.bandBottom + (double) spacing() * layer;
        }

        public double mask(double x, double z, int layer) {
            double shift = layer * 4096.0D;
            return this.islands.getValue(
                x * this.horizontalScale + shift, 0.0D, z * this.horizontalScale + shift
            ) - this.threshold;
        }
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : Math.min(value, high);
    }

    /**
     * One layer's rock at a point: solid where the fade from above and the fade from below agree.
     *
     * <p>The same two gradients the density builds, written out. Each runs to twice the reach and to minus
     * two rather than stopping at minus one, which is why the clamps go to two.
     */
    private static double layerAt(Field field, double mask, double ridge, int layer, double y) {
        double dy = (y - field.centre(layer)) / field.reach();
        double up = DECK_SHAPE.apply(new Shape(mask, ridge)) - clamp(dy, 0.0D, 2.0D);
        double down = BOWL_SHAPE.apply(new Shape(mask, ridge)) + clamp(dy, -2.0D, 0.0D);
        return Math.min(up, down);
    }

    /**
     * The island field at a point, exactly as {@link #build} composes it.
     *
     * <p>Everything except the interpolation across noise cells, which moves a surface by about a block and
     * is why anything reading this should leave itself that much room.
     */
    public static double density(Field field, double x, double y, double z) {
        return density(field, columnOf(field, x, z), x, y, z);
    }

    /**
     * Everything about a column that does not change going down it.
     *
     * <p>Which is most of the work. A ridge lookup and one island lookup per layer, and then two splines
     * each, none of which vary with height -- and a scan down a column asked for all of them again at
     * every block. Read once and the same scan costs a detail lookup and a landform lookup a block, and
     * nothing else.
     *
     * @param top where the deck stands, in density units, per layer; NaN where a layer has no ground here
     * @param bottom where the underside sits, likewise
     */
    public record Column(double[] top, double[] bottom) {
    }

    public static Column columnOf(Field field, double x, double z) {
        double ridge = field.ridges().getValue(x, 0.0D, z);
        double[] top = new double[field.layerCount()];
        double[] bottom = new double[field.layerCount()];
        for (int i = 0; i < field.layerCount(); i++) {
            double mask = field.mask(x, z, i);
            if (mask <= 0.0D) {
                top[i] = Double.NaN;
                continue;
            }
            Shape shape = new Shape(mask, ridge);
            top[i] = DECK_SHAPE.apply(shape);
            bottom[i] = BOWL_SHAPE.apply(shape);
        }
        return new Column(top, bottom);
    }

    public static double density(Field field, Column column, double x, double y, double z) {
        int reach = field.reach();
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < column.top().length; i++) {
            if (Double.isNaN(column.top()[i])) {
                continue;
            }
            double dy = (y - field.centre(i)) / reach;
            double value = Math.min(
                column.top()[i] - clamp(dy, 0.0D, 2.0D),
                column.bottom()[i] + clamp(dy, -2.0D, 0.0D)
            );
            if (value > best) {
                best = value;
            }
        }
        best += field.detail().getValue(x, y, z) * DETAIL_STRENGTH;
        if (field.landformOn()) {
            best += field.landform().getValue(x, y * field.landformSquash(), z) * field.landformStrength();
        }
        if (field.cavesOn()) {
            best += caveCut(field, best, x, y, z);
        }
        return best;
    }

    /** How much the cave carver takes out of the rock at a point. Never positive. */
    public static double caveCut(Field field, double rock, double x, double y, double z) {
        double cut = CARVE_SHAPE.apply(new Shape(field.caves().getValue(x, y, z), 0.0D));
        if (cut > 0.0D) {
            cut = 0.0D;
        }
        return cut * clamp(rock * SKIN_FADE, 0.0D, 1.0D);
    }

    /**
     * The top of the rock a layer actually generates in a column, caves and all.
     *
     * <p>Searched rather than solved, because once the three dimensional terms are in the surface stops
     * being a function of x and z at all. The search is bounded: a term of amplitude A can only move the
     * surface by A reaches, so it looks that far either side of where the height field said the surface
     * would be. Returns MIN_VALUE where the column has nothing solid in that window.
     */
    public static int surfaceOf(Field field, double x, double z, int layer, int slack) {
        double ridge = field.ridges().getValue(x, 0.0D, z);
        double mask = field.mask(x, z, layer);
        double offset = DECK_SHAPE.apply(new Shape(mask, ridge));
        if (mask <= 0.0D || offset <= 0.0D) {
            return Integer.MIN_VALUE;
        }
        int from = (int) Math.ceil(field.centre(layer) + offset * field.reach()) + slack;
        int to = (int) Math.floor(field.centre(layer) - Math.max(0.0D, BOWL_SHAPE.apply(new Shape(mask, 0.0D)))
            * field.reach()) - slack;
        for (int y = from; y >= to; y--) {
            if (density(field, x, y, z) > 0.0D) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** How far below a point the rock keeps going, stopping at the first gap taller than a cave mouth. */
    public static int floorUnder(Field field, double x, double z, int from, int lowest, int caveSkip) {
        int bottom = from;
        int gap = 0;
        for (int y = from - 1; y >= lowest && gap <= caveSkip; y--) {
            if (density(field, x, y, z) > 0.0D) {
                bottom = y;
                gap = 0;
            } else {
                gap++;
            }
        }
        return bottom;
    }

    private static ResourceKey<NormalNoise.NoiseParameters> noise(String path) {
        return ResourceKey.create(Registries.NOISE, ResourceLocation.fromNamespaceAndPath("barrenskies", path));
    }

    /**
     * @param bandBottom centre height of the lowest island layer
     * @param bandTop centre height of the highest island layer
     * @param layerCount how many overlapping layers to spread across that range
     * @param threshold how much of the noise counts as land; higher leaves more open sky
     * @param horizontalScale island size; smaller values stretch the noise into larger landmasses
     */
    public static DensityFunction build(
        Holder<NormalNoise.NoiseParameters> islands,
        Holder<NormalNoise.NoiseParameters> ridges,
        Holder<NormalNoise.NoiseParameters> detail,
        Holder<NormalNoise.NoiseParameters> caveNoise,
        Holder<NormalNoise.NoiseParameters> landformNoise,
        int bandBottom,
        int bandTop,
        int layerCount,
        double threshold,
        double horizontalScale
    ) {
        // Ridge noise varies the island surface across a landmass. Cached per column, since it has no
        // height component and would otherwise be recomputed for every block in the column.
        //
        // Deliberately our own noise alone. Averaging it with the world's own ridge field, so that island
        // terraces would follow the ground's, narrowed the spread of the result: it then rarely reached the
        // bands that cut the terraces and the islands went back to smooth domes. Two noises averaged are
        // flatter than either.
        DensityFunction ridgeField = DensityFunctions.flatCache(
            DensityFunctions.shiftedNoise2d(DensityFunctions.zero(), DensityFunctions.zero(), 1.0D, ridges)
        );

        int reach = layerReach();
        // Fine surface texture, deliberately tiny. A density unit is worth a whole reach in blocks, so this
        // works out at one or two blocks of undulation: the small rises real plains have, rather than the
        // perfectly level plate a pure height field otherwise gives. Earlier attempts at this were an order
        // of magnitude stronger and made the tops lumpy instead.
        DensityFunction detailField = DensityFunctions.mul(
            DensityFunctions.noise(detail, 1.0D, 1.0D), DensityFunctions.constant(DETAIL_STRENGTH)
        );

        List<DensityFunction> layers = new ArrayList<>(layerCount);
        int spacing = layerCount > 1 ? (bandTop - bandBottom) / (layerCount - 1) : 0;

        for (int i = 0; i < layerCount; i++) {
            int centre = bandBottom + spacing * i;
            // Each layer offsets the noise so its islands sit in different places rather than stacking
            // directly on top of one another.
            double shift = i * 4096.0D;
            DensityFunction mask = DensityFunctions.flatCache(
                DensityFunctions.add(
                    DensityFunctions.constant(-threshold),
                    DensityFunctions.shiftedNoise2d(
                        DensityFunctions.constant(shift), DensityFunctions.constant(shift), horizontalScale, islands
                    )
                )
            );

            DensityFunction top = DensityFunctions.flatCache(DensityFunctions.cache2d(surface(ridgeField, mask, true)));
            DensityFunction bottom = DensityFunctions.flatCache(DensityFunctions.cache2d(surface(ridgeField, mask, false)));

            // Rock fades out going up from the layer centre, and again going down, each offset by how far
            // inland the column is. Taking the lesser of the two means a column is only solid where both
            // agree, which is what gives an island a top and an underside.
            //
            // Each fade runs to twice the reach and to minus two rather than stopping at minus one. It
            // passes through the same value at the island surface, so the shape is unchanged, but it keeps
            // falling below instead of levelling off. Stopping at minus one left the density at the deepest
            // point of the bowl sitting a hundredth below solid, since the bowl offset reaches 0.9875, and
            // anything that nudged it - detail noise, or interpolation against a neighbour - tipped it over.
            // That is what strung trails of blobs from the underside of an island down towards the ground.
            DensityFunction fadeUp = DensityFunctions.add(
                DensityFunctions.yClampedGradient(centre, centre + reach * 2, 0.0D, -2.0D), top
            );
            DensityFunction fadeDown = DensityFunctions.add(
                DensityFunctions.yClampedGradient(centre - reach * 2, centre, -2.0D, 0.0D), bottom
            );
            layers.add(DensityFunctions.min(fadeUp, fadeDown));
        }

        DensityFunction combined = layers.getFirst();
        for (int i = 1; i < layers.size(); i++) {
            combined = DensityFunctions.max(combined, layers.get(i));
        }

        // Both 3D terms are added once, after the layers combine, rather than once inside each of them.
        // Adding the same value to every layer and then taking the greater gives the same answer, so with
        // four layers this is the same shape for a quarter of the noise lookups.
        combined = DensityFunctions.add(combined, detailField);
        if (com.barrenskies.BarrenSkiesConfig.LANDFORM_NOISE.get()) {
            combined = DensityFunctions.add(combined, landform(landformNoise));
        }

        if (com.barrenskies.BarrenSkiesConfig.ISLAND_CAVES.get()) {
            // Cached first, because the carve reads it a second time to work out how deep under the
            // surface it is. Without this the whole layer stack above is evaluated twice per block.
            DensityFunction rock = DensityFunctions.cacheOnce(combined);
            combined = DensityFunctions.add(rock, caves(caveNoise, rock));
        }
        // Scaled up before being handed to the world. The island field naturally sits within about plus or
        // minus one, but it is combined with the world density by taking the greater of the two, and open
        // air in the world density runs far more negative than that. Left unscaled it would lift genuine
        // air up against the solid threshold and scatter spikes across the world. Scaling both sides
        // equally leaves the surface exactly where it was.
        // Interpolated across noise cells, the way the game interpolates its own terrain. Without this the
        // per-column caching below shows through as flat square steps rather than a smooth surface.
        return DensityFunctions.interpolated(DensityFunctions.mul(combined, DensityFunctions.constant(SCALE)));
    }

    /**
     * The three dimensional term that stops an island being a height field.
     *
     * <p>Minecraft's own terrain is a depth gradient with a 3D noise added to it, and that noise is the
     * whole reason ground has overhangs, ledges and broken edges rather than being a surface with texture
     * on it. Islands had only the fine detail noise, which is the same idea an order of magnitude too weak
     * to change any shape, and that is most of why they read as smooth and round.
     *
     * <p>The vertical squash matters as much as the strength. Sampling the noise more slowly in Y than
     * across it stretches every feature into a horizontal shelf; sampled evenly it just scatters round
     * lumps. Vanilla runs its own 3D noise at half the vertical frequency for exactly this reason.
     */
    private static DensityFunction landform(Holder<NormalNoise.NoiseParameters> landformNoise) {
        return DensityFunctions.mul(
            DensityFunctions.noise(landformNoise, 1.0D, com.barrenskies.BarrenSkiesConfig.LANDFORM_SQUASH.get()),
            DensityFunctions.constant(com.barrenskies.BarrenSkiesConfig.LANDFORM_STRENGTH.get())
        );
    }

    /**
     * Tunnels through an island, carved where a 3D noise passes through zero.
     *
     * <p>A noise zero crossing is a winding sheet through space, so subtracting where the noise is near
     * zero cuts a connected tunnel network rather than isolated pockets. Nothing is subtracted anywhere
     * else. The technique is from Skylands over the Sea, whose own carver is capped at Y 80 and so never
     * reaches an island: their island caves come entirely from the density like this.
     *
     * <p>Nothing here sets how wide a tunnel is. The width is how far the noise travels while it is inside
     * the band this spline dips through, which is a property of the noise wavelength, so the spline and the
     * cave noise parameters have to be read together. See the note on the cave noise for why that matters.
     */
    private static DensityFunction caves(Holder<NormalNoise.NoiseParameters> caveNoise, DensityFunction island) {
        DensityFunctions.Spline.Coordinate field = new DensityFunctions.Spline.Coordinate(
            Holder.direct(DensityFunctions.noise(caveNoise, 1.0D, 1.0D))
        );
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> carve = carve(field);
        // Clamped at zero so the positive shoulders of the spline cannot add rock where there was none.
        DensityFunction cut = DensityFunctions.min(
            DensityFunctions.interpolated(DensityFunctions.spline(carve)), DensityFunctions.zero()
        );

        // Faded out through the outer skin of the island. Without this a tunnel opens a mouth wherever it
        // passes near the surface, and it opens the widest one exactly there: the cave wall sits where the
        // carve cancels the island density, so as that density falls away towards the surface the tunnel
        // flares out to the full width of the carved band. Multiplying by the island density holds the last
        // few blocks of rock closed, so a mouth only appears where a tunnel genuinely runs out through it.
        DensityFunction skin = DensityFunctions.mul(island, DensityFunctions.constant(SKIN_FADE)).clamp(0.0D, 1.0D);
        return DensityFunctions.mul(cut, skin);
    }

    /**
     * Turns the island mask into a surface height offset.
     *
     * <p>The outer spline varies the result across a landmass by ridge noise, and the inner splines map how
     * far inland a column is to how thick the island is there. Shaping this way rather than with a plain
     * falloff is what produces headlands, bays and varied cliff heights instead of a dome.
     */
    private static DensityFunction surface(DensityFunction ridgeField, DensityFunction mask, boolean upper) {
        DensityFunctions.Spline.Coordinate inland = new DensityFunctions.Spline.Coordinate(Holder.direct(mask));
        if (!upper) {
            return DensityFunctions.spline(underside(inland));
        }
        DensityFunctions.Spline.Coordinate ridge = new DensityFunctions.Spline.Coordinate(Holder.direct(ridgeField));
        return DensityFunctions.spline(deck(ridge, inland));
    }

    /**
     * The deck: three heights selected by ridge, each of them a profile against how far inland a column is.
     *
     * <p>Ridge decides whether a stretch of island stands tall or low, and the bands are deliberately
     * narrow: the height collapses across a tenth of ridge, which is what cuts terraces and cliff lines
     * across a landmass. Three heights rather than two, so a landmass steps between levels instead of being
     * one uniform plate. Values follow Skylands over the Sea.
     *
     * <p>Written against any spline coordinate rather than against density functions, because it is built
     * twice: once into the world's density, and once as plain arithmetic for the stream planner, which has
     * to know where the island surface is at coordinates no chunk exists at yet. A second copy of these
     * control points written out by hand would drift from this one the moment either was tuned.
     */
    private static <C, I extends ToFloatFunction<C>> CubicSpline<C, I> deck(I ridge, I inland) {
        CubicSpline.Builder<C, I> spline = CubicSpline.builder(ridge);
        spline.addPoint(-1.00F, top(inland, 1.095F, 1.240F));
        spline.addPoint(-0.49F, top(inland, 0.380F, 0.610F));
        spline.addPoint(-0.12F, top(inland, 0.125F, 0.160F));
        spline.addPoint(0.12F, top(inland, 0.125F, 0.160F));
        spline.addPoint(0.49F, top(inland, 0.380F, 0.610F));
        spline.addPoint(1.00F, top(inland, 1.095F, 1.240F));
        return spline.build();
    }

    /**
     * The underside: a bowl that dives steadily deeper towards the middle of an island.
     *
     * <p>Nothing like the top, and that is the point. It rises without ever falling back, takes no notice of
     * ridge, and reaches almost the full layer reach at the centre. Mirroring the top profile down here
     * instead gives a dome under a dome, which is what made islands read as spheres. Values follow Skylands
     * over the Sea, which is where the shape comes from.
     */
    private static <C, I extends ToFloatFunction<C>> CubicSpline<C, I> carve(I field) {
        return CubicSpline.<C, I>builder(field)
            .addPoint(-0.035F, 0.1F, 0.0F)
            .addPoint(0.0F, -2.5F, 0.0F)
            .addPoint(0.035F, 0.1F, 0.0F)
            .build();
    }

    private static <C, I extends ToFloatFunction<C>> CubicSpline<C, I> underside(I inland) {
        return CubicSpline.<C, I>builder(inland)
            .addPoint(-1.0F, -1.4F, 0.0F)
            .addPoint(-0.08F, -0.25F, 1.6F)
            .addPoint(0.0F, 0.0F, 0.0F)
            .addPoint(0.02F, 0.350F, 0.0F)
            .addPoint(0.05F, 0.550F, 0.0F)
            .addPoint(0.10F, 0.750F, 0.0F)
            .addPoint(0.18F, 0.880F, 0.0F)
            .addPoint(0.30F, 0.960F, 0.0F)
            .addPoint(0.50F, 0.9875F, 0.0F)
            .build();
    }

    /**
     * The upper surface: a steep rise at the very shore, then a level deck.
     *
     * <p>The deck never falls back inland. Peaking partway across and easing down again, as the shape this
     * was taken from does, puts the high point near the median mask, and the result is a raised rim around
     * every island with the middle sloping away from it. Rising once and then holding flat is what a plains
     * island should look like.
     *
     * <p>The mask positions matter more than the heights. Measured over a million columns, half of all island
     * ground sits below mask 0.13 and only one percent passes 0.6. Skylands over the Sea puts its rise
     * across 0 to 0.32 and only flattens past 0.42, which against this distribution means nearly every
     * column is still climbing, and a surface that climbs everywhere is a dome. The rise is compressed into
     * the first 0.05 so it reads as a cliff at the shoreline, and everything beyond is deck.
     */
    private static <C, I extends ToFloatFunction<C>> CubicSpline<C, I> top(I inland, float shore, float deck) {
        return CubicSpline.<C, I>builder(inland)
            // Firmly negative outside an island, so open sky stays open rather than resting on the
            // threshold at each layer height.
            .addPoint(-1.0F, -1.4F, 0.0F)
            .addPoint(-0.05F, -0.20F, 2.0F)
            // Almost all of the height is gained in the first few hundredths of mask, which puts a cliff at
            // the waterline and leaves the rest of the island level.
            .addPoint(0.0F, 0.0F, 9.0F)
            .addPoint(0.04F, shore, 1.2F)
            .addPoint(0.12F, deck, 0.1F)
            .addPoint(0.40F, deck, 0.0F)
            .build();
    }
}
