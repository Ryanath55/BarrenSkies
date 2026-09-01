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

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    public static void bootstrapConfiguredFeatures(net.minecraft.data.worldgen.BootstrapContext<ConfiguredFeature<?, ?>> context) {
        context.register(
            ISLAND_STREAM_CONFIGURED,
            new ConfiguredFeature<>(ISLAND_STREAM.get(), NoneFeatureConfiguration.INSTANCE)
        );
    }

    /**
     * One placement per chunk, and no modifiers at all.
     *
     * <p>The feature walks its own sixteen by sixteen itself, so it wants the chunk rather than a spot in
     * it. A count would run it several times over the same columns, a height would be ignored, and a biome
     * filter would test one column on behalf of two hundred and fifty six. It does all of that per column,
     * where the answers actually differ.
     */
    public static void bootstrapPlacedFeatures(net.minecraft.data.worldgen.BootstrapContext<PlacedFeature> context) {
        context.register(
            ISLAND_STREAM_PLACED,
            new PlacedFeature(
                context.lookup(Registries.CONFIGURED_FEATURE).getOrThrow(ISLAND_STREAM_CONFIGURED),
                java.util.List.of()
            )
        );
    }

    /**
     * Added to every overworld biome rather than to the sky pool alone. A biome is only in one pool or the
     * other, but that depends on config, and the feature already refuses to run below the island floor.
     */
    public static void bootstrapBiomeModifiers(
        net.minecraft.data.worldgen.BootstrapContext<net.neoforged.neoforge.common.world.BiomeModifier> context
    ) {
        context.register(
            ResourceKey.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.BIOME_MODIFIERS, id("island_streams")),
            new net.neoforged.neoforge.common.world.BiomeModifiers.AddFeaturesBiomeModifier(
                context.lookup(Registries.BIOME).getOrThrow(net.minecraft.tags.BiomeTags.IS_OVERWORLD),
                net.minecraft.core.HolderSet.direct(
                    context.lookup(Registries.PLACED_FEATURE).getOrThrow(ISLAND_STREAM_PLACED)
                ),
                net.minecraft.world.level.levelgen.GenerationStep.Decoration.LAKES
            )
        );
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BarrenSkies.MOD_ID, path);
    }

    private BarrenSkiesFeatures() {
    }
}
