package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkies;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BarrenSkiesFeatures {
    private static final DeferredRegister<Feature<?>> FEATURES =
        DeferredRegister.create(Registries.FEATURE, BarrenSkies.MOD_ID);

    public static final DeferredHolder<Feature<?>, IslandStreamFeature> ISLAND_STREAM =
        FEATURES.register("island_stream", () -> new IslandStreamFeature(NoneFeatureConfiguration.CODEC));

    public static final ResourceKey<ConfiguredFeature<?, ?>> ISLAND_STREAM_CONFIGURED =
        ResourceKey.create(Registries.CONFIGURED_FEATURE, id("island_stream"));

    public static final ResourceKey<PlacedFeature> ISLAND_STREAM_PLACED =
        ResourceKey.create(Registries.PLACED_FEATURE, id("island_stream"));

    public static final DeferredHolder<Feature<?>, IslandOreFeature> ISLAND_ORES =
        FEATURES.register("island_ores", () -> new IslandOreFeature(NoneFeatureConfiguration.CODEC));

    public static final ResourceKey<ConfiguredFeature<?, ?>> ISLAND_ORES_CONFIGURED =
        ResourceKey.create(Registries.CONFIGURED_FEATURE, id("island_ores"));

    public static final ResourceKey<PlacedFeature> ISLAND_ORES_PLACED =
        ResourceKey.create(Registries.PLACED_FEATURE, id("island_ores"));

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    public static void bootstrapConfiguredFeatures(net.minecraft.data.worldgen.BootstrapContext<ConfiguredFeature<?, ?>> context) {
        context.register(
            ISLAND_STREAM_CONFIGURED,
            new ConfiguredFeature<>(ISLAND_STREAM.get(), NoneFeatureConfiguration.INSTANCE)
        );
        context.register(
            ISLAND_ORES_CONFIGURED,
            new ConfiguredFeature<>(ISLAND_ORES.get(), NoneFeatureConfiguration.INSTANCE)
        );
    }

    /**
     * One placement per chunk each, and no modifiers at all.
     *
     * <p>The stream walks its own sixteen by sixteen itself, so it wants the chunk rather than a spot in
     * it. A count would run it several times over the same columns, a height would be ignored, and a biome
     * filter would test one column on behalf of two hundred and fifty six. It does all of that per column,
     * where the answers actually differ.
     *
     * <p>The ore pass wants the chunk for a different reason: it is not a vein but a run through every ore
     * the biome has, and each of those brings its own count, its own height and its own biome filter when
     * it is run. A modifier here would apply to the pass rather than to the veins, which is a level too
     * high to mean anything.
     */
    public static void bootstrapPlacedFeatures(net.minecraft.data.worldgen.BootstrapContext<PlacedFeature> context) {
        var configured = context.lookup(Registries.CONFIGURED_FEATURE);
        context.register(
            ISLAND_STREAM_PLACED,
            new PlacedFeature(configured.getOrThrow(ISLAND_STREAM_CONFIGURED), java.util.List.of())
        );
        context.register(
            ISLAND_ORES_PLACED,
            new PlacedFeature(configured.getOrThrow(ISLAND_ORES_CONFIGURED), java.util.List.of())
        );
    }

    /**
     * Added to every overworld biome rather than to the sky pool alone. A biome is only in one pool or the
     * other, but that depends on config, and both features already refuse to run below the island floor.
     *
     * <p>The ore pass goes in the ore step, after the biome's own ore rather than in a step of its own, so
     * that anything reading the world between steps sees ore arrive when ore is supposed to arrive.
     */
    public static void bootstrapBiomeModifiers(
        net.minecraft.data.worldgen.BootstrapContext<net.neoforged.neoforge.common.world.BiomeModifier> context
    ) {
        var overworld = context.lookup(Registries.BIOME).getOrThrow(net.minecraft.tags.BiomeTags.IS_OVERWORLD);
        var placed = context.lookup(Registries.PLACED_FEATURE);
        context.register(
            ResourceKey.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.BIOME_MODIFIERS, id("island_streams")),
            new net.neoforged.neoforge.common.world.BiomeModifiers.AddFeaturesBiomeModifier(
                overworld,
                net.minecraft.core.HolderSet.direct(placed.getOrThrow(ISLAND_STREAM_PLACED)),
                net.minecraft.world.level.levelgen.GenerationStep.Decoration.LAKES
            )
        );
        context.register(
            ResourceKey.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.BIOME_MODIFIERS, id("island_ores")),
            new net.neoforged.neoforge.common.world.BiomeModifiers.AddFeaturesBiomeModifier(
                overworld,
                net.minecraft.core.HolderSet.direct(placed.getOrThrow(ISLAND_ORES_PLACED)),
                net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES
            )
        );
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, path);
    }

    private BarrenSkiesFeatures() {
    }
}
