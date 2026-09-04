package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkies;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Minecraft's own caves, moved up to island altitude and cut into the islands.
 *
 * <p>Not a carver of our own. The one this replaces was ours and it read as ours: even tunnel width, no
 * entrances, nothing that looked like it belonged to the same game as the ground below. These are the
 * functions the overworld is carved with, taken out of the registry as they are -- cave entrances,
 * spaghetti, noodles, pillars and the cheese noises behind the big caverns -- with every height written
 * into them moved up to where the islands are.
 *
 * <p>Which is the whole difficulty. See LiftedDensity for what has to move and what happens if it does
 * not, but briefly: noodle gates its noises on height and returns a carve-everything value outside that
 * gate, so used unshifted it does not give islands caves, it removes the islands.
 *
 * <p>How far up they move is chosen to sit the islands in the middle of the window noodle will work in,
 * which is a little over three hundred and eighty blocks wide against an island band of about two hundred
 * and seventy. Centring leaves the same margin at both ends, and margin is what there is to spend: too
 * low and the lowest islands fall out of the bottom of the window, too high and the tallest fall out of
 * the top, and either way the ones that fall out are deleted rather than left solid.
 */
public final class IslandCaves {
    /** Where noodle works, in its own coordinates. Everything else is shifted to agree with this. */
    private static final int NOODLE_BOTTOM = -60;
    private static final int NOODLE_TOP = 321;

    /** How far inside the bottom of that window to sit the islands, rather than exactly on the edge. */
    private static final int MARGIN = 4;

    private static final ResourceKey<DensityFunction> ENTRANCES = function("overworld/caves/entrances");
    private static final ResourceKey<DensityFunction> NOODLE = function("overworld/caves/noodle");
    private static final ResourceKey<DensityFunction> PILLARS = function("overworld/caves/pillars");
    private static final ResourceKey<DensityFunction> SPAGHETTI_2D = function("overworld/caves/spaghetti_2d");
    private static final ResourceKey<DensityFunction> SPAGHETTI_ROUGHNESS =
        function("overworld/caves/spaghetti_roughness_function");

    /**
     * The pieces of the last carve built, for the bench to sample. A cave mask is one number made of six,
     * and when it comes out wrong the only useful question is which of the six.
     */
    public static final java.util.Map<String, DensityFunction> PARTS = new java.util.LinkedHashMap<>();

    private IslandCaves() {
    }

    /**
     * How far the ground's caves have to move to land on the islands.
     *
     * <p>The window is noodle's, which is where it will do anything at all: outside it noodle returns a
     * carve-everything value, so an island that falls out of the window is not left solid, it is deleted.
     * Three hundred and eighty one blocks wide, against an island extent of about two hundred and seventy.
     *
     * <p>What is left over is a choice about which of the ground's altitudes the islands land on, and the
     * ground is not evenly caved: nearly all of it is below sea level, and the two hundred blocks above
     * that are mountainside with very little in them. Centring the islands in the window spends the margin
     * on nothing and lands them at ground Y minus three to two hundred and sixty three, which is to say
     * almost entirely in the empty part. Sitting them on the floor of the window instead puts the lowest
     * layer in the deep caves and the highest at mountain altitude, so the layers differ from each other:
     * low islands come out riddled, high ones nearly solid.
     *
     * <p>Asked of islandFloor and islandCeiling rather than worked out from the band, because how far an
     * island hangs below its layer is a property of the underside curve and moved when that curve did. The
     * pointed profile reaches most of two layer reaches down where the bowl reached one, and the difference
     * is thirty odd blocks of rock that would have been sitting below the window -- not left uncaved, but
     * carved away entirely.
     *
     * <p>Public because it is worth being able to check. If the band is ever configured taller than the
     * window the floor has to give way to the ceiling, since falling out of the top deletes islands just as
     * thoroughly as falling out of the bottom, and then the lowest layer loses its caves instead.
     */
    public static int offsetFor() {
        int islandBottom = SkyIslandDensity.islandFloor();
        int islandTop = SkyIslandDensity.islandCeiling();

        int offset = islandBottom - (NOODLE_BOTTOM + MARGIN);
        if (islandTop - offset >= NOODLE_TOP) {
            offset = islandTop - NOODLE_TOP + 1;
        }
        return offset;
    }


    /**
     * The cave mask: the density an island is allowed to have, given the caves running through it.
     *
     * <p>Combined with the rock by taking the lesser of the two, which is how the ground does it and is not
     * the same as subtracting. Subtracting was what this did first, and it needed a fade through the outer
     * skin to go with it, because a tunnel carved by subtraction has its wall where the carve cancels the
     * rock -- so the thinner the rock the wider the tunnel, and the widest mouth of any tunnel was the one
     * it opened at the surface. Under a minimum the wall sits where the mask crosses zero and nowhere else,
     * so a tunnel is the same width at an island's edge as in the middle of it, and the fade is not needed.
     *
     * @return null if the functions could not be found or lifted, in which case there are simply no caves
     */
    public static DensityFunction carve(
        HolderLookup.RegistryLookup<DensityFunction> functions,
        HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noiseLookup
    ) {
        if (functions == null || noiseLookup == null) {
            // Datagen, where the stem is written out and never generated from.
            return null;
        }
        int offset = offsetFor();

        // A round trip needs registry aware ops, and ops need a provider rather than the two lookups the
        // codec handed us. Two is all the cave functions can refer to: other density functions by name,
        // and noise parameters.
        HolderLookup.Provider registries =
            HolderLookup.Provider.create(java.util.stream.Stream.of(functions, noiseLookup));
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        DensityFunction entrances = lifted(registries, ops, ENTRANCES, offset);
        DensityFunction noodle = lifted(registries, ops, NOODLE, offset);
        DensityFunction pillars = lifted(registries, ops, PILLARS, offset);
        DensityFunction spaghetti = lifted(registries, ops, SPAGHETTI_2D, offset);
        DensityFunction roughness = lifted(registries, ops, SPAGHETTI_ROUGHNESS, offset);
        if (entrances == null || noodle == null || pillars == null || spaghetti == null || roughness == null) {
            return null;
        }

        // The big caverns, as the overworld builds them, less the term that gates them on depth. That term
        // reads the terrain density back into the mask, and underground it is what keeps a cavern from
        // opening at the surface: near the top the density is low, the term is at its half, and the cavern
        // closes. An island has no depth to speak of -- forty blocks against the four hundred the term was
        // written for -- so the gate would be shut throughout and there would be no caverns at all. Held
        // at the half it is a constant, which is the same thing without reading the rock, and reading the
        // rock is what would cost a second evaluation of the whole island.
        DensityFunction cheese = DensityFunctions.add(
            DensityFunctions.add(
                DensityFunctions.constant(0.27D),
                DensityFunctions.noise(noiseLookup.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666D)
            ).clamp(-1.0D, 1.0D),
            DensityFunctions.constant(0.5D)
        );
        DensityFunction layer = DensityFunctions.noise(noiseLookup.getOrThrow(Noises.CAVE_LAYER), 8.0D);
        DensityFunction caverns = DensityFunctions.add(
            DensityFunctions.mul(DensityFunctions.constant(4.0D), layer.square()), cheese
        );

        // Pillars only stand where they are already most of the way there; elsewhere they are taken out
        // of the running entirely rather than being allowed to fill a cave in a little.
        DensityFunction standing = DensityFunctions.rangeChoice(
            pillars, -1000000.0D, 0.03D, DensityFunctions.constant(-1000000.0D), pillars
        );

        DensityFunction mask = DensityFunctions.min(
            DensityFunctions.max(
                DensityFunctions.min(
                    DensityFunctions.min(caverns, entrances),
                    DensityFunctions.add(spaghetti, roughness)
                ),
                standing
            ),
            noodle
        );

        PARTS.clear();
        PARTS.put("entrances", entrances);
        PARTS.put("noodle", noodle);
        PARTS.put("pillars", pillars);
        PARTS.put("spaghetti+roughness", DensityFunctions.add(spaghetti, roughness));
        PARTS.put("caverns", caverns);
        PARTS.put("standing", standing);
        PARTS.put("mask", mask);

        BarrenSkies.LOG.info(
            "Lifted the overworld's caves {} blocks for the sky islands, which puts the island floor at "
                + "ground Y {} and the top of the band at ground Y {}.",
            offset, SkyIslandDensity.islandFloor() - offset, SkyIslandDensity.islandCeiling() - offset);
        return mask;
    }

    private static DensityFunction lifted(
        HolderLookup.Provider registries, DynamicOps<JsonElement> ops, ResourceKey<DensityFunction> key, int offset
    ) {
        return registries.lookup(Registries.DENSITY_FUNCTION)
            .flatMap(lookup -> lookup.get(key))
            .map(holder -> LiftedDensity.liftBy(ops, holder.value(), offset))
            .orElseGet(() -> {
                BarrenSkies.LOG.warn("No density function {}; island caves will be left out.", key.location());
                return null;
            });
    }

    private static ResourceKey<DensityFunction> function(String path) {
        return ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.withDefaultNamespace(path));
    }
}
