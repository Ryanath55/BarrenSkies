package com.barrenskies.worldgen;

import com.barrenskies.BarrenSkies;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** Datapack escape hatches for biomes the climate rules classify wrongly. */
public final class BarrenSkiesTags {
    public static final TagKey<Biome> ALLOWED_ON_SURFACE = biomeTag("allowed_on_surface");
    public static final TagKey<Biome> DENIED_ON_SURFACE = biomeTag("denied_on_surface");
    /**
     * Biomes that may generate wherever the base worldgen placed them, but are never used to replace a
     * different biome. Use this for a biome that is fine in its own right but too distinctive to spread.
     */
    public static final TagKey<Biome> NEVER_PAINTED = biomeTag("never_painted");

    /**
     * Biomes kept off the sky islands.
     *
     * <p>Separate from the surface deny list, and not its opposite: a biome can be wrong on the barren
     * ground and wrong on an island for entirely different reasons.
     */
    public static final TagKey<Biome> DENIED_IN_SKY = biomeTag("denied_in_sky");

    /**
     * Structures that must be given the ground and never an island.
     *
     * <p>Ocean and underground ones: a shipwreck wants the sea floor, a mineshaft wants rock. Anything not
     * in here may stand on an island if the column it lands on has one, which is what a village or an
     * outpost should be allowed to do. A structure whose own biome list is entirely water counts as being
     * in here whether or not anyone listed it, so a modded shipwreck behaves without being named.
     */
    public static final TagKey<net.minecraft.world.level.levelgen.structure.Structure> GROUND_ONLY_STRUCTURES =
        TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, "ground_only"));

    private static TagKey<Biome> biomeTag(String name) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, name));
    }

    private BarrenSkiesTags() {
    }
}
