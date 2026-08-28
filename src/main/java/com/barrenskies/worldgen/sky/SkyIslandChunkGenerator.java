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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.core.QuartPos;

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

    private static final net.minecraft.resources.ResourceLocation SKY_ISLAND_RANDOM =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("barrenskies", "sky_islands");

    private final Holder<NoiseGeneratorSettings> settings;
    private final Supplier<Integer> floorY = Suppliers.memoize(BarrenSkiesConfig.SKY_ISLAND_BOTTOM::get);

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
        return SkyIslandLayouts.forSeed(seed, this.floorY.get(), BarrenSkiesConfig.ISLAND_DENSITY.get());
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        SkyIslandLayout layout = this.layout(randomState);
        List<Holder<Biome>> skyBiomes = this.getBiomeSource() instanceof LayeredBiomeSource layered ? layered.skyBiomes() : List.of();
        if (skyBiomes.isEmpty()) {
            return super.createBiomes(randomState, blender, structureManager, chunk);
        }

        int floor = this.floorY.get();
        BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            if (QuartPos.toBlock(quartY) < floor) {
                return this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler);
            }
            // One biome for the whole island, so it reads as a single place from the air.
            SkyIslandLayout.Island island = layout.islandAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ));
            return island == null
                ? this.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, sampler)
                : skyBiomes.get(Math.floorMod(island.biomeSelector(), skyBiomes.size()));
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

                int surface = layout.surfaceY(island, x, z);
                int bottom = layout.bottomY(island, x, z);
                if (surface == Integer.MIN_VALUE || bottom >= surface) {
                    continue;
                }
                surface = Math.min(surface, ceiling);

                Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z));
                for (int y = Math.max(bottom, chunk.getMinBuildHeight()); y <= surface; y++) {
                    cursor.set(x, y, z);
                    chunk.setBlockState(cursor, this.blockFor(biome, surface - y), false);
                }
            }
        }
        return chunk;
    }

    /** Surface dressing for the island, chosen from the biome so a desert island is not capped with turf. */
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
