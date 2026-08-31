package com.barrenskies.worldgen;

import com.barrenskies.BarrenSkies;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import com.barrenskies.worldgen.sky.SkyIslandChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BarrenSkiesWorldgen {
    /**
     * Terrain from the overworld noise settings stops at Y 320, so everything above that is empty and
     * available for the island band. The world is deliberately far taller than vanilla to give the islands
     * room to sit on genuinely different levels. This cannot be a config value: it lives in the dimension
     * type datapack entry, which is baked at build time.
     */
    public static final int WORLD_MIN_Y = -64;
    public static final int WORLD_HEIGHT = 1024;

    public static final ResourceKey<DimensionType> DIMENSION_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, id("barren_skies"));
    public static final ResourceKey<NoiseGeneratorSettings> NOISE_SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, id("barren_skies"));
    public static final ResourceKey<WorldPreset> WORLD_PRESET = ResourceKey.create(Registries.WORLD_PRESET, id("barren_skies"));

    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES = DeferredRegister.create(
        Registries.BIOME_SOURCE, BarrenSkies.MOD_ID
    );

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS = DeferredRegister.create(
        Registries.CHUNK_GENERATOR, BarrenSkies.MOD_ID
    );

    static {
        BIOME_SOURCES.register("layered", () -> LayeredBiomeSource.CODEC);
        CHUNK_GENERATORS.register("sky_islands", () -> SkyIslandChunkGenerator.CODEC);
    }

    public static void register(IEventBus modBus) {
        BIOME_SOURCES.register(modBus);
        CHUNK_GENERATORS.register(modBus);
    }

    public static void bootstrapDimensionTypes(BootstrapContext<DimensionType> context) {
        context.register(
            DIMENSION_TYPE,
            new DimensionType(
                OptionalLong.empty(),
                true,
                false,
                false,
                true,
                1.0,
                true,
                false,
                WORLD_MIN_Y,
                WORLD_HEIGHT,
                WORLD_HEIGHT,
                BlockTags.INFINIBURN_OVERWORLD,
                BuiltinDimensionTypes.OVERWORLD_EFFECTS,
                0.0F,
                new DimensionType.MonsterSettings(false, true, UniformInt.of(0, 7), 0)
            )
        );
    }

    /**
     * Noise for the island shape and for varying its surface.
     *
     * <p>The island parameters are taken from Klinbee's Skylands over the Sea (MIT). The negative amplitude
     * in the middle of the series is what stops the islands reading as round blobs.
     */
    public static void bootstrapNoises(BootstrapContext<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> context) {
        context.register(
            com.barrenskies.worldgen.sky.SkyIslandDensity.ISLANDS,
            new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-8, 1.0D, 1.0D, 2.0D, 0.0D, -2.0D, 1.0D, 0.0D)
        );
        context.register(
            com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_RIDGES,
            new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-7, 1.0D, 2.0D, 1.0D, 0.0D, 0.0D, 0.0D)
        );
        // The vanilla-style landform term. Squashed vertically where it is used, which is what makes it
        // cut ledges and shelves rather than scatter round lumps.
        context.register(
            com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_LANDFORM,
            new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-6, 1.0D, 0.5D, 0.25D)
        );
        // Short wavelength, around sixteen blocks, so it reads as the small rises across a field rather than
        // as broad swells. A coarser noise here just tilts whole hillsides.
        context.register(
            com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_DETAIL,
            new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-5, 1.0D, 0.6D, 0.3D)
        );
        // Cave tunnels are cut where this passes through zero, so its wavelength sets both the tunnel
        // spacing and, less obviously, the tunnel width. The carving spline dips over a fixed band of noise
        // values, and how many blocks the noise takes to cross that band is what the tunnel diameter
        // actually is. A short wavelength crosses it in a block or two, which is narrower than a noise cell,
        // and terrain narrower than a cell cannot survive the interpolation between cell corners: it comes
        // out as flat, axis-aligned faces. These are the parameters of minecraft:gravel, which is the noise
        // Skylands over the Sea uses for the same carve, and at 256 and 128 blocks it crosses the band over
        // several blocks instead. The proportion of rock removed is unchanged; only the scale of it moves.
        context.register(
            com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_CAVES,
            new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-8, 1.0D, 1.0D)
        );
    }

    public static void bootstrapNoiseSettings(BootstrapContext<NoiseGeneratorSettings> context) {
        NoiseGeneratorSettings overworld = NoiseGeneratorSettings.overworld(context, false, false);
        context.register(
            NOISE_SETTINGS,
            new NoiseGeneratorSettings(
                NoiseSettings.create(WORLD_MIN_Y, WORLD_HEIGHT, 1, 2),
                overworld.defaultBlock(),
                overworld.defaultFluid(),
                overworld.noiseRouter(),
                overworld.surfaceRule(),
                overworld.spawnTarget(),
                overworld.seaLevel(),
                overworld.disableMobGeneration(),
                overworld.aquifersEnabled(),
                overworld.oreVeinsEnabled(),
                overworld.useLegacyRandomSource()
            )
        );
    }

    public static void bootstrapWorldPresets(BootstrapContext<WorldPreset> context) {
        HolderGetter<DimensionType> dimensionTypes = context.lookup(Registries.DIMENSION_TYPE);
        HolderGetter<NoiseGeneratorSettings> noiseSettings = context.lookup(Registries.NOISE_SETTINGS);
        HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);
        HolderGetter<MultiNoiseBiomeSourceParameterList> parameterLists = context.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

        LevelStem overworld = new LevelStem(
            dimensionTypes.getOrThrow(DIMENSION_TYPE),
            // Use the overworld's own noise settings rather than a copy. Copying baked vanilla's terrain
            // chain in while the climate axes stayed as references, so a terrain mod that replaces the
            // referenced density functions left the ground disagreeing with the biome chosen for it.
            new SkyIslandChunkGenerator(
                new LayeredBiomeSource(biomes, parameterLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD),
                context.lookup(Registries.NOISE).getOrThrow(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLANDS),
                context.lookup(Registries.NOISE).getOrThrow(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_RIDGES),
                context.lookup(Registries.NOISE).getOrThrow(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_DETAIL),
                context.lookup(Registries.NOISE).getOrThrow(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_CAVES),
                context.lookup(Registries.NOISE).getOrThrow(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_LANDFORM)
            )
        );
        LevelStem nether = new LevelStem(
            dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER),
            new NoiseBasedChunkGenerator(
                MultiNoiseBiomeSource.createFromPreset(parameterLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
                noiseSettings.getOrThrow(NoiseGeneratorSettings.NETHER)
            )
        );
        LevelStem end = new LevelStem(
            dimensionTypes.getOrThrow(BuiltinDimensionTypes.END),
            new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes), noiseSettings.getOrThrow(NoiseGeneratorSettings.END))
        );

        context.register(WORLD_PRESET, new WorldPreset(Map.of(LevelStem.OVERWORLD, overworld, LevelStem.NETHER, nether, LevelStem.END, end)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, path);
    }

    private BarrenSkiesWorldgen() {
    }
}
