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
     * How far a layer.s rock fades, in layer reaches, going up from its centre and going down from it.
     *
     * <p>Two, and the same two on both sides. It is not only the slope of the fade: it is also where the
     * fade stops, and stopping is the part that matters. Below this the gradient contributes a constant, so
     * a column whose underside curve asks to go deeper than this never has its density brought back below
     * solid at all -- its rock simply continues, straight down, until the band gate cuts it off a few blocks
     * above the sea. Measured: five hundred and fifty one such columns in seventy four thousand, which is
     * one island column in a hundred and thirty five, and each one of them a pillar hanging from an island
     * to the ground.
     *
     * <p>So nothing may ask for more than this. See undersideLimit, which is what holds the curve to it.
     */
    private static final int FADE_REACHES = 2;

    /**
     * How far the island field is scaled up before joining the world density. See where it is applied.
     */
    private static final double SCALE = 24.0D;

    /**
     * Vertical distance from a layer to where its rock has completely faded out.
     *
     * <p>Islands therefore reach this far below the configured floor, which the biome side has to allow
     * for. Switching pools at the floor itself left the underside of every island taking barren biomes.
     */
    public static int layerReach() {
        return com.barrenskies.BarrenSkiesConfig.ISLAND_THICKNESS.get();
    }

    /**
     * The lowest block an island can reach. Everything below this is ground and nothing above it is.
     *
     * <p>Asked by anything that has to tell the two apart without looking: which biome pool a column
     * belongs to, how far down to look when a structure must be given the sea floor rather than a
     * mountain top in the sky.
     */
    public static int islandFloor() {
        return bandBottom() - underReach();
    }

    /**
     * How far below its layer an island.s rock can reach.
     *
     * <p>The fade, and nothing else. Rock is impossible below this by construction: the band gate cuts the
     * whole field off at exactly this height, and the fade has run out of room to go lower in any case.
     * Anything at all that has to tell island from ground reads this -- which biome pool a column belongs
     * to, how far down a structure looks for the sea floor, how far the overworld.s caves are lifted, and
     * where the two skies are split.
     *
     * <p>It used to ask the underside curve where it bottomed out, and that was wrong twice over. It read
     * the curve at the deepest mask a perfect noise could produce, and the noise is not perfect -- masks
     * half again that large were measured. And it took no account of the two three dimensional terms added
     * after the layers combine, which lift the density and so carry the rock several blocks further down
     * than the shape alone says. Between them the answer came out seventy six blocks when the truth was
     * eighty four, and eight blocks of island were living below the floor that everything else trusted.
     */
    public static int underReach() {
        return FADE_REACHES * layerReach();
    }

    /**
     * The most the underside curve may ask for, in layer reaches.
     *
     * <p>The curve does not end at its last control point; past it the spline runs on down the tip slope,
     * which is the whole reason islands come to a point rather than stopping flat. What was supposed to
     * stop it was the fade. A fade does not stop anything -- it clamps, and a clamped gradient below its
     * own floor is a constant. So the curve has to be held back itself, and this is where.
     *
     * <p>Set below the fade by the headroom, because the density the fade has to bring back under solid is
     * not the curve alone: the detail and landform noise are added to it afterwards and both push upward.
     * Leave them out and the rock terminates on paper and not in the world.
     *
     * <p>Only the extrapolated tail is affected. The last control point sits at mask 0.38 and the limit is
     * not reached until about 0.42, so every number tuned in the workbench is untouched and only the run-on
     * past the end of the curve is held.
     */
    private static double undersideLimit() {
        return FADE_REACHES - noiseHeadroom() - 0.02D;
    }

    /**
     * How far the three dimensional terms can lift the density, and so how much room the fade must keep.
     *
     * <p>Both are noises of unit amplitude times their strength. Measured over seventy four thousand island
     * columns they peaked at 0.97 and 0.80 of their amplitudes, which at the default strengths is 0.157 of
     * density between them; the bound is written at the full amplitude rather than at what was observed,
     * since a bound that holds only for the sample is not a bound.
     */
    private static double noiseHeadroom() {
        return DETAIL_STRENGTH
            + (com.barrenskies.BarrenSkiesConfig.LANDFORM_NOISE.get()
                ? com.barrenskies.BarrenSkiesConfig.LANDFORM_STRENGTH.get() : 0.0D);
    }

    /**
     * The island band, clamped so the tallest island still has room under the roof of the world.
     *
     * <p>Here rather than in the generator because several things outside the density need to agree on it:
     * which biome pool a column belongs to, how far down a structure has to look for ground, and where the
     * mirrored ore ends up. Two copies of a clamp is one copy too many.
     */
    public static int bandBottom() {
        return Math.min(com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(), bandCeiling());
    }

    public static int bandTop() {
        return Math.min(
            Math.max(bandBottom(), com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_TOP.get()), bandCeiling());
    }

    /**
     * The highest a layer centre may sit and still have its island fit under the world ceiling.
     *
     * <p>Rock fades out twice the layer reach above a centre, so a band top left where the config asks
     * for it can want ground above the top of the world, and what it gets instead is a flat slice where
     * the island was cut off by the build limit. Only bites where the two have been set against each
     * other -- a tall island band in a world sized for a shorter one -- which is exactly what happens
     * when the defaults move and an existing config file does not.
     */
    private static int bandCeiling() {
        return com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_MIN_Y
            + com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_HEIGHT - 1 - layerReach() * FADE_REACHES;
    }

    /**
     * The highest block an island can reach: the top of the band, plus the reach a layer fades over.
     *
     * <p>Doubled above, and not below, because a layer sits on its ridge rather than in the middle of its
     * own thickness -- the deck rises above the layer height and the bowl hangs the full reach beneath it.
     */
    public static int islandCeiling() {
        return bandTop() + layerReach() * FADE_REACHES;
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
        NormalNoise islands, NormalNoise ridges, NormalNoise detail, NormalNoise landform,
        int bandBottom, int bandTop, int layerCount, double threshold, double horizontalScale,
        double landformStrength, double landformSquash, boolean landformOn
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
     * The island field at a point, exactly as {@link #build} composes it.
     *
     * <p>Everything except the interpolation across noise cells, which moves a surface by about a block and
     * is why anything reading this should leave itself that much room.
     *
     * <p>And except the caves, which this does not model at all. It used to, back when they were ours and
     * were one noise it could evaluate as cheaply as any other; they are Minecraft's own cave stack now,
     * a dozen functions deep. So a stream can be planned across a spot where a cavern breaks the deck, and
     * the water will pour into it -- which is what a river over a cave does on the ground too.
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
            // Held to what the fade can bring back under solid; see undersideLimit.
            bottom[i] = Math.min(BOWL_SHAPE.apply(shape), undersideLimit());
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
                column.top()[i] - clamp(dy, 0.0D, FADE_REACHES),
                column.bottom()[i] + clamp(dy, -FADE_REACHES, 0.0D)
            );
            if (value > best) {
                best = value;
            }
        }
        best += field.detail().getValue(x, y, z) * DETAIL_STRENGTH;
        if (field.landformOn()) {
            best += field.landform().getValue(x, y * field.landformSquash(), z) * field.landformStrength();
        }
        return best;
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
        Holder<NormalNoise.NoiseParameters> landformNoise,
        int bandBottom,
        int bandTop,
        int layerCount,
        double threshold,
        double horizontalScale,
        net.minecraft.core.HolderLookup.RegistryLookup<DensityFunction> functions,
        net.minecraft.core.HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises
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
        List<DensityFunction> masks = new ArrayList<>(layerCount);
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

            masks.add(mask);

            // One column cache, not two. flatCache already keeps a value a column, and cache2d inside it
            // was a second cache over the same key doing the same job.
            DensityFunction top = DensityFunctions.flatCache(surface(ridgeField, mask, true));
            DensityFunction bottom = DensityFunctions.flatCache(surface(ridgeField, mask, false));

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
                DensityFunctions.yClampedGradient(
                    centre, centre + reach * FADE_REACHES, 0.0D, -FADE_REACHES), top
            );
            DensityFunction fadeDown = DensityFunctions.add(
                DensityFunctions.yClampedGradient(
                    centre - reach * FADE_REACHES, centre, -FADE_REACHES, 0.0D), bottom
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
            // Minecraft's own caves, lifted to island altitude. Combined by taking the lesser of the two,
            // which is how the ground does it: the tunnel wall sits where the mask crosses zero and
            // nowhere else, so a tunnel is the same width at an island's edge as in the middle of it.
            //
            // Inside the interpolation rather than after it, which was worth measuring both ways. After
            // it the mask is read at every block; inside it, only at the corners of each four block cell,
            // which is about forty times fewer reads and showed up as the whole difference between 145
            // and 196 milliseconds a chunk. It carves more this way, not less -- 5.1 percent of the
            // island against 4.3 -- because interpolation spreads a carved corner into its neighbours as
            // readily as it fills one in. What it costs is sharpness: a tunnel narrower than a cell comes
            // out as a smoothed hollow rather than a crisp bore.
            //
            // Gated on whether any layer claims the column at all, which is the second half of the trick
            // the band gate plays and multiplies with it. The band gate spares the cells above and below
            // the islands; this one spares the columns beside them, and beside them is where most of the
            // sky is -- measured, 74428 island columns in 490000, so seven columns in eight have no rock
            // at any height for a cave to be cut out of. The whole lifted stack is half a dozen
            // multi-octave noises deep and was being evaluated in all of them to carve air into air.
            //
            // Worth three percent of a chunk, measured on all three bench passes and consistent across
            // them, which for this file is the difference between a result and a wobble. Costs one
            // flatCache read a block, and the branch not taken costs nothing: RangeChoice fills its input
            // as an array and then computes only the chosen side of each element.
            DensityFunction mask = IslandCaves.carve(functions, noises);
            if (mask != null) {
                DensityFunction land = masks.getFirst();
                for (int i = 1; i < masks.size(); i++) {
                    land = DensityFunctions.max(land, masks.get(i));
                }
                combined = DensityFunctions.rangeChoice(
                    DensityFunctions.flatCache(land),
                    // Four times the margin rock can actually bleed past the mask by. The deck spline
                    // leaves zero with a slope of 9, so a mask of -0.021 is the furthest out the two 3D
                    // terms can lift a column solid; anything tighter would leave an uncarved rim.
                    -BIOME_MASK_MARGIN, Double.POSITIVE_INFINITY,
                    DensityFunctions.min(combined, mask),
                    combined);
            }
        }

        // Scaled up before being handed to the world. The island field naturally sits within about plus or
        // minus one, but it is combined with the world density by taking the greater of the two, and open
        // air in the world density runs far more negative than that. Left unscaled it would lift genuine
        // air up against the solid threshold and scatter spikes across the world. Scaling both sides
        // equally leaves the surface exactly where it was.
        // Interpolated across noise cells, the way the game interpolates its own terrain. Without this the
        // per-column caching below shows through as flat square steps rather than a smooth surface.
        //
        // The band gate sits inside the interpolation and not outside it, which is the whole trick. An
        // interpolated function is filled by the chunk for every cell of the column whether or not
        // anything ends up reading it, so gating from the outside saves nothing at all: the work is done
        // before the gate is ever consulted. Inside, the cells above and below the islands still get
        // filled, but filling them costs a height comparison and a constant instead of four layers of
        // spline work and two noise lookups a block. Islands occupy about three hundred blocks of a
        // thousand, so seven cells in ten now cost nothing.
        DensityFunction height = DensityFunctions.yClampedGradient(
            com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_MIN_Y,
            com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_MIN_Y + com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_HEIGHT,
            com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_MIN_Y,
            com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_MIN_Y + com.barrenskies.worldgen.BarrenSkiesWorldgen.WORLD_HEIGHT
        );
        DensityFunction band = DensityFunctions.rangeChoice(
            height,
            bandBottom - reach * FADE_REACHES,
            bandTop + reach * FADE_REACHES + 1,
            DensityFunctions.mul(combined, DensityFunctions.constant(SCALE)),
            // Far enough below solid that taking the greater of this and the world's own density leaves
            // the world's answer untouched.
            DensityFunctions.constant(-64.0D)
        );
        return DensityFunctions.interpolated(band);
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
     * Turns the island mask into a surface height offset.
     *
     * <p>The outer spline varies the result across a landmass by ridge noise, and the inner splines map how
     * far inland a column is to how thick the island is there. Shaping this way rather than with a plain
     * falloff is what produces headlands, bays and varied cliff heights instead of a dome.
     */
    private static DensityFunction surface(DensityFunction ridgeField, DensityFunction mask, boolean upper) {
        DensityFunctions.Spline.Coordinate inland = new DensityFunctions.Spline.Coordinate(Holder.direct(mask));
        if (!upper) {
            // Clamped for the same reason columnOf clamps it: past its last control point the curve runs
            // on, and the fade below has only so much room to bring it back under solid.
            return DensityFunctions.spline(underside(inland)).clamp(-1000.0D, undersideLimit());
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
     * The underside: one arc that flares out beneath the rim and then runs down to a point.
     *
     * <p>Nothing like the top, and that is the point. It rises without ever falling back and takes no
     * notice of ridge; mirroring the top profile down here instead gives a dome under a dome, which is what
     * made islands read as spheres.
     *
     * <p>An S rather than a bowl, which is the difference between a hemisphere and a floating mountain. The
     * bowl this replaces was steepest at the rim and flattest in the middle -- it plunged a third of its
     * depth within two hundredths of mask and then eased off for the rest, so the deepest part of an island
     * was also the flattest and every underside read as the bottom of a bell. This one leaves the rim
     * steeply, keeps steepening to a knee just inside it, and then eases into a long taper that is still
     * descending when it runs out of mask. Convex above the knee, concave below it.
     *
     * <p>The last point is not the end of the curve. Past it the spline carries on along the tip slope, so
     * the biggest islands keep deepening instead of stopping flat, which is what makes a point rather than
     * a plug. What stops it is undersideLimit, and it has to: the fade below was believed to stop it and
     * does not, since a clamped gradient below its own floor is a constant and a constant added to a
     * positive curve never comes back under solid. Left to run, one island column in a hundred and thirty
     * five was solid from the island to the sea.
     *
     * <p>Numbers tuned in the workbench rather than argued about here. The limit sits past the last control
     * point, so none of them are touched by it and only the run-on beyond the curve is held.
     */
    private static <C, I extends ToFloatFunction<C>> CubicSpline<C, I> underside(I inland) {
        return CubicSpline.<C, I>builder(inland)
            .addPoint(-1.0F, -1.4F, 0.0F)
            .addPoint(-0.08F, -0.25F, 1.6F)
            .addPoint(0.0F, 0.0F, 6.0F)
            .addPoint(0.057F, 0.3933F, 7.65F)
            .addPoint(0.38F, 1.71F, 2.35F)
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
