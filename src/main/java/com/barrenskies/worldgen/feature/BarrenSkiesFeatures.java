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

    public static final DeferredHolder<Feature<?>, IslandWaterfallFeature> ISLAND_WATERFALL =
        FEATURES.register("island_waterfall", () -> new IslandWaterfallFeature(NoneFeatureConfiguration.CODEC));

    public static final ResourceKey<ConfiguredFeature<?, ?>> ISLAND_WATERFALL_CONFIGURED =
        ResourceKey.create(Registries.CONFIGURED_FEATURE, id("island_waterfall"));

    public static final ResourceKey<PlacedFeature> ISLAND_WATERFALL_PLACED =
        ResourceKey.create(Registries.PLACED_FEATURE, id("island_waterfall"));

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    public static void bootstrapConfiguredFeatures(net.minecraft.data.worldgen.BootstrapContext<ConfiguredFeature<?, ?>> context) {
        context.register(
            ISLAND_WATERFALL_CONFIGURED,
            new ConfiguredFeature<>(ISLAND_WATERFALL.get(), NoneFeatureConfiguration.INSTANCE)
        );
    }

    /**
     * A height anywhere in the island band, not the world surface.
     *
     * <p>The surface heightmap describes a whole column, so where islands stack it only ever names the
     * topmost one, and every waterfall was landing on the highest island in the sky. A uniform height lets
     * the feature find whichever island is below the point it was given, which is what puts water on the
     * low ones a player can actually reach.
     *
     * <p>The range is generous rather than exact because a datapack cannot read the config. Points that
     * miss the band cost one height check and are dropped.
     *
     * <p>No rarity filter here either: nearly every spot considered is nowhere near an island edge and gets
     * turned down anyway, and the one dial for how many waterfalls there are belongs in the config where it
     * can be changed, not baked into a datapack.
     */
    public static void bootstrapPlacedFeatures(net.minecraft.data.worldgen.BootstrapContext<PlacedFeature> context) {
        context.register(
            ISLAND_WATERFALL_PLACED,
            new PlacedFeature(
                context.lookup(Registries.CONFIGURED_FEATURE).getOrThrow(ISLAND_WATERFALL_CONFIGURED),
                java.util.List.of(
                    net.minecraft.world.level.levelgen.placement.CountPlacement.of(8),
                    net.minecraft.world.level.levelgen.placement.InSquarePlacement.spread(),
                    net.minecraft.world.level.levelgen.placement.HeightRangePlacement.uniform(
                        net.minecraft.world.level.levelgen.VerticalAnchor.absolute(345),
                        net.minecraft.world.level.levelgen.VerticalAnchor.absolute(625)
                    ),
                    net.minecraft.world.level.levelgen.placement.BiomeFilter.biome()
                )
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
            ResourceKey.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.BIOME_MODIFIERS, id("island_waterfalls")),
            new net.neoforged.neoforge.common.world.BiomeModifiers.AddFeaturesBiomeModifier(
                context.lookup(Registries.BIOME).getOrThrow(net.minecraft.tags.BiomeTags.IS_OVERWORLD),
                net.minecraft.core.HolderSet.direct(
                    context.lookup(Registries.PLACED_FEATURE).getOrThrow(ISLAND_WATERFALL_PLACED)
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
