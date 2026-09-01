package com.barrenskies;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BarrenSkiesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SKY_ISLAND_BOTTOM;
    public static final ModConfigSpec.IntValue SKY_ISLAND_TOP;
    public static final ModConfigSpec.IntValue ISLAND_LAYERS;
    public static final ModConfigSpec.DoubleValue ISLAND_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ISLAND_SCALE;
    public static final ModConfigSpec.IntValue ISLAND_THICKNESS;
    public static final ModConfigSpec.BooleanValue ISLAND_CAVES;
    public static final ModConfigSpec.BooleanValue LANDFORM_NOISE;
    public static final ModConfigSpec.DoubleValue LANDFORM_STRENGTH;
    public static final ModConfigSpec.DoubleValue LANDFORM_SQUASH;
    public static final ModConfigSpec.BooleanValue ISLAND_WATERFALLS;
    public static final ModConfigSpec.IntValue WATERFALL_ISLAND_CHANCE;
    public static final ModConfigSpec.IntValue WATERFALLS_PER_ISLAND;
    public static final ModConfigSpec.BooleanValue WATERFALLS_ONLY_OVER_WATER;
    public static final ModConfigSpec.BooleanValue ISLAND_WATER;
    public static final ModConfigSpec.BooleanValue ALTITUDE_COOLING;
    public static final ModConfigSpec.BooleanValue LIFT_SURFACE_RULES;
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
                "  Y 360-560   : cool biomes turn snowy, warm ones are fine (default top is 560)",
                "  above Y 600 : everything freezes over, including jungles",
                "The band is squeezed from both sides: the ground reaches Y 320 and must be cleared, while",
                "temperature freezes everything above about Y 600. That, not world height, is what limits it."
            )
            .translation("barrenskies.configuration.skyIslandTop")
            .defineInRange("skyIslandTop", 560, 0, 950);


        ISLAND_LAYERS = b
            .comment(
                "How many overlapping layers of islands to stack between the floor and the ceiling.",
                "Layers are combined by taking whichever is denser, so islands from different layers overlap",
                "and merge rather than averaging their heights. More layers means a busier, more vertical sky.",
                "Islands reach islandThickness blocks either side of their layer, so layers closer together than",
                "that overlap and fuse into broader masses, while layers further apart stand separate."
            )
            .translation("barrenskies.configuration.islandLayers")
            .defineInRange("islandLayers", 4, 1, 8);

        ISLAND_THRESHOLD = b
            .comment(
                "How much of the island noise counts as land. Higher leaves more open sky.",
                "Around 0.30 gives large connected landmasses, 0.55 gives sparse scattered islands."
            )
            .translation("barrenskies.configuration.islandThreshold")
            .defineInRange("islandThreshold", 0.58D, 0.05D, 0.9D);

        ISLAND_SCALE = b
            .comment(
                "Horizontal scale of the island noise. Smaller values stretch it into larger landmasses,",
                "larger values break the sky into smaller, more numerous islands."
            )
            .translation("barrenskies.configuration.islandScale")
            .defineInRange("islandScale", 0.42D, 0.1D, 4.0D);

        ISLAND_THICKNESS = b
            .comment(
                "How far an island reaches above and below its layer, in blocks.",
                "This is the single biggest control over how round an island looks. A thick island has a deep",
                "bowl underneath it, and a deep bowl is a large curve, so it reads as round however level the",
                "top is. How far the deck sits above the layer depends on the ridge, and the bowl reaches",
                "almost all of this below it, so 42 gives islands from about 60 to 95 blocks thick.",
                "Raising this also spreads the layers further apart, since islands need the room."
            )
            .translation("barrenskies.configuration.islandThickness")
            .defineInRange("islandThickness", 42, 12, 96);

        LANDFORM_NOISE = b
            .comment(
                "Whether to add a three dimensional noise to the island shape.",
                "Real Minecraft terrain is not a height field. It is a depth gradient with a 3D noise added,",
                "and that noise is what gives ground its overhangs, ledges and broken edges instead of a",
                "smooth surface. Without this an island is a pure height map with only fine texture on top,",
                "which is most of why they used to read as too round and too smooth."
            )
            .translation("barrenskies.configuration.landformNoise")
            .define("landformNoise", true);

        LANDFORM_STRENGTH = b
            .comment(
                "How strongly the landform noise reshapes the islands, in density units.",
                "One density unit is worth a whole island thickness in blocks, so this times islandThickness",
                "is roughly how far it can move a surface. Past about 0.25 it stops adding texture and starts",
                "tearing islands into separate fragments."
            )
            .translation("barrenskies.configuration.landformStrength")
            .defineInRange("landformStrength", 0.15D, 0.0D, 0.6D);

        LANDFORM_SQUASH = b
            .comment(
                "How much slower the landform noise varies with height than it does horizontally.",
                "Below one it stretches the noise vertically, which is what turns round blobs into the",
                "horizontal ledges and shelves real terrain has. Minecraft's own 3D noise runs at 0.5."
            )
            .translation("barrenskies.configuration.landformSquash")
            .defineInRange("landformSquash", 0.55D, 0.1D, 2.0D);

        ISLAND_CAVES = b
            .comment(
                "Whether to carve caves through the sky islands.",
                "Cut where a three dimensional noise passes through zero, which traces a connected tunnel",
                "network rather than isolated pockets. Carvers cannot do this: the vanilla cave carver is",
                "limited to ground altitude and never reaches an island.",
                "On a thin island a tunnel can break through to open sky, leaving arches and windows."
            )
            .translation("barrenskies.configuration.islandCaves")
            .define("islandCaves", true);

        ISLAND_WATERFALLS = b
            .comment(
                "Whether springs form on island rims and pour over the edge.",
                "A basin is cut into the island top and filled with source water, with a notch through the",
                "rim for it to escape by. Source blocks never drain, so the fall lasts as long as the island.",
                "Where the ground below is sea or river the water lands in it, and a fall is something a",
                "player can swim up, which is what makes an island reachable before you can fly."
            )
            .translation("barrenskies.configuration.islandWaterfalls")
            .define("islandWaterfalls", true);

        WATERFALL_ISLAND_CHANCE = b
            .comment(
                "Percentage of islands that have any water on them at all.",
                "Decided per island rather than per chunk. Rolling for it in every chunk scattered streams",
                "evenly, which left every island with a few and none without any, and a waterfall you can",
                "count on finding is not worth flying to. At 30 most of the sky is dry."
            )
            .translation("barrenskies.configuration.waterfallIslandChance")
            .defineInRange("waterfallIslandChance", 30, 0, 100);

        WATERFALLS_PER_ISLAND = b
            .comment(
                "How many streams an island that has water may have.",
                "An upper limit rather than a count: each one still needs a rim that runs downhill and open",
                "sea below it, so an island will often have fewer."
            )
            .translation("barrenskies.configuration.waterfallsPerIsland")
            .defineInRange("waterfallsPerIsland", 2, 1, 8);

        WATERFALLS_ONLY_OVER_WATER = b
            .comment(
                "Only put a stream on a rim that has open sea or a river below it.",
                "A fall onto the barren surface is fresh water poured into a desert, and it also strands the",
                "water on ground it then spreads across. This is what makes them worth flying out to find,",
                "but it is also strict: an island has to overhang water, so most rims are left dry.",
                "Turn it off if your world has too few."
            )
            .translation("barrenskies.configuration.waterfallsOnlyOverWater")
            .define("waterfallsOnlyOverWater", true);

        ISLAND_WATER = b
            .comment(
                "Whether aquifers run, which is what puts ponds in island hollows and water in island caves.",
                "This was switched off for a while because water appeared in mid air around islands. That was",
                "a symptom of island density hovering at the solid threshold rather than of aquifers, and the",
                "fault has since been fixed. Skylands over the Sea leaves aquifers on and disables springs",
                "entirely, so all of its island water comes from here.",
                "Turning this off also removes underground water from the barren surface below."
            )
            .translation("barrenskies.configuration.islandWater")
            .define("islandWater", true);

        ALTITUDE_COOLING = b
            .comment(
                "Whether biomes get colder with height, as they do in vanilla Minecraft.",
                "Vanilla subtracts about 0.0125 of temperature per 10 blocks above Y 80, which at sky island",
                "altitude is enough to freeze every biome regardless of what it is. Turning this off keeps a",
                "jungle island a jungle no matter how high it sits, at the cost of no snow-capped peaks."
            )
            .translation("barrenskies.configuration.altitudeCooling")
            .define("altitudeCooling", false);





        LIFT_SURFACE_RULES = b
            .comment(
                "Whether to raise the height limits inside surface rules so they also work on sky islands.",
                "Surface rules are written against absolute heights: Terralith gates its yellowstone gravel",
                "on a check that is false above Y 115, so at island altitude that biome comes out as bare",
                "calcite. This shifts a copy of those heights up to the island band and applies it there,",
                "leaving the ground with the originals.",
                "Turn it off if a terrain mod surfaces oddly on islands and you would rather have the",
                "unaltered rules."
            )
            .translation("barrenskies.configuration.liftSurfaceRules")
            .define("liftSurfaceRules", true);

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
