package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.BarrenSkiesWorldgen;
import com.barrenskies.worldgen.LayeredBiomeSource;
import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Generates the barren surface from the world's own noise settings and adds the sky islands into the same
 * density pipeline.
 *
 * <p>The islands are folded into the noise router rather than stamped on afterwards, so surface rules,
 * carvers, heightmaps and structure placement all see island rock as terrain. The router is wrapped rather
 * than replaced, so whichever mod supplies the overworld shaping still shapes the ground underneath.
 */
public class SkyIslandChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<SkyIslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.getBiomeSource()),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.baseSettings),
                // A registered noise, retrieved so it carries its key. The router seeds noise by key, and
                // the biome pass looks the same one up from the world, which is how both ends agree on a
                // seed without one of them inventing its own.
                RegistryOps.retrieveElement(Noises.AQUIFER_BARRIER)
            )
            .apply(instance, SkyIslandChunkGenerator::new)
    );

    /**
     * A holder that reports the world noise settings with the islands folded in, but only works them out
     * when first asked. Data generation constructs the generator while the underlying settings are still
     * unbound, so the wrapping cannot happen in the constructor.
     */
    private record LazySettings(Holder<NoiseGeneratorSettings> base, Holder<NormalNoise.NoiseParameters> seedNoise, Supplier<NoiseGeneratorSettings> wrapped)
        implements Holder<NoiseGeneratorSettings> {
        LazySettings(Holder<NoiseGeneratorSettings> base, Holder<NormalNoise.NoiseParameters> seedNoise) {
            this(base, seedNoise, Suppliers.memoize(() -> withIslands(base, seedNoise)));
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
        public boolean is(net.minecraft.resources.ResourceLocation location) {
            return this.base.is(location);
        }

        @Override
        public boolean is(net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> key) {
            return this.base.is(key);
        }

        @Override
        public boolean is(java.util.function.Predicate<net.minecraft.resources.ResourceKey<NoiseGeneratorSettings>> predicate) {
            return this.base.is(predicate);
        }

        @Override
        public boolean is(net.minecraft.tags.TagKey<NoiseGeneratorSettings> tag) {
            return this.base.is(tag);
        }

        @Override
        public boolean is(Holder<NoiseGeneratorSettings> holder) {
            return this.base.is(holder);
        }

        @Override
        public java.util.stream.Stream<net.minecraft.tags.TagKey<NoiseGeneratorSettings>> tags() {
            return this.base.tags();
        }

        @Override
        public com.mojang.datafixers.util.Either<net.minecraft.resources.ResourceKey<NoiseGeneratorSettings>, NoiseGeneratorSettings> unwrap() {
            return this.base.unwrap();
        }

        @Override
        public java.util.Optional<net.minecraft.resources.ResourceKey<NoiseGeneratorSettings>> unwrapKey() {
            return this.base.unwrapKey();
        }

        @Override
        public Holder.Kind kind() {
            return this.base.kind();
        }

        @Override
        public boolean canSerializeIn(net.minecraft.core.HolderOwner<NoiseGeneratorSettings> owner) {
            return this.base.canSerializeIn(owner);
        }
    }

    /** Fraction of the temperature-sorted pool an island may vary within, for local variety. */
    private static final double BIOME_TEMPERATURE_WINDOW = 0.18D;

    /** Slack around an island's envelope within which a column still counts as that island's biome. */
    private static final int BIOME_MARGIN = 24;

    private final Holder<NoiseGeneratorSettings> baseSettings;
    private final Supplier<SkyIslandLayout.Settings> shape = Suppliers.memoize(SkyIslandChunkGenerator::readShape);

    public SkyIslandChunkGenerator(
        BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, Holder<NormalNoise.NoiseParameters> seedNoise
    ) {
        // Wrapped lazily: at data generation time this holder is not yet bound, and dereferencing it there
        // fails. Everything except the value itself is delegated, so identity and serialisation are unchanged.
        super(biomeSource, new LazySettings(settings, seedNoise));
        this.baseSettings = settings;
    }

    private static SkyIslandLayout.Settings readShape() {
        return new SkyIslandLayout.Settings(
            BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
            Math.max(BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(), BarrenSkiesConfig.SKY_ISLAND_TOP.get()),
            BarrenSkiesConfig.ISLAND_DENSITY.get() * 0.55D,
            BarrenSkiesConfig.ISLAND_RADIUS_MIN.get(),
            Math.max(BarrenSkiesConfig.ISLAND_RADIUS_MIN.get(), BarrenSkiesConfig.ISLAND_RADIUS_MAX.get()),
            BarrenSkiesConfig.ISLAND_SPACING.get()
        );
    }

    /**
     * Rebuilds the world's noise settings with the islands added and room above for them to sit in.
     *
     * <p>Both the final density and the density used for heightmaps are combined with the island field, so
     * island rock is solid ground and is also what a heightmap query finds. Every other part of the router
     * is passed through untouched, which is what preserves the ground a terrain mod produces.
     */
    private static NoiseGeneratorSettings withIslands(
        Holder<NoiseGeneratorSettings> base, Holder<NormalNoise.NoiseParameters> seedNoise
    ) {
        NoiseGeneratorSettings settings = base.value();
        NoiseRouter router = settings.noiseRouter();

        // The noise here carries nothing but the world seed: it is seeded when the router is built, and
        // sampling it is the only way a seed can reach a density function.
        DensityFunction islands = new SkyIslandDensityFunction(
            new DensityFunction.NoiseHolder(seedNoise, null),
            readShape(),
            router.finalDensity(),
            BarrenSkiesConfig.WORLD_TERRAIN_INFLUENCE.get()
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

        // A finer vertical cell than vanilla's, so island detail survives the interpolation the noise
        // pipeline applies between cell corners.
        NoiseSettings noise = NoiseSettings.create(BarrenSkiesWorldgen.WORLD_MIN_Y, BarrenSkiesWorldgen.WORLD_HEIGHT, 1, 1);

        return new NoiseGeneratorSettings(
            noise,
            settings.defaultBlock(),
            settings.defaultFluid(),
            withIslands,
            settings.surfaceRule(),
            settings.spawnTarget(),
            settings.seaLevel(),
            settings.disableMobGeneration(),
            settings.aquifersEnabled(),
            settings.oreVeinsEnabled(),
            settings.useLegacyRandomSource()
        );
    }

    @Override
    protected MapCodec<? extends NoiseBasedChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        List<Holder<Biome>> skyBiomes = this.getBiomeSource() instanceof LayeredBiomeSource layered ? layered.skyBiomes() : List.of();
        if (skyBiomes.isEmpty()) {
            return super.createBiomes(randomState, blender, structureManager, chunk);
        }

        SkyIslandLayout.Settings shape = this.shape.get();
        SkyIslandLayout layout = SkyIslandLayouts.forSeed(SkyIslandDensityFunction.seedOf(randomState.getOrCreateNoise(Noises.AQUIFER_BARRIER)), shape);
        int floor = shape.bandBottom();
        int groundQuartY = QuartPos.fromBlock(floor - 64);

        BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            int y = QuartPos.toBlock(quartY);
            if (y < floor) {
                return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
            }

            int x = QuartPos.toBlock(quartX);
            int z = QuartPos.toBlock(quartZ);
            SkyIslandLayout.Column column = layout.columnAt(x, z);
            if (column == null) {
                return this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }

            // An island decides its own shape, so the envelope is available without knowing the biome first.
            TerrainProfile profile = TerrainProfile.forIsland(column.island().biomeSelector());
            SkyIslandLayout.Envelope envelope = layout.envelope(column, x, z, profile);
            if (envelope.isEmpty() || y < envelope.bottom() - BIOME_MARGIN || y > envelope.top() + BIOME_MARGIN) {
                // Open sky reports the barren ground below rather than naming a biome for empty air.
                return this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }
            return skyBiomes.get(this.skyBiomeIndex(column, skyBiomes.size(), sampler));
        };

        return CompletableFuture.supplyAsync(
            () -> {
                chunk.fillBiomesFromNoise(resolver, randomState.sampler());
                return chunk;
            },
            net.minecraft.Util.backgroundExecutor()
        );
    }

    /**
     * Picks this island's biome from the sky pool, which is sorted cold to warm.
     *
     * <p>The world's own temperature field decides roughly where in that range to look, so neighbouring
     * islands share a climate and a snowy peak does not end up beside a jungle. The island's own selector
     * then chooses within a window around that point, keeping local variety without breaking the pattern.
     */
    private int skyBiomeIndex(SkyIslandLayout.Column column, int count, Climate.Sampler sampler) {
        SkyIslandLayout.Island island = column.island();
        // Sampled at the anchor rather than per column, so one island is one biome throughout.
        Climate.TargetPoint climate = sampler.sample(
            QuartPos.fromBlock(island.centreX()), QuartPos.fromBlock(island.deckY()), QuartPos.fromBlock(island.centreZ())
        );
        double temperature = Mth.clamp((Climate.unquantizeCoord(climate.temperature()) + 1.0F) * 0.5D, 0.0D, 1.0D);

        double window = Math.max(1.0D, count * BIOME_TEMPERATURE_WINDOW);
        double jitter = (Math.floorMod(island.biomeSelector() * 2654435761L, 1024L) / 1023.0D - 0.5D) * window;
        return (int) Mth.clamp(Math.round(temperature * (count - 1) + jitter), 0L, count - 1L);
    }
}
