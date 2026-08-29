package com.barrenskies;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BarrenSkiesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SKY_ISLAND_BOTTOM;
    public static final ModConfigSpec.IntValue SKY_ISLAND_TOP;
    public static final ModConfigSpec.DoubleValue ISLAND_DENSITY;
    public static final ModConfigSpec.IntValue ISLAND_RADIUS_MIN;
    public static final ModConfigSpec.IntValue ISLAND_RADIUS_MAX;
    public static final ModConfigSpec.IntValue ISLAND_SPACING;
    public static final ModConfigSpec.DoubleValue WORLD_TERRAIN_INFLUENCE;
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
                "Ground terrain reaches Y 320, so keep this above 360 or island undersides will punch through peaks.")
            .translation("barrenskies.configuration.skyIslandBottom")
            .defineInRange("skyIslandBottom", 380, 0, 950);

        SKY_ISLAND_TOP = b
            .comment(
                "Highest Y an island may sit at. Islands are spread evenly between the floor and this, so a wide",
                "gap gives genuinely layered altitudes and a narrow one puts them all on one plane.",
                "Minecraft lowers biome temperature with height, by about 0.0125 per 10 blocks above Y 80.",
                "That is not something this mod controls, and it means altitude decides how frozen an island",
                "looks far more than its biome does:",
                "  up to Y 360 : nearly every biome looks as it should",
                "  Y 360-560   : cool biomes turn snowy, warm ones are fine (default top is 520)",
                "  above Y 600 : everything freezes over, including jungles",
                "The band is squeezed from both sides: the ground reaches Y 320 and must be cleared, while",
                "temperature freezes everything above about Y 600. That, not world height, is what limits it."
            )
            .translation("barrenskies.configuration.skyIslandTop")
            .defineInRange("skyIslandTop", 520, 0, 950);


        ISLAND_DENSITY = b
            .comment(
                "Fraction of grid cells that hold an island, before size is taken into account.",
                "This is a multiplier on a 0.55 base, so 1.0 fills about 55 percent of cells.",
                "Combine with islandSpacing and the radii for fine control over how much open sky there is."
            )
            .translation("barrenskies.configuration.islandDensity")
            .defineInRange("islandDensity", 1.0D, 0.05D, 3.0D);

        ISLAND_RADIUS_MIN = b
            .comment("Smallest island radius in blocks. An island is roughly twice this across at its narrowest.")
            .translation("barrenskies.configuration.islandRadiusMin")
            .defineInRange("islandRadiusMin", 95, 20, 600);



        ISLAND_RADIUS_MAX = b
            .comment("Largest island radius in blocks. Values below the minimum are treated as equal to it.")
            .translation("barrenskies.configuration.islandRadiusMax")
            .defineInRange("islandRadiusMax", 180, 20, 600);



        ISLAND_SPACING = b
            .comment(
                "Grid pitch between island centres, in blocks. Lower packs islands closer together.",
                "Sky coverage is roughly density * pi * meanRadius^2 / spacing^2, so if you change the radii",
                "and want the same amount of open sky, scale this with them."
            )
            .translation("barrenskies.configuration.islandSpacing")
            .defineInRange("islandSpacing", 300, 60, 4000);



        WORLD_TERRAIN_INFLUENCE = b
            .comment(
                "How strongly the world own terrain noise shapes the islands.",
                "Islands read density from the same noise router that builds the ground, sampled from a distant",
                "place at normal ground height and lifted into the sky, so with a terrain mod installed they",
                "inherit its character rather than looking like noise of our own.",
                "This costs generation time, and a heavy terrain mod costs more. Set to 0 to skip it and fall",
                "back to the built-in island shaping, which generates noticeably faster."
            )
            .translation("barrenskies.configuration.worldTerrainInfluence")
            .defineInRange("worldTerrainInfluence", 1.0D, 0.0D, 2.0D);





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
