package com.barrenskies.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Whether the structure being placed right now wants the ground or will take an island.
 *
 * <p>The generator is asked where the surface of a column is and is told nothing about who is asking, but
 * the right answer depends entirely on that. A shipwreck wants the sea floor; a village does not care what
 * it stands on. Handing everything the same number is what put shipwrecks in the sky: measured, of four
 * hundred columns asked, seventy three came back with an island rather than with ground.
 *
 * <p>So the decision is taken once, when a structure starts placing, on the thread doing it. Passing it
 * down properly would mean changing a signature that runs through the whole of worldgen; this reaches the
 * same place without asking anyone else to carry the parameter. It is a boolean rather than the structure
 * itself so that nothing here holds a registry or a holder open a moment longer than the call.
 *
 * <p>It is noted twice, because a structure is sited twice. Structure.generate picks the point, and that
 * was the only place this covered at first -- which was not enough, because a shipwreck throws that point
 * away. Its pieces work out their own height when they are built, from the finished world rather than
 * from the generator, and the finished world has islands in it. So StructureStart.placeInChunk notes the
 * structure again for the building phase.
 */
public final class StructureIntent {
    /** Unset on every thread that is not placing a structure, which is most of them. */
    private static final ThreadLocal<Boolean> GROUND = new ThreadLocal<>();

    private StructureIntent() {
    }

    public static void begin(Structure structure, RegistryAccess registries) {
        GROUND.set(decide(structure, registries));
    }

    public static void end() {
        GROUND.remove();
    }

    /**
     * Whether whatever is asking should be given the ground and never an island.
     *
     * <p>Nothing asking means something other than a structure -- choosing a world spawn, most often --
     * and those want the ground too, so that is the default and the value it goes back to.
     */
    public static boolean wantsGround() {
        Boolean ground = GROUND.get();
        return ground == null || ground;
    }

    /**
     * Whether a structure that must have the ground is being built right now, on this thread.
     *
     * <p>The same state read the other way round, and the difference is what nothing asking means. The
     * generator is asked how high a column is almost only by structures, so a thread with nothing noted
     * can be given the ground and no harm done. The world height a piece reads while it is being built is
     * asked by everything there is -- every feature, every tree, every block of gravel deciding whether
     * to fall -- and to those the absence of a structure has to mean leave the answer alone.
     */
    public static boolean placingOnGround() {
        return Boolean.TRUE.equals(GROUND.get());
    }

    /**
     * <p>A structure gets an island only if it is neither tagged as belonging to the ground nor, by its own
     * biome list, a thing that only generates in water. That second test is there so a modded shipwreck
     * nobody has tagged still behaves: a structure that will only ever generate in the sea is not one to
     * offer a mountain top in the sky.
     */
    private static boolean decide(Structure structure, RegistryAccess registries) {
        try {
            Holder<Structure> holder = registries.registryOrThrow(Registries.STRUCTURE).wrapAsHolder(structure);
            return holder.is(BarrenSkiesTags.GROUND_ONLY_STRUCTURES) || onlyInWater(structure);
        } catch (RuntimeException e) {
            // Unregistered, or a registry not available here. The ground is the safe answer: a structure
            // put on the ground that wanted an island is in the wrong place, and one put on an island that
            // wanted the sea floor is in the sky.
            return true;
        }
    }

    private static boolean onlyInWater(Structure structure) {
        boolean any = false;
        for (Holder<Biome> biome : structure.biomes()) {
            any = true;
            if (!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_DEEP_OCEAN) && !biome.is(BiomeTags.IS_RIVER)) {
                return false;
            }
        }
        return any;
    }
}
