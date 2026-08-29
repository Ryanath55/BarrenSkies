package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.BarrenSkiesWorldgen;
import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Generates the barren surface from the world's own noise settings and adds the sky islands into the same
 * density pipeline.
 *
 * <p>Only two of the router's components are replaced: the final density, which decides where rock is, and
 * the density used for heightmaps, so structures and features find island ground. Everything else, the
 * continents, erosion, temperature and the rest, is passed through untouched. That is what lets a terrain
 * mod keep shaping the ground while the islands sit above it, without either side owning the same file.
 */
public class SkyIslandChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<SkyIslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.getBiomeSource()),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.baseSettings),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLANDS),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_RIDGES)
            )
            .apply(instance, SkyIslandChunkGenerator::new)
    );

    private final Holder<NoiseGeneratorSettings> baseSettings;

    public SkyIslandChunkGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> settings,
        Holder<NormalNoise.NoiseParameters> islands,
        Holder<NormalNoise.NoiseParameters> ridges
    ) {
        // Wrapped lazily: data generation builds the generator while the settings are still unbound, and
        // dereferencing them there fails.
        super(biomeSource, new LazySettings(settings, islands, ridges));
        this.baseSettings = settings;
    }

    @Override
    protected MapCodec<? extends NoiseBasedChunkGenerator> codec() {
        return CODEC;
    }

    /** Rebuilds the world's noise settings with the islands added and room above for them to sit in. */
    private static NoiseGeneratorSettings withIslands(
        Holder<NoiseGeneratorSettings> base,
        Holder<NormalNoise.NoiseParameters> islandNoise,
        Holder<NormalNoise.NoiseParameters> ridgeNoise
    ) {
        NoiseGeneratorSettings settings = base.value();
        NoiseRouter router = settings.noiseRouter();

        DensityFunction islands = SkyIslandDensity.build(
            islandNoise,
            ridgeNoise,
            BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
            Math.max(BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(), BarrenSkiesConfig.SKY_ISLAND_TOP.get()),
            BarrenSkiesConfig.ISLAND_LAYERS.get(),
            BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
            BarrenSkiesConfig.ISLAND_SCALE.get()
        );

        NoiseRouter withIslands = new NoiseRouter(
            router.barrierNoise(),
            router.fluidLevelFloodednessNoise(),
            router.fluidLevelSpreadNoise(),
            router.lavaNoise(),
            router.temperature(),
            router.vegetation(),
            router.continents(),
            router.erosion(),
            router.depth(),
            router.ridges(),
            DensityFunctions.max(router.initialDensityWithoutJaggedness(), islands),
            DensityFunctions.max(router.finalDensity(), islands),
            router.veinToggle(),
            router.veinRidged(),
            router.veinGap()
        );

        return new NoiseGeneratorSettings(
            NoiseSettings.create(BarrenSkiesWorldgen.WORLD_MIN_Y, BarrenSkiesWorldgen.WORLD_HEIGHT, 1, 2),
            settings.defaultBlock(),
            settings.defaultFluid(),
            withIslands,
            settings.surfaceRule(),
            settings.spawnTarget(),
            settings.seaLevel(),
            settings.disableMobGeneration(),
            // Aquifers decide a fluid level for a whole column, assuming terrain rises from bedrock. Floating
            // islands break that, and it filled the air around them with sheets and columns of water.
            false,
            settings.oreVeinsEnabled(),
            settings.useLegacyRandomSource()
        );
    }

    /**
     * A holder reporting the world noise settings with the islands folded in, worked out on first use.
     * Everything except the value is delegated, so identity and serialisation are unchanged.
     */
    private record LazySettings(
        Holder<NoiseGeneratorSettings> base, Supplier<NoiseGeneratorSettings> wrapped
    ) implements Holder<NoiseGeneratorSettings> {
        LazySettings(
            Holder<NoiseGeneratorSettings> base,
            Holder<NormalNoise.NoiseParameters> islands,
            Holder<NormalNoise.NoiseParameters> ridges
        ) {
            this(base, Suppliers.memoize(() -> withIslands(base, islands, ridges)));
        }

        @Override
        public NoiseGeneratorSettings value() {
            return this.wrapped.get();
        }

        @Override
        public boolean isBound() {
            return this.base.isBound();
        }

        @Override
        public boolean is(ResourceLocation location) {
            return this.base.is(location);
        }

        @Override
        public boolean is(ResourceKey<NoiseGeneratorSettings> key) {
            return this.base.is(key);
        }

        @Override
        public boolean is(java.util.function.Predicate<ResourceKey<NoiseGeneratorSettings>> predicate) {
            return this.base.is(predicate);
        }

        @Override
        public boolean is(TagKey<NoiseGeneratorSettings> tag) {
            return this.base.is(tag);
        }

        @Override
        public boolean is(Holder<NoiseGeneratorSettings> holder) {
            return this.base.is(holder);
        }

        @Override
        public java.util.stream.Stream<TagKey<NoiseGeneratorSettings>> tags() {
            return this.base.tags();
        }

        @Override
        public com.mojang.datafixers.util.Either<ResourceKey<NoiseGeneratorSettings>, NoiseGeneratorSettings> unwrap() {
            return this.base.unwrap();
        }

        @Override
        public java.util.Optional<ResourceKey<NoiseGeneratorSettings>> unwrapKey() {
            return this.base.unwrapKey();
        }

        @Override
        public Holder.Kind kind() {
            return this.base.kind();
        }

        @Override
        public boolean canSerializeIn(HolderOwner<NoiseGeneratorSettings> owner) {
            return this.base.canSerializeIn(owner);
        }
    }
}
