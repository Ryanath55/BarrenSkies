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
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_RIDGES),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_DETAIL)
            )
            .apply(instance, SkyIslandChunkGenerator::new)
    );

    private final Holder<NoiseGeneratorSettings> baseSettings;

    public SkyIslandChunkGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> settings,
        Holder<NormalNoise.NoiseParameters> islands,
        Holder<NormalNoise.NoiseParameters> ridges,
        Holder<NormalNoise.NoiseParameters> detail
    ) {
        // Wrapped lazily: data generation builds the generator while the settings are still unbound, and
        // dereferencing them there fails.
        super(biomeSource, new LazySettings(settings, islands, ridges, detail));
        this.baseSettings = settings;
    }

    @Override
    protected MapCodec<? extends NoiseBasedChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> createBiomes(
        net.minecraft.world.level.levelgen.RandomState randomState,
        net.minecraft.world.level.levelgen.blending.Blender blender,
        net.minecraft.world.level.StructureManager structureManager,
        net.minecraft.world.level.chunk.ChunkAccess chunk
    ) {
        // Islands hang a layer reach below the configured floor, so the biome switch has to sit that low too.
        int floor = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - SkyIslandDensity.LAYER_REACH;
        int layerCount = BarrenSkiesConfig.ISLAND_LAYERS.get();
        double threshold = BarrenSkiesConfig.ISLAND_THRESHOLD.get();
        double scale = BarrenSkiesConfig.ISLAND_SCALE.get();
        NormalNoise islandNoise = randomState.getOrCreateNoise(SkyIslandDensity.ISLANDS);
        // A height well inside the barren pool, used to report what the ground below is.
        int groundQuartY = net.minecraft.core.QuartPos.fromBlock(floor - 96);

        net.minecraft.world.level.biome.BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            if (net.minecraft.core.QuartPos.toBlock(quartY) >= floor
                && !SkyIslandDensity.hasIsland(
                    islandNoise,
                    net.minecraft.core.QuartPos.toBlock(quartX),
                    net.minecraft.core.QuartPos.toBlock(quartZ),
                    layerCount,
                    threshold,
                    scale
                )) {
                // Open sky between islands reports the ground beneath rather than naming a biome for air.
                return this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }
            return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
        };

        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
                chunk.fillBiomesFromNoise(resolver, randomState.sampler());
                return chunk;
            },
            net.minecraft.Util.backgroundExecutor()
        );
    }

    /** Rebuilds the world's noise settings with the islands added and room above for them to sit in. */
    private static NoiseGeneratorSettings withIslands(
        Holder<NoiseGeneratorSettings> base,
        Holder<NormalNoise.NoiseParameters> islandNoise,
        Holder<NormalNoise.NoiseParameters> ridgeNoise,
        Holder<NormalNoise.NoiseParameters> detailNoise
    ) {
        NoiseGeneratorSettings settings = base.value();
        NoiseRouter router = settings.noiseRouter();

        DensityFunction islands = SkyIslandDensity.build(
            islandNoise,
            ridgeNoise,
            detailNoise,
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
            // Only the final density carries the islands. The other one sets the preliminary surface level,
            // which is a single height per column: folding islands into it moved that level up to the
            // island, so the ground underneath never met its own surface rules and was left bare stone.
            // Structures are unaffected: height queries read the final density, not this one, which is why
            // Skylands over the Sea ships no structure files of its own.
            router.initialDensityWithoutJaggedness(),
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
            // Surface rules are written against ground level heights, so at island altitude they take their
            // wrong branch. Lifted copies apply above the island floor; the ground keeps the originals.
            BarrenSkiesConfig.LIFT_SURFACE_RULES.get()
                ? LiftedSurfaceRules.liftAbove(
                    settings.surfaceRule(),
                    BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - SkyIslandDensity.LAYER_REACH,
                    settings.seaLevel()
                )
                : settings.surfaceRule(),
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
            Holder<NormalNoise.NoiseParameters> ridges,
            Holder<NormalNoise.NoiseParameters> detail
        ) {
            this(base, Suppliers.memoize(() -> withIslands(base, islands, ridges, detail)));
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
