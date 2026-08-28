package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.LayeredBiomeSource;
import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;

/**
 * Generates the barren surface exactly as the underlying noise settings describe, then adds the sky
 * island layer above it.
 *
 * <p>The islands are added after the base terrain rather than folded into its density functions. That
 * keeps the ground compatible with whichever terrain mod supplies the overworld's noise settings.
 */
public class SkyIslandChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<SkyIslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.getBiomeSource()),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.settings)
            )
            .apply(instance, SkyIslandChunkGenerator::new)
    );

    private static final BlockState BODY = Blocks.STONE.defaultBlockState();
    private static final BlockState SOIL = Blocks.DIRT.defaultBlockState();
    private static final BlockState TURF = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();

    /** How far past the smooth envelope the 3D field is allowed to place or remove rock. */
    private static final int SURFACE_MARGIN = 16;

    /** How far above and below its deck an island claims the biome, beyond which the sky reads as ground. */
    private static final int ISLAND_BIOME_REACH = 110;

    /** Fraction of the temperature-sorted pool an island may vary within, for local variety. */
    private static final double BIOME_TEMPERATURE_WINDOW = 0.18D;

    private static final net.minecraft.resources.ResourceLocation SKY_ISLAND_RANDOM =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("barrenskies", "sky_islands");

    private final Holder<NoiseGeneratorSettings> settings;
    private final Supplier<SkyIslandLayout.Settings> shape = Suppliers.memoize(
        () -> new SkyIslandLayout.Settings(
            BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
            Math.max(BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(), BarrenSkiesConfig.SKY_ISLAND_TOP.get()),
            BarrenSkiesConfig.ISLAND_DENSITY.get() * 0.55D,
            BarrenSkiesConfig.ISLAND_RADIUS_MIN.get(),
            Math.max(BarrenSkiesConfig.ISLAND_RADIUS_MIN.get(), BarrenSkiesConfig.ISLAND_RADIUS_MAX.get()),
            BarrenSkiesConfig.ISLAND_SPACING.get()
        )
    );

    public SkyIslandChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
        this.settings = settings;
    }

    @Override
    protected MapCodec<? extends NoiseBasedChunkGenerator> codec() {
        return CODEC;
    }

    private SkyIslandLayout layout(RandomState randomState) {
        // RandomState does not expose the level seed, but its positional random factories are derived from it.
        long seed = randomState.getOrCreateRandomFactory(SKY_ISLAND_RANDOM).at(0, 0, 0).nextLong();
        return SkyIslandLayouts.forSeed(seed, this.shape.get());
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        SkyIslandLayout layout = this.layout(randomState);
        List<Holder<Biome>> skyBiomes = this.getBiomeSource() instanceof LayeredBiomeSource layered ? layered.skyBiomes() : List.of();
        if (skyBiomes.isEmpty()) {
            return super.createBiomes(randomState, blender, structureManager, chunk);
        }

        int floor = this.shape.get().bandBottom();
        // A height safely inside the surface pool, used to read what the ground below reports.
        int groundQuartY = QuartPos.fromBlock(floor - 64);
        BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            int y = QuartPos.toBlock(quartY);
            if (y < floor) {
                return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
            }

            SkyIslandLayout.Island island = layout.islandAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ));
            // Only the air an island actually occupies gets its biome. Reporting a forest across the whole
            // column meant the debug screen named a biome for open sky hundreds of blocks from any land.
            // Empty sky takes the barren biome from the ground below instead, which is what a player
            // flying between islands is actually above.
            if (island == null || y < island.deckY() - ISLAND_BIOME_REACH || y > island.deckY() + ISLAND_BIOME_REACH) {
                return this.getBiomeSource().getNoiseBiome(quartX, groundQuartY, quartZ, sampler);
            }
            return skyBiomes.get(this.skyBiomeIndex(island, skyBiomes.size(), sampler));
        };

        return CompletableFuture.supplyAsync(
            () -> {
                chunk.fillBiomesFromNoise(resolver, randomState.sampler());
                return chunk;
            },
            net.minecraft.Util.backgroundExecutor()
        );
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        return super.fillFromNoise(blender, randomState, structureManager, chunk)
            .thenApply(filled -> this.addIslands(filled, randomState));
    }

    private ChunkAccess addIslands(ChunkAccess chunk, RandomState randomState) {
        SkyIslandLayout layout = this.layout(randomState);
        // The router belongs to whichever mod supplies the overworld noise settings, so with a terrain
        // mod installed the islands are shaped by its density functions rather than by our own noise.
        double influence = BarrenSkiesConfig.WORLD_TERRAIN_INFLUENCE.get();
        // One sampler per chunk, sized to the sky band. The router is read on a coarse lattice rather
        // than per block, which is how vanilla evaluates its own density functions.
        TerrainSampler terrain = influence <= 0.0D
            ? TerrainSampler.NONE
            : new CoarseTerrainSampler(randomState.router().finalDensity(), influence);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int ceiling = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                SkyIslandLayout.Island island = layout.islandAt(x, z);
                if (island == null) {
                    continue;
                }

                // Biomes are filled before terrain, and a sky biome does not vary with height, so the
                // island biome is available here and is what decides how its terrain is shaped.
                Holder<Biome> biome = chunk.getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(island.deckY()), QuartPos.fromBlock(z)
                );
                TerrainProfile profile = profileFor(biome);
                SkyIslandLayout.Envelope envelope = layout.envelope(island, x, z, profile);
                if (envelope.isEmpty()) {
                    continue;
                }

                // Scan a margin past the envelope so the 3D field can hang rock below it or raise spurs
                // above it. Depth is counted down from each run of solid blocks rather than from the
                // envelope, so the top of an overhang gets its own grass instead of bare stone.
                int from = Math.max(envelope.bottom() - SURFACE_MARGIN * 2, chunk.getMinBuildHeight());
                int to = Math.min(envelope.top() + SURFACE_MARGIN, ceiling);
                int depth = 0;
                boolean air = true;
                for (int y = to; y >= from; y--) {
                    if (!layout.isSolid(island, x, y, z, profile, envelope, terrain)) {
                        air = true;
                        continue;
                    }
                    depth = air ? 0 : depth + 1;
                    air = false;
                    cursor.set(x, y, z);
                    chunk.setBlockState(cursor, this.blockFor(biome, depth), false);
                }
            }
        }
        return chunk;
    }

    /** Surface dressing for the island, chosen from the biome so a desert island is not capped with turf. */
    /**
     * Picks this island's biome from the sky pool, which is sorted cold to warm.
     *
     * <p>The world's own temperature field decides roughly where in that range to look, so neighbouring
     * islands share a climate and a snowy peak does not end up beside a jungle. The island's own selector
     * then chooses within a window around that point, keeping local variety without breaking the pattern.
     */
    private int skyBiomeIndex(SkyIslandLayout.Island island, int count, Climate.Sampler sampler) {
        // Sampled at the anchor rather than per column, so one island is one biome throughout.
        Climate.TargetPoint climate = sampler.sample(
            QuartPos.fromBlock(island.centreX()), QuartPos.fromBlock(island.deckY()), QuartPos.fromBlock(island.centreZ())
        );
        double temperature = Mth.clamp((Climate.unquantizeCoord(climate.temperature()) + 1.0F) * 0.5D, 0.0D, 1.0D);

        double window = Math.max(1.0D, count * BIOME_TEMPERATURE_WINDOW);
        double jitter = (Math.floorMod(island.biomeSelector() * 2654435761L, 1024L) / 1023.0D - 0.5D) * window;
        return (int) Mth.clamp(Math.round(temperature * (count - 1) + jitter), 0L, count - 1L);
    }

    /** How rugged this island is, so a mountain island is not shaped like a plains one. */
    private static TerrainProfile profileFor(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return TerrainProfile.ERODED;
        }
        if (biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL)) {
            return TerrainProfile.RUGGED;
        }
        if (biome.is(BiomeTags.HAS_SWAMP_HUT) || biome.is(BiomeTags.IS_RIVER)) {
            return TerrainProfile.BASIN;
        }
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA)) {
            return TerrainProfile.ROLLING;
        }
        return TerrainProfile.FLAT;
    }

    private BlockState blockFor(Holder<Biome> biome, int depthBelowSurface) {
        if (depthBelowSurface > 4) {
            return BODY;
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return depthBelowSurface == 0 ? RED_SAND : TERRACOTTA;
        }
        if (biome.value().getBaseTemperature() >= 1.5F && !biome.value().hasPrecipitation()) {
            return depthBelowSurface == 0 ? SAND : SANDSTONE;
        }
        if (biome.value().coldEnoughToSnow(BlockPos.ZERO)) {
            return depthBelowSurface == 0 ? SNOW : SOIL;
        }
        return depthBelowSurface == 0 ? TURF : SOIL;
    }
}
