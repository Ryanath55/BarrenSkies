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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BarrenSkiesWorldgen {
    /**
     * Vanilla's terrain slide fades everything out between y=240 and y=256 regardless of world height,
     * so the extra room above 320 is empty and available for the island band.
     */
    public static final int WORLD_MIN_Y = -64;
    public static final int WORLD_HEIGHT = 512;

    public static final ResourceKey<DimensionType> DIMENSION_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, id("barren_skies"));
    public static final ResourceKey<NoiseGeneratorSettings> NOISE_SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, id("barren_skies"));
    public static final ResourceKey<WorldPreset> WORLD_PRESET = ResourceKey.create(Registries.WORLD_PRESET, id("barren_skies"));

    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES = DeferredRegister.create(
        Registries.BIOME_SOURCE, BarrenSkies.MOD_ID
    );

    static {
        BIOME_SOURCES.register("layered", () -> LayeredBiomeSource.CODEC);
    }

    public static void register(IEventBus modBus) {
        BIOME_SOURCES.register(modBus);
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
            new NoiseBasedChunkGenerator(
                new LayeredBiomeSource(biomes, parameterLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
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
