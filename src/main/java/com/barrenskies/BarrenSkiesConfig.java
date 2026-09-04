package com.barrenskies;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Everything the world type can be tuned by, in five sections.
 *
 * <p>Comments here are tooltips before they are documentation. The config screen shows each one in a box
 * beside the option, and a box taller than the screen is a box nobody reads, so these are held to a few
 * short lines: what the setting does, and what the numbers mean. Why any of it works the way it does is
 * written where the work happens -- SkyIslandDensity for the shape, IslandCaves for the caves, MirroredOres
 * for the ore, IslandLight for the light -- which is also where it stays true when the code changes.
 */
public final class BarrenSkiesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SKY_ISLAND_BOTTOM;
    public static final ModConfigSpec.IntValue SKY_ISLAND_TOP;
    public static final ModConfigSpec.IntValue ISLAND_LAYERS;
    public static final ModConfigSpec.DoubleValue ISLAND_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ISLAND_SCALE;
    public static final ModConfigSpec.IntValue ISLAND_THICKNESS;

    public static final ModConfigSpec.BooleanValue LANDFORM_NOISE;
    public static final ModConfigSpec.DoubleValue LANDFORM_STRENGTH;
    public static final ModConfigSpec.DoubleValue LANDFORM_SQUASH;
    public static final ModConfigSpec.BooleanValue ISLAND_CAVES;

    public static final ModConfigSpec.IntValue ISLAND_ORE_RARITY;
    public static final ModConfigSpec.BooleanValue ISLAND_SHADOW;

    public static final ModConfigSpec.BooleanValue ISLAND_WATERFALLS;
    public static final ModConfigSpec.IntValue WATERFALL_ISLAND_CHANCE;
    public static final ModConfigSpec.IntValue STREAM_DEPTH;
    public static final ModConfigSpec.BooleanValue WATERFALLS_ONLY_OVER_WATER;
    public static final ModConfigSpec.BooleanValue ISLAND_WATER;

    public static final ModConfigSpec.BooleanValue ALTITUDE_COOLING;
    public static final ModConfigSpec.BooleanValue LIFT_SURFACE_RULES;
    public static final ModConfigSpec.DoubleValue OCEAN_CONTINENTALNESS_MAX;
    public static final ModConfigSpec.DoubleValue ARID_TEMPERATURE_MIN;
    public static final ModConfigSpec.BooleanValue ARID_REQUIRES_NO_RAIN;
    public static final ModConfigSpec.BooleanValue INCLUDE_MODDED_BIOMES;
    public static final ModConfigSpec.BooleanValue RECONCILE_MODDED_PLACEMENT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment(
                "Where the islands sit and how big they are.",
                "Read when a world loads, so reload the world for a change to apply. Changing these after",
                "chunks exist leaves a visible seam between the old chunks and the new."
            )
            .push("islands");

        SKY_ISLAND_BOTTOM = b
            .comment(
                "Lowest Y counted as sky. Below this a column takes the barren surface and cave biomes.",
                "Islands hang well under this: rock fades out two islandThickness below a layer, so at the",
                "defaults the lowest rock is Y 256 against a barren surface topping out at Y 251.",
                "Lowering this, or raising islandThickness, spends those five blocks of clearance."
            )
            .translation("barrenskies.configuration.skyIslandBottom")
            .defineInRange("skyIslandBottom", 340, 0, 600);

        SKY_ISLAND_TOP = b
            .comment(
                "Highest Y a layer may sit at. Layers spread evenly between the floor and this.",
                "Altitude decides how frozen an island looks far more than its biome does, because vanilla",
                "cools biomes about 0.0125 per 10 blocks above Y 80:",
                "  to Y 360 : every biome looks as it should",
                "  360-560  : cool biomes turn snowy, warm ones are fine",
                "  above 600: everything freezes, jungles included"
            )
            .translation("barrenskies.configuration.skyIslandTop")
            .defineInRange("skyIslandTop", 480, 0, 600);

        ISLAND_LAYERS = b
            .comment(
                "How many overlapping layers of islands to stack between the floor and the ceiling.",
                "Layers are combined by taking whichever is denser, so islands merge rather than average.",
                "Layers closer together than islandThickness fuse into broader masses; further apart, they",
                "stand separate. More layers means a busier, more vertical sky."
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
                "Horizontal scale of the island noise.",
                "Smaller stretches it into larger landmasses, larger breaks the sky into smaller islands."
            )
            .translation("barrenskies.configuration.islandScale")
            .defineInRange("islandScale", 0.42D, 0.1D, 4.0D);

        ISLAND_THICKNESS = b
            .comment(
                "How far an island reaches above and below its layer, in blocks.",
                "The biggest single control over how round an island looks, since a thick island has a deep",
                "underside and a deep curve reads as round however level the top is.",
                "42 gives islands about 60 to 95 blocks thick. Raising it also spreads the layers apart."
            )
            .translation("barrenskies.configuration.islandThickness")
            .defineInRange("islandThickness", 42, 12, 96);

        b.pop();

        b.comment("How island rock is textured, broken up and hollowed out.")
            .push("shape");

        LANDFORM_NOISE = b
            .comment(
                "Whether to add a three dimensional noise to the island shape.",
                "Real terrain is not a height field; it is a gradient with a 3D noise added, and that noise",
                "is what gives ground overhangs, ledges and broken edges. Without this an island is a pure",
                "height map with fine texture on top, which is most of what made them read as too round."
            )
            .translation("barrenskies.configuration.landformNoise")
            .define("landformNoise", true);

        LANDFORM_STRENGTH = b
            .comment(
                "How strongly the landform noise reshapes the islands, in density units.",
                "One density unit is worth a whole islandThickness in blocks, so this times islandThickness",
                "is roughly how far it can move a surface.",
                "Past about 0.25 it stops adding texture and starts tearing islands into fragments."
            )
            .translation("barrenskies.configuration.landformStrength")
            .defineInRange("landformStrength", 0.15D, 0.0D, 0.6D);

        LANDFORM_SQUASH = b
            .comment(
                "How much slower the landform noise varies with height than horizontally.",
                "Below one stretches it vertically, turning round blobs into the horizontal ledges and",
                "shelves real terrain has. Minecraft's own 3D noise runs at 0.5."
            )
            .translation("barrenskies.configuration.landformSquash")
            .defineInRange("landformSquash", 0.55D, 0.1D, 2.0D);

        ISLAND_CAVES = b
            .comment(
                "Whether to carve caves through the sky islands.",
                "These are Minecraft's own caves lifted to island altitude: entrances, spaghetti, noodles,",
                "pillars and the cheese noise behind the big caverns. Carvers cannot reach an island, so it",
                "is done in the density instead. On a thin island a tunnel can break through to open sky."
            )
            .translation("barrenskies.configuration.islandCaves")
            .define("islandCaves", true);

        b.pop();

        b.comment("What the islands are worth to dig, and what they do to the light below.")
            .push("resources");

        ISLAND_ORE_RARITY = b
            .comment(
                "How common island ore is, as a percentage of how common ore is underground.",
                "0 turns it off. 50 is half as common, 100 matches the ground, 200 is double.",
                "The underground range is turned upside down and squeezed into the island band, so what is",
                "deepest below is highest above: diamond and redstone up top, coal and copper at the bottom.",
                "Each biome's own ore list is reused, so ores added by other mods are mirrored too.",
                "Above 0 the ground's own ore also stops at the island floor, so no island is served twice."
            )
            .translation("barrenskies.configuration.islandOreRarity")
            .defineInRange("islandOreRarity", 100, 0, 200);

        ISLAND_SHADOW = b
            .comment(
                "Whether the islands darken the ground far below them.",
                "Sky light is a flood fill, so an island puts the whole column under it in the dark all the",
                "way to bedrock -- measured at 0 from the underside past the sea floor, against 15 beside it.",
                "That decides mob spawning, crop growth and snow exactly as a cave would.",
                "Off runs sky light as two passes split at the island floor: the ground is lit as though the",
                "sky were clear, while islands still shade their own hollows and caves. Both are real light.",
                "INCOMPATIBLE with light engine replacements, Starlight above all. Sodium and Iris are fine."
            )
            .translation("barrenskies.configuration.islandShadow")
            .define("islandShadow", false);

        b.pop();

        b.comment("Streams, waterfalls and where water is allowed to sit.")
            .push("water");

        ISLAND_WATERFALLS = b
            .comment(
                "Whether springs form on island rims and pour over the edge.",
                "A basin is cut into the island top and filled with source water, with a notch through the",
                "rim to escape by. Source blocks never drain, so the fall lasts as long as the island does,",
                "and a fall is something a player can swim up before they can fly."
            )
            .translation("barrenskies.configuration.islandWaterfalls")
            .define("islandWaterfalls", true);

        WATERFALL_ISLAND_CHANCE = b
            .comment(
                "Percentage of islands that have any water on them at all.",
                "Decided per island rather than per chunk, so some islands are wet and others are dry.",
                "Rolling per chunk instead gave every island a few streams and none without any, and a",
                "waterfall you can count on finding is not worth flying to. At 30 most of the sky is dry."
            )
            .translation("barrenskies.configuration.waterfallIslandChance")
            .defineInRange("waterfallIslandChance", 70, 0, 100);

        STREAM_DEPTH = b
            .comment(
                "How deep a stream channel is cut into an island top, in blocks.",
                "Deepest along the middle and shallower at the banks, so this is the deepest it gets.",
                "Four cuts past the soil into rock in most places, which reads as a gorge, not a ditch."
            )
            .translation("barrenskies.configuration.streamDepth")
            .defineInRange("streamDepth", 4, 2, 12);

        WATERFALLS_ONLY_OVER_WATER = b
            .comment(
                "Only put a stream on a rim with open sea or river below it.",
                "A fall onto the barren surface is fresh water poured into a desert, and it strands water on",
                "ground it then spreads across. Strict, though: an island has to overhang water, so most",
                "rims are left dry. Turn it off if your world has too few."
            )
            .translation("barrenskies.configuration.waterfallsOnlyOverWater")
            .define("waterfallsOnlyOverWater", false);

        ISLAND_WATER = b
            .comment(
                "Whether aquifers run, which is what puts ponds in island hollows and water in island caves.",
                "Turning this off also removes underground water from the barren surface below."
            )
            .translation("barrenskies.configuration.islandWater")
            .define("islandWater", true);

        b.pop();

        b.comment("Which biomes land where, and how they behave at altitude.")
            .push("biomes");

        ALTITUDE_COOLING = b
            .comment(
                "Whether biomes get colder with height, as they do in vanilla.",
                "Vanilla subtracts about 0.0125 of temperature per 10 blocks above Y 80, which at island",
                "altitude is enough to freeze every biome whatever it is. Off keeps a jungle island a",
                "jungle however high it sits, at the cost of no snow-capped peaks."
            )
            .translation("barrenskies.configuration.altitudeCooling")
            .define("altitudeCooling", false);

        LIFT_SURFACE_RULES = b
            .comment(
                "Whether to raise the height limits inside surface rules so they work on islands too.",
                "Surface rules are written against absolute heights: Terralith gates its yellowstone gravel",
                "on a check that is false above Y 115, so at island altitude that biome comes out bare.",
                "This applies a lifted copy to the islands and leaves the ground with the originals."
            )
            .translation("barrenskies.configuration.liftSurfaceRules")
            .define("liftSurfaceRules", true);

        OCEAN_CONTINENTALNESS_MAX = b
            .comment(
                "A biome counts as ocean when the middle of its continentalness range is at or below this.",
                "Vanilla has only a few wide bands, so this steps rather than sliding:",
                "  -0.20 or below : oceans only",
                "  -0.15 to -0.11 : also keeps beaches and coastal biomes",
                "   0.00 or above : nearly everything becomes ocean"
            )
            .translation("barrenskies.configuration.oceanContinentalnessMax")
            .defineInRange("oceanContinentalnessMax", -0.19D, -2.0D, 2.0D);

        ARID_TEMPERATURE_MIN = b
            .comment(
                "A biome counts as arid when the middle of its temperature range is at or above this.",
                "Vanilla has only five temperature bands, so this steps rather than sliding:",
                "  above 0.56 : desert and badlands only",
                "   0.20-0.55 : also savanna and jungle temperatures",
                "  below 0.02 : also temperate biomes such as plains and forest",
                "Raising it past every biome falls back to the hottest band rather than disabling the mod."
            )
            .translation("barrenskies.configuration.aridTemperatureMin")
            .defineInRange("aridTemperatureMin", 0.55D, -2.0D, 2.0D);

        ARID_REQUIRES_NO_RAIN = b
            .comment(
                "Also require that a hot biome has no rain before treating it as arid.",
                "This is what keeps mangrove swamps and other hot-but-wet biomes off the barren surface."
            )
            .translation("barrenskies.configuration.aridRequiresNoRain")
            .define("aridRequiresNoRain", true);

        INCLUDE_MODDED_BIOMES = b
            .comment(
                "Pull in biomes contributed by other worldgen mods.",
                "Anything using TerraBlender, such as Biomes O' Plenty."
            )
            .translation("barrenskies.configuration.includeModdedBiomes")
            .define("includeModdedBiomes", true);

        RECONCILE_MODDED_PLACEMENT = b
            .comment(
                "Hold biomes placed by other mods to the same layer rules as everything else.",
                "Blueprint, which places Team Abnormals' biomes, and mods like it answer the biome lookup",
                "before this mod sees it, so without this their biomes ignore the barren surface entirely.",
                "Turn it off to let them place wherever they like."
            )
            .translation("barrenskies.configuration.reconcileModdedPlacement")
            .define("reconcileModdedPlacement", true);

        b.pop();

        SPEC = b.build();
    }

    private BarrenSkiesConfig() {
    }
}
