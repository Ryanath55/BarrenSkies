package com.barrenskies.worldgen.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Contributes the sky islands to the world's terrain density.
 *
 * <p>Earlier versions stamped islands into chunks after the terrain pass had finished. That left them
 * invisible to everything downstream that asks the generator about terrain: surface rules never ran on
 * them, structures could not find their ground, and features placed against a height the islands were not
 * part of. Expressing them as a density function instead folds them into the same pipeline the ground
 * uses, so the rest of the game treats island rock as rock.
 *
 * <p>This wraps the world's existing router rather than replacing it, which is what keeps a terrain mod's
 * ground intact underneath.
 */
public final class SkyIslandDensityFunction implements DensityFunction.SimpleFunction {
    public static final MapCodec<SkyIslandDensityFunction> CODEC_INSTANCE = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                DensityFunction.NoiseHolder.CODEC.fieldOf("seed_noise").forGetter(function -> function.seedNoise),
                SettingsCodec.CODEC.fieldOf("settings").forGetter(function -> function.settings),
                DensityFunction.HOLDER_HELPER_CODEC.fieldOf("ground").forGetter(function -> function.ground),
                com.mojang.serialization.Codec.DOUBLE.fieldOf("ground_influence").forGetter(function -> function.groundInfluence)
            )
            .apply(instance, SkyIslandDensityFunction::new)
    );
    public static final KeyDispatchDataCodec<SkyIslandDensityFunction> CODEC = new KeyDispatchDataCodec<>(CODEC_INSTANCE);

    /** Beyond this distance from the island band there is nothing to compute, so skip the column work. */
    private static final int BAND_MARGIN = 96;
    /** Columns cached per worker thread. A chunk touches only a handful, so this comfortably covers one. */
    private static final int COLUMN_CACHE = 512;

    private final DensityFunction.NoiseHolder seedNoise;
    private final SkyIslandLayout.Settings settings;
    /** The world terrain density, read before the islands were added to it, so there is no circularity. */
    private final DensityFunction ground;
    private final double groundInfluence;
    private final SkyIslandLayout layout;
    private final ThreadLocal<Long2ObjectLinkedOpenHashMap<ColumnData>> columns =
        ThreadLocal.withInitial(Long2ObjectLinkedOpenHashMap::new);

    public SkyIslandDensityFunction(
        DensityFunction.NoiseHolder seedNoise, SkyIslandLayout.Settings settings, DensityFunction ground, double groundInfluence
    ) {
        this.seedNoise = seedNoise;
        this.settings = settings;
        this.ground = ground;
        this.groundInfluence = groundInfluence;
        // The noise is seeded from the world seed when the router is built, so sampling it at a fixed
        // point gives a value unique to this world. That is the only route a seed has into here.
        this.layout = SkyIslandLayouts.forSeed(seedOf(seedNoise), settings);
    }

    /**
     * A world specific seed taken from a seeded noise. Sampling it at a fixed point is the only route a
     * world seed has into a density function, and reading the same noise elsewhere gives the same value,
     * which is what keeps the terrain and the biome passes in agreement.
     */
    public static long seedOf(DensityFunction.NoiseHolder noise) {
        return Double.doubleToLongBits(noise.getValue(0.5D, 0.5D, 0.5D));
    }

    public static long seedOf(net.minecraft.world.level.levelgen.synth.NormalNoise noise) {
        return Double.doubleToLongBits(noise.getValue(0.5D, 0.5D, 0.5D));
    }

    /** Everything about a column that does not vary with height, worked out once and reused down the column. */
    private record ColumnData(SkyIslandLayout.Column column, TerrainProfile profile, SkyIslandLayout.Envelope envelope) {
        static final ColumnData EMPTY = new ColumnData(null, null, null);
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int y = context.blockY();
        if (y < this.settings.bandBottom() - BAND_MARGIN || y > this.settings.bandTop() + BAND_MARGIN) {
            return -1.0D;
        }

        ColumnData data = this.columnAt(context.blockX(), context.blockZ());
        if (data.column() == null) {
            return -1.0D;
        }
        // Island rock is shaped by the world own terrain density, read from a distant place at normal
        // ground height and lifted here, so islands inherit the character of whatever mod shapes the ground.
        TerrainSampler lifted = this.groundInfluence <= 0.0D
            ? TerrainSampler.NONE
            : (sx, sy, sz) -> this.ground.compute(new DensityFunction.SinglePointContext(sx, sy, sz)) * this.groundInfluence;
        double d = this.layout.density(
            data.column(), context.blockX(), y, context.blockZ(), data.profile(), data.envelope(), lifted
        );
        return d;
    }

    private ColumnData columnAt(int x, int z) {
        Long2ObjectLinkedOpenHashMap<ColumnData> cache = this.columns.get();
        long key = (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
        ColumnData cached = cache.getAndMoveToFirst(key);
        if (cached != null) {
            return cached;
        }

        SkyIslandLayout.Column column = this.layout.columnAt(x, z);
        ColumnData data = ColumnData.EMPTY;
        if (column != null) {
            TerrainProfile profile = TerrainProfile.forIsland(column.island().biomeSelector());
            data = new ColumnData(column, profile, this.layout.envelope(column, x, z, profile));
        }

        cache.putAndMoveToFirst(key, data);
        if (cache.size() > COLUMN_CACHE) {
            cache.removeLast();
        }
        return data;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        // The visitor is what attaches the world seed to the noise, so rebuild through it.
        return visitor.apply(
            new SkyIslandDensityFunction(
                visitor.visitNoise(this.seedNoise), this.settings, this.ground.mapAll(visitor), this.groundInfluence
            )
        );
    }

    @Override
    public double minValue() {
        return -1.0D;
    }

    @Override
    public double maxValue() {
        return 1.0D;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** Serialisation for the shape settings, needed only so the function satisfies the density function codec. */
    private static final class SettingsCodec {
        static final com.mojang.serialization.Codec<SkyIslandLayout.Settings> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    com.mojang.serialization.Codec.INT.fieldOf("band_bottom").forGetter(SkyIslandLayout.Settings::bandBottom),
                    com.mojang.serialization.Codec.INT.fieldOf("band_top").forGetter(SkyIslandLayout.Settings::bandTop),
                    com.mojang.serialization.Codec.DOUBLE.fieldOf("density").forGetter(SkyIslandLayout.Settings::density),
                    com.mojang.serialization.Codec.INT.fieldOf("min_radius").forGetter(SkyIslandLayout.Settings::minRadius),
                    com.mojang.serialization.Codec.INT.fieldOf("max_radius").forGetter(SkyIslandLayout.Settings::maxRadius),
                    com.mojang.serialization.Codec.INT.fieldOf("spacing").forGetter(SkyIslandLayout.Settings::spacing)
                )
                .apply(instance, SkyIslandLayout.Settings::new)
        );

        private SettingsCodec() {
        }
    }
}
