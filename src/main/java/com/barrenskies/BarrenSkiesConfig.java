package com.barrenskies;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BarrenSkiesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SKY_ISLAND_BOTTOM;
    public static final ModConfigSpec.DoubleValue ISLAND_DENSITY;
    public static final ModConfigSpec.DoubleValue OCEAN_CONTINENTALNESS_MAX;
    public static final ModConfigSpec.DoubleValue ARID_TEMPERATURE_MIN;
    public static final ModConfigSpec.BooleanValue ARID_REQUIRES_NO_RAIN;
    public static final ModConfigSpec.BooleanValue INCLUDE_MODDED_BIOMES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment(
                "Barren Skies world type settings.",
                "These are read when a world loads, so a world must be reloaded for changes to apply.",
                "Changing them after chunks have generated will produce visible seams at the border",
                "between old and new chunks."
            )
            .push("layers");

        SKY_ISLAND_BOTTOM = b
            .comment("Lowest Y level that counts as the sky island layer. Everything below this uses the barren surface / cave biome pool.",
                "Terrain generates up to Y 320, so keep this above 340 or island undersides will clip tall peaks.")
            .translation("barrenskies.configuration.skyIslandBottom")
            .defineInRange("skyIslandBottom", 352, 0, 447);

        ISLAND_DENSITY = b
            .comment(
                "How much of the sky is filled with island clusters.",
                "1.0 is the default archipelago spacing: clusters of 3-6 islands with long empty crossings between them.",
                "Raise toward 2.0 for a crowded sky, lower toward 0.2 for rare, hard-won landmasses."
            )
            .translation("barrenskies.configuration.islandDensity")
            .defineInRange("islandDensity", 1.0D, 0.05D, 3.0D);

        OCEAN_CONTINENTALNESS_MAX = b
            .comment(
                "A biome is treated as ocean-like when the middle of its continentalness range is at or below this value.",
                "Vanilla only has a few wide bands, so this behaves as a series of steps rather than a smooth dial.",
                "  -0.20 or below : oceans only (default)",
                "  -0.15 to -0.11 : also keeps beaches and coastal biomes on the barren surface",
                "   0.00 or above : nearly everything becomes ocean"
            )
            .translation("barrenskies.configuration.oceanContinentalnessMax")
            .defineInRange("oceanContinentalnessMax", -0.19D, -2.0D, 2.0D);

        ARID_TEMPERATURE_MIN = b
            .comment(
                "A biome is treated as arid when the middle of its temperature range is at or above this value.",
                "Vanilla only has five temperature bands, so this behaves as a series of steps rather than a smooth dial.",
                "  above 0.56 : desert and badlands only (default 0.55; raising it changes nothing until 0.78)",
                "   0.20-0.55 : also pulls in savanna and jungle temperatures",
                "  below 0.02 : also pulls in temperate biomes such as plains and forest",
                "Raising this past every biome falls back to the hottest band rather than disabling the mod."
            )
            .translation("barrenskies.configuration.aridTemperatureMin")
            .defineInRange("aridTemperatureMin", 0.55D, -2.0D, 2.0D);

        ARID_REQUIRES_NO_RAIN = b
            .comment(
                "Also require that a hot biome has no precipitation before treating it as arid.",
                "This is what keeps mangrove swamps and similar hot-but-wet biomes off the barren surface."
            )
            .translation("barrenskies.configuration.aridRequiresNoRain")
            .define("aridRequiresNoRain", true);

        INCLUDE_MODDED_BIOMES = b
            .comment("Pull biomes contributed by other worldgen mods (anything using TerraBlender, such as Biomes O' Plenty) into the layers.")
            .translation("barrenskies.configuration.includeModdedBiomes")
            .define("includeModdedBiomes", true);

        b.pop();

        SPEC = b.build();
    }

    private BarrenSkiesConfig() {
    }
}
