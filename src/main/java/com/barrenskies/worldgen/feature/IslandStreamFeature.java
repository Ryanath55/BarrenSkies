package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.Fluids;

/**
 * Streams cut into the tops of the sky islands.
 *
 * <p>Where a stream runs is a pure function of position and world seed: a noise field sampled per column,
 * with channel wherever it passes near zero and the depth falling away towards the banks. Nothing about it
 * depends on what has been generated yet, which is the whole point of it.
 *
 * <p>The version before this traced a path outwards from wherever the placement landed and carved up to
 * thirteen blocks along it, which meant writing well into the neighbouring chunks. Decoration gives no
 * order across chunks, so a neighbour already decorated had the ground cut from under its trees and was
 * left with them floating, and one not yet decorated grew its trees into the water afterwards. Streams
 * Reflowing has the same problem and answers it with two mixins: one defers chunk generation until its
 * river network for the region is known, and one cancels any other feature that tries to place inside a
 * stream. Making the channel a function of position answers it without either. Every chunk carves only its
 * own sixteen by sixteen, and the channels still line up across the seams because every chunk works out the
 * same field. Vegetation then runs afterwards, on ground that is already carved and already wet, and
 * declines to grow in it of its own accord.
 */
public class IslandStreamFeature extends Feature<NoneFeatureConfiguration> {
    /** How much of the noise range counts as channel. Its width in blocks follows from the wavelength. */
    private static final double BAND = 0.09D;

    /** Air needed above a surface before it counts as open ground rather than the roof of a cave. */
    private static final int OPEN_SKY = 6;

    public IslandStreamFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!BarrenSkiesConfig.ISLAND_WATERFALLS.get()) {
            return false;
        }

        WorldGenLevel level = context.level();
        NormalNoise noise = level.getLevel().getChunkSource().randomState()
            .getOrCreateNoise(SkyIslandDensity.ISLAND_STREAMS);

        int maxDepth = BarrenSkiesConfig.STREAM_DEPTH.get();
        int reach = SkyIslandDensity.layerReach();
        int bandBottom = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach;
        int bandTop = BarrenSkiesConfig.SKY_ISLAND_TOP.get() + reach * 2;

        // This chunk only. Every column of it, and nothing outside it.
        int baseX = context.origin().getX() & ~15;
        int baseZ = context.origin().getZ() & ~15;
        boolean carved = false;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;

                double across = Math.abs(noise.getValue(x, 0.0D, z));
                if (across >= BAND) {
                    continue;
                }
                double wet = wetness(noise, x, z);
                if (wet <= 0.0D) {
                    continue;
                }
                // Deepest along the middle of the channel and shallower towards the banks, so the trough
                // shape falls out of the noise rather than having to be drawn, and fading out again where
                // the wet country runs out.
                int depth = (int) Math.round(maxDepth * (1.0D - across / BAND) * wet);
                if (depth < 2) {
                    continue;
                }

                int surface = islandSurface(level, x, z, bandBottom, bandTop);
                if (surface == Integer.MIN_VALUE) {
                    continue;
                }
                if (!landsInWater(level, x, z)) {
                    continue;
                }
                cut(level, x, z, surface, depth);
                carved = true;
            }
        }
        return carved;
    }

    /** The topmost open island surface in a column, or MIN_VALUE if this column has no island in it. */
    private static int islandSurface(WorldGenLevel level, int x, int z, int bandBottom, int bandTop) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int air = 0;
        for (int y = bandTop; y >= bandBottom; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                air++;
                continue;
            }
            // Enough sky over it to be ground somebody could stand on, rather than a cave roof.
            if (air >= OPEN_SKY && state.isSolid()) {
                return y;
            }
            air = 0;
        }
        return Integer.MIN_VALUE;
    }

    /** Opens the channel and lays the water in the bottom of it. */
    private static void cut(WorldGenLevel level, int x, int z, int surface, int depth) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bed = surface - depth;
        for (int y = surface; y > bed + 1; y--) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // Two blocks of water in a channel several deep, so the banks stand well over the stream.
        for (int y = bed + 1; y >= bed; y--) {
            pos.set(x, y, z);
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
            level.scheduleTick(pos, Fluids.WATER, 0);
        }
        // A bed, in case the channel bottomed out over a cave.
        pos.set(x, bed - 1, z);
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
    }

    /**
     * How wet this stretch of sky is, from nothing to one.
     *
     * <p>Most of the sky is dry, and which parts are is decided by a second, far coarser reading of the
     * same noise -- the same field, sampled over hundreds of blocks instead of tens. A grid of island-sized
     * cells was the obvious way to do this and the wrong one: the grid is square and islands are not, so an
     * island lying across a cell edge had its stream stop dead along a straight invisible line. A noise
     * gives a region with an edge of its own shape, and returning a fraction rather than a yes lets the
     * channel shallow out and disappear on its own instead of ending.
     *
     * <p>The threshold is only approximately the configured percentage. Normal noise is not uniform, so
     * turning a share of the world into a cutoff exactly would take the distribution; this is monotonic and
     * lands close enough for a dial whose whole job is more or fewer.
     */
    private static double wetness(NormalNoise noise, int x, int z) {
        double scale = 0.15D;
        double cutoff = (50 - BarrenSkiesConfig.WATERFALL_ISLAND_CHANCE.get()) / 100.0D * 0.8D;
        double wet = noise.getValue(x * scale, 512.0D, z * scale);
        return Math.max(0.0D, Math.min(1.0D, (wet - cutoff) / 0.06D));
    }

    /**
     * Whether what lies under this column is water.
     *
     * <p>A stream running off an island over the barren surface is fresh water poured into a desert. The
     * biome at sea level reads as a cave biome wherever the ground stands above it, so it answers nothing
     * useful over land; looking for the water itself is the more direct question and settles both cases.
     */
    private static boolean landsInWater(WorldGenLevel level, int x, int z) {
        if (!BarrenSkiesConfig.WATERFALLS_ONLY_OVER_WATER.get()) {
            return true;
        }
        int sea = level.getSeaLevel();
        Holder<Biome> biome = level.getBiome(new BlockPos(x, sea, z));
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = sea - 1; y >= sea - 4; y--) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
