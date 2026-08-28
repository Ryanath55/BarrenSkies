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

    private static TagKey<Biome> biomeTag(String name) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, name));
    }

    private BarrenSkiesTags() {
    }
}
