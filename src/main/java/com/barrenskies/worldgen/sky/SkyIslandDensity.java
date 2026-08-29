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

    /**
     * How far the 3D detail noise can move the island surface, as a fraction of a layer reach.
     *
     * <p>Without this the surface is purely a height field over smooth noise, which can only produce
     * domes however the splines are shaped. This is the term that cuts cliffs, ledges and overhangs into
     * them, and it is the same trick the game uses on its own ground.
     *
     * <p>Kept small on purpose. The noise itself ranges to about plus or minus two, and a density unit is
     * worth a whole layer reach in blocks, so a value that looks modest here moves the surface a long way
     * and can carve a thin island away entirely.
     */
    private static final double DETAIL_STRENGTH = 0.14D;

    /** Extra reach given to the biome mask so island edges are never left reporting the ground biome. */
    public static final double BIOME_MASK_MARGIN = 0.08D;

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
    public static final int LAYER_REACH = 64;

    private SkyIslandDensity() {
    }

    /**
     * Whether any island layer claims this column, evaluated exactly as the density function does.
     *
     * <p>The biome pass runs before terrain and cannot ask what was generated, so without this it names a
     * sky biome for every column above the island floor, including open air between islands.
     */
    public static boolean hasIsland(NormalNoise noise, int x, int z, int layerCount, double threshold, double horizontalScale) {
        for (int i = 0; i < layerCount; i++) {
            double shift = i * 4096.0D;
            // A margin wider than the terrain mask, because the density is interpolated across cells and
            // rock bleeds slightly past where the mask alone says land. Without it those edge columns
            // fall through and report the barren biome from the ground below.
            if (noise.getValue(x * horizontalScale + shift, 0.0D, z * horizontalScale + shift) - threshold + BIOME_MASK_MARGIN > 0.0D) {
                return true;
            }
        }
        return false;
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
        int bandBottom,
        int bandTop,
        int layerCount,
        double threshold,
        double horizontalScale
    ) {
        // Ridge noise varies the island surface across a landmass. Cached per column, since it has no
        // height component and would otherwise be recomputed for every block in the column.
        DensityFunction ridgeField = DensityFunctions.flatCache(
            DensityFunctions.shiftedNoise2d(DensityFunctions.zero(), DensityFunctions.zero(), 0.25D, ridges)
        );

        // Three dimensional detail, which is what turns a smooth dome into terrain with faces and ledges.
        DensityFunction detailField = DensityFunctions.mul(
            DensityFunctions.noise(detail, 1.0D, 0.6D), DensityFunctions.constant(DETAIL_STRENGTH)
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
            DensityFunction fadeUp = DensityFunctions.add(
                DensityFunctions.yClampedGradient(centre, centre + LAYER_REACH, 0.0D, -1.0D), top
            );
            DensityFunction fadeDown = DensityFunctions.add(
                DensityFunctions.yClampedGradient(centre - LAYER_REACH, centre, -1.0D, 0.0D), bottom
            );
            // Detail is added after the two fades meet, so it cuts into the top and the underside alike.
            // Far from any island the fades are strongly negative, so it cannot strand rock in open sky.
            layers.add(DensityFunctions.add(DensityFunctions.min(fadeUp, fadeDown), detailField));
        }

        DensityFunction combined = layers.getFirst();
        for (int i = 1; i < layers.size(); i++) {
            combined = DensityFunctions.max(combined, layers.get(i));
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
     * Turns the island mask into a surface height offset.
     *
     * <p>The outer spline varies the result across a landmass by ridge noise, and the inner splines map how
     * far inland a column is to how thick the island is there. Shaping this way rather than with a plain
     * falloff is what produces headlands, bays and varied cliff heights instead of a dome.
     */
    private static DensityFunction surface(DensityFunction ridgeField, DensityFunction mask, boolean upper) {
        DensityFunctions.Spline.Coordinate ridge = new DensityFunctions.Spline.Coordinate(Holder.direct(ridgeField));
        DensityFunctions.Spline.Coordinate inland = new DensityFunctions.Spline.Coordinate(Holder.direct(mask));

        CubicSpline.Builder<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline =
            CubicSpline.builder(ridge);
        // Low ridge values give flatter islands, high ones give taller and more broken ones.
        // Every peak must stay below one. The fade gradient bottoms out at minus one, so an offset of one or
        // more leaves the column solid however high it goes, which is what grew pillars to the world ceiling.
        spline.addPoint(-1.0F, thickness(inland, upper, upper ? 0.52F : 0.58F));
        spline.addPoint(-0.2F, thickness(inland, upper, upper ? 0.70F : 0.66F));
        spline.addPoint(0.3F, thickness(inland, upper, upper ? 0.90F : 0.74F));
        spline.addPoint(1.0F, thickness(inland, upper, upper ? 0.66F : 0.62F));
        return DensityFunctions.spline(spline.build());
    }

    /**
     * How thick the island is as a function of how far inland the column is.
     *
     * <p>Zero at the shoreline so land tapers out rather than ending in a wall, rising quickly just inland,
     * then easing off so the middle of an island is broad rather than domed.
     */
    private static CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> thickness(
        DensityFunctions.Spline.Coordinate inland, boolean upper, float peak
    ) {
        return CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(inland)
            // Well outside an island the offset is firmly negative, so open sky stays open rather than
            // resting on the threshold at each layer height.
            .addPoint(-1.0F, -1.4F, 0.0F)
            .addPoint(-0.08F, -0.25F, 1.6F)
            // Shape follows Skylands over the Sea: rise steeply from the shore, peak early, then fall away
            // again deeper in. Holding a plateau across the interior instead is what made islands read as
            // domes, since a flat top with rounded edges is exactly that.
            .addPoint(0.0F, 0.0F, 2.0F)
            .addPoint(0.145F, peak * 0.82F, upper ? 0.9F : 0.7F)
            .addPoint(0.316F, peak, upper ? -0.63F : -0.45F)
            .addPoint(0.418F, peak * 0.40F, 0.0F)
            .addPoint(0.75F, peak * (upper ? 0.30F : 0.45F), 0.0F)
            .build();
    }
}
