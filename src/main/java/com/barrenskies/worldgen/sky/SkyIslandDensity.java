package com.barrenskies.worldgen.sky;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.CubicSpline;
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
     * Where streams run across the island tops. Long wavelength, so a channel is a wide slow curve rather
     * than a wiggle, and there are few enough of them that an island gets one or two rather than a network.
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
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> carve =
            CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(field)
                .addPoint(-0.035F, 0.1F, 0.0F)
                .addPoint(0.0F, -2.5F, 0.0F)
                .addPoint(0.035F, 0.1F, 0.0F)
                .build();
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

        // Ridge decides whether a stretch of island stands tall or low, and the bands are deliberately
        // narrow: the height collapses across a tenth of ridge, which is what cuts terraces and cliff lines
        // across a landmass. Values follow Skylands over the Sea.
        DensityFunctions.Spline.Coordinate ridge = new DensityFunctions.Spline.Coordinate(Holder.direct(ridgeField));
        CubicSpline.Builder<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline =
            CubicSpline.builder(ridge);
        // Three deck heights rather than two, so a landmass steps between levels instead of being one
        // uniform plate. The transitions stay narrow, which is what makes them read as cliffs.
        spline.addPoint(-1.00F, top(inland, 1.095F, 1.240F));
        spline.addPoint(-0.49F, top(inland, 0.380F, 0.610F));
        spline.addPoint(-0.12F, top(inland, 0.125F, 0.160F));
        spline.addPoint(0.12F, top(inland, 0.125F, 0.160F));
        spline.addPoint(0.49F, top(inland, 0.380F, 0.610F));
        spline.addPoint(1.00F, top(inland, 1.095F, 1.240F));
        return DensityFunctions.spline(spline.build());
    }

    /**
     * The underside: a bowl that dives steadily deeper towards the middle of an island.
     *
     * <p>Nothing like the top, and that is the point. It rises without ever falling back, takes no notice of
     * ridge, and reaches almost the full layer reach at the centre. Mirroring the top profile down here
     * instead gives a dome under a dome, which is what made islands read as spheres. Values follow Skylands
     * over the Sea, which is where the shape comes from.
     */
    private static CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> underside(
        DensityFunctions.Spline.Coordinate inland
    ) {
        return CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(inland)
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
    private static CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> top(
        DensityFunctions.Spline.Coordinate inland, float shore, float deck
    ) {
        return CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(inland)
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
