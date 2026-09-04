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
                // The source this generator was built with, not the one it is currently using. A mod that
                // takes biome placement over swaps the live one out; writing that back would save its
                // wrapper into level.dat and bake a dependency on it into the world.
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.declaredBiomeSource),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.baseSettings),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLANDS),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_RIDGES),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_DETAIL),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_CAVES),
                RegistryOps.retrieveElement(SkyIslandDensity.ISLAND_LANDFORM),
                // Not fields of the generator: retrieved from the ops the settings are read through, so
                // nothing is added to what gets written back out. The island caves need both to encode
                // and re-read the overworld cave functions, which is how their heights get moved.
                RegistryOps.retrieveRegistryLookup(net.minecraft.core.registries.Registries.DENSITY_FUNCTION)
                    .forGetter(generator -> generator.functions),
                RegistryOps.retrieveRegistryLookup(net.minecraft.core.registries.Registries.NOISE)
                    .forGetter(generator -> generator.noises)
            )
            .apply(instance, SkyIslandChunkGenerator::new)
    );

    private final BiomeSource declaredBiomeSource;
    private final Holder<NoiseGeneratorSettings> baseSettings;
    private final net.minecraft.core.HolderLookup.RegistryLookup<DensityFunction> functions;
    private final net.minecraft.core.HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises;

    public SkyIslandChunkGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> settings,
        Holder<NormalNoise.NoiseParameters> islands,
        Holder<NormalNoise.NoiseParameters> ridges,
        Holder<NormalNoise.NoiseParameters> detail,
        Holder<NormalNoise.NoiseParameters> caves,
        Holder<NormalNoise.NoiseParameters> landform,
        net.minecraft.core.HolderLookup.RegistryLookup<DensityFunction> functions,
        net.minecraft.core.HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises
    ) {
        // Wrapped lazily: data generation builds the generator while the settings are still unbound, and
        // dereferencing them there fails.
        super(biomeSource, new LazySettings(settings, islands, ridges, detail, caves, landform, functions, noises));
        this.declaredBiomeSource = biomeSource;
        this.baseSettings = settings;
        this.functions = functions;
        this.noises = noises;
    }

    /**
     * Where the surface of a column is, asked of the ground and never of the islands.
     *
     * <p>Two different questions get asked of one number. Generating the world wants the islands in: they
     * are terrain. Asking where the surface is almost always does not, because the things that ask are
     * structures, and a structure that wants the sea floor and is handed an island builds on the island.
     * Measured: of four hundred columns asked for a surface height, seventy three came back with an
     * island -- the share of the sky the islands cover, as it should be. Those seventy three are the
     * flying shipwrecks, and the mineshafts hanging in the air are the same query with a different
     * structure on the end of it.
     *
     * <p>Answered by lowering the ceiling of the search rather than by taking the islands out of the
     * density. Taking them out is the obvious move and it does not work: the column is sampled through a
     * NoiseChunk, and a NoiseChunk takes its router from the RandomState the level was built with, not
     * from the settings it is handed. A second generator carrying a second set of settings makes no
     * difference at all -- measured, it changed the count by not one column. A search that cannot look
     * above the island floor can only return ground, and needs nothing rewired to say so.
     *
     * <p>The cost is that no structure will place on an island. None was placing on one properly anyway,
     * and giving them islands to stand on is its own piece of work: they would need the island's own
     * heightmap, and most want a shoreline or a cave mouth that an island has not got.
     */
    private static net.minecraft.world.level.LevelHeightAccessor groundOnly(
        net.minecraft.world.level.LevelHeightAccessor level
    ) {
        int floor = SkyIslandDensity.islandFloor();
        int bottom = level.getMinBuildHeight();
        int height = Math.min(level.getHeight(), Math.max(16, floor - bottom));
        return net.minecraft.world.level.LevelHeightAccessor.create(bottom, height);
    }

    @Override
    public int getBaseHeight(
        int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types type,
        net.minecraft.world.level.LevelHeightAccessor level,
        net.minecraft.world.level.levelgen.RandomState randomState
    ) {
        return super.getBaseHeight(
            x, z, type,
            com.barrenskies.worldgen.StructureIntent.wantsGround() ? groundOnly(level) : level,
            randomState
        );
    }

    @Override
    public net.minecraft.world.level.NoiseColumn getBaseColumn(
        int x, int z, net.minecraft.world.level.LevelHeightAccessor level,
        net.minecraft.world.level.levelgen.RandomState randomState
    ) {
        return super.getBaseColumn(
            x, z,
            com.barrenskies.worldgen.StructureIntent.wantsGround() ? groundOnly(level) : level,
            randomState
        );
    }

    @Override
    protected MapCodec<? extends NoiseBasedChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * The biome source the world preset asked for, which stays put whatever happens to the live one.
     *
     * <p>{@link #getBiomeSource()} answers with whatever is installed right now, and that is deliberately
     * not always this: another mod may have wrapped it. This is what the layer rules are read back out of
     * when that has happened, and what gets written when the world is saved.
     */
    public BiomeSource declaredBiomeSource() {
        return this.declaredBiomeSource;
    }

    @Override
    public java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> createBiomes(
        net.minecraft.world.level.levelgen.RandomState randomState,
        net.minecraft.world.level.levelgen.blending.Blender blender,
        net.minecraft.world.level.StructureManager structureManager,
        net.minecraft.world.level.chunk.ChunkAccess chunk
    ) {
        // Islands hang a layer reach below the configured floor, so the biome switch has to sit that low too.
        int floor = SkyIslandDensity.islandFloor();
        int layerCount = BarrenSkiesConfig.ISLAND_LAYERS.get();
        double threshold = BarrenSkiesConfig.ISLAND_THRESHOLD.get();
        double scale = BarrenSkiesConfig.ISLAND_SCALE.get();
        NormalNoise islandNoise = randomState.getOrCreateNoise(SkyIslandDensity.ISLANDS);
        // A height well inside the barren pool, used to report what the ground below is.
        int groundQuartY = net.minecraft.core.QuartPos.fromBlock(floor - 96);

        // Both of the answers this needs are properties of a column, and it is asked for every height in
        // one: a chunk is four quarts across, four deep, and at this world height a hundred and ninety two
        // tall, so each of the sixteen columns was being asked the same two questions a hundred and ninety
        // two times over. Whether an island claims the column is four noise lookups, and what lies on the
        // ground below is a climate sample and a search of the biome tree. Worked out once a column and
        // kept, which is three thousand of each per chunk down to sixteen.
        int baseQuartX = net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int baseQuartZ = net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        byte[] claimed = new byte[16];
        @SuppressWarnings("unchecked")
        Holder<net.minecraft.world.level.biome.Biome>[] groundBiome = new Holder[16];

        net.minecraft.world.level.biome.BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            if (net.minecraft.core.QuartPos.toBlock(quartY) < floor) {
                return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
            }
            int dx = quartX - baseQuartX;
            int dz = quartZ - baseQuartZ;
            boolean inChunk = dx >= 0 && dx < 4 && dz >= 0 && dz < 4;
            int slot = inChunk ? dx * 4 + dz : -1;

            boolean island;
            if (slot >= 0 && claimed[slot] != 0) {
                island = claimed[slot] > 0;
            } else {
                island = SkyIslandDensity.hasIsland(
                    islandNoise,
                    net.minecraft.core.QuartPos.toBlock(quartX),
                    net.minecraft.core.QuartPos.toBlock(quartZ),
                    layerCount,
                    threshold,
                    scale
                );
                if (slot >= 0) {
                    claimed[slot] = (byte) (island ? 1 : -1);
                }
            }
            if (island) {
                return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
            }
            // Open sky between islands reports the ground beneath rather than naming a biome for air.
            if (slot < 0) {
                return this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }
            if (groundBiome[slot] == null) {
                groundBiome[slot] = this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }
            return groundBiome[slot];
        };

        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
                chunk.fillBiomesFromNoise(resolver, randomState.sampler());
                return chunk;
            },
            net.minecraft.Util.backgroundExecutor()
        );
    }

    /**
     * Rebuilds the world's noise settings with the islands added and room above for them to sit in.
     *
     */
    private static NoiseGeneratorSettings withIslands(
        Holder<NoiseGeneratorSettings> base,
        Holder<NormalNoise.NoiseParameters> islandNoise,
        Holder<NormalNoise.NoiseParameters> ridgeNoise,
        Holder<NormalNoise.NoiseParameters> detailNoise,
        Holder<NormalNoise.NoiseParameters> caveNoise,
        Holder<NormalNoise.NoiseParameters> landformNoise,
        net.minecraft.core.HolderLookup.RegistryLookup<DensityFunction> functions,
        net.minecraft.core.HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises
    ) {
        NoiseGeneratorSettings settings = base.value();
        NoiseRouter router = settings.noiseRouter();

        int reach = SkyIslandDensity.layerReach();
        int bandBottom = SkyIslandDensity.bandBottom();
        int bandTop = SkyIslandDensity.bandTop();

        DensityFunction islands = SkyIslandDensity.build(
            islandNoise,
            ridgeNoise,
            detailNoise,
            landformNoise,
            bandBottom,
            bandTop,
            BarrenSkiesConfig.ISLAND_LAYERS.get(),
            BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
            BarrenSkiesConfig.ISLAND_SCALE.get(),
            functions,
            noises
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
            // Structures do read the final density, and that is exactly why they needed the separate
            // answer above -- see getBaseHeight.
            router.initialDensityWithoutJaggedness(),
            DensityFunctions.max(router.finalDensity(), islands),
            router.veinToggle(),
            router.veinRidged(),
            router.veinGap()
        );

        return new NoiseGeneratorSettings(
            // Four block noise cells rather than eight. Terrain is interpolated between cell corners, so with
            // eight block cells any face shorter than that is smoothed into a ramp and edges read as curves.
            // Skylands over the Sea uses four for the same reason. It costs twice the vertical samples.
            NoiseSettings.create(BarrenSkiesWorldgen.WORLD_MIN_Y, BarrenSkiesWorldgen.WORLD_HEIGHT, 1, 1),
            settings.defaultBlock(),
            settings.defaultFluid(),
            withIslands,
            // Surface rules are written against ground level heights, so at island altitude they take their
            // wrong branch. Lifted copies apply above this height; the ground keeps the originals.
            //
            // Deliberately one layer reach under the band rather than islandFloor, which the pointed
            // underside took a good deal lower. The two errors are not the same size. Rock below this line
            // is the deep interior of an island underside, a hundred blocks down its own stone run, where
            // every surface rule that could fire wants a run top and there is none -- so it comes out as
            // stone either way. Ground caught above the line is a real surface handed the wrong rules, and
            // the barren layer was measured topping out at Y 251 against a floor here of 298.
            BarrenSkiesConfig.LIFT_SURFACE_RULES.get()
                ? LiftedSurfaceRules.liftAbove(
                    settings.surfaceRule(),
                    SkyIslandDensity.bandBottom() - SkyIslandDensity.layerReach(),
                    settings.seaLevel()
                )
                : settings.surfaceRule(),
            settings.spawnTarget(),
            settings.seaLevel(),
            settings.disableMobGeneration(),
            // Aquifers are what put ponds in island hollows and water in island caves, which is where all of
            // the island water in Skylands over the Sea comes from.
            BarrenSkiesConfig.ISLAND_WATER.get(),
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
            Holder<NormalNoise.NoiseParameters> detail,
            Holder<NormalNoise.NoiseParameters> caves,
            Holder<NormalNoise.NoiseParameters> landform,
            net.minecraft.core.HolderLookup.RegistryLookup<DensityFunction> functions,
            net.minecraft.core.HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises
        ) {
            this(base, Suppliers.memoize(
                () -> withIslands(base, islands, ridges, detail, caves, landform, functions, noises)));
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
