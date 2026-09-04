package com.barrenskies.worldgen.compat;

import com.barrenskies.worldgen.LayeredBiomeSource;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Puts the layer rules back on top of a biome source another mod has wrapped around ours.
 *
 * <p>Blueprint's modded biome slices -- the system Team Abnormals' biomes are placed with -- swap the
 * chunk generator's biome source for one of their own that wraps whatever was there. Inside a slice the
 * wrapper answers from its own copy of the vanilla climate space, so {@link LayeredBiomeSource} is never
 * asked and none of its rules apply: that is a rainforest on the barren surface. Rather than fight for
 * the slot, this sits outside the whole arrangement and reconciles the answer, which leaves the other
 * mod's placement intact everywhere the rules have nothing to say about it.
 *
 * <p>Sitting outermost is the only position that works. Whoever wraps last wins the lookup, and the
 * mod doing the wrapping is not obliged to consult anyone, so being the last word is the only way to
 * hold a promise about which biomes reach the surface.
 */
public class ReconciledBiomeSource extends BiomeSource {
    public static final MapCodec<ReconciledBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                BiomeSource.CODEC.fieldOf("delegate").forGetter(source -> source.delegate),
                // No fields of its own: the layered source is rebuilt from the registry, the same way it
                // is when it appears as a dimension's biome source in its own right.
                LayeredBiomeSource.CODEC.forGetter(source -> source.rules)
            )
            .apply(instance, ReconciledBiomeSource::new)
    );

    private final BiomeSource delegate;
    private final LayeredBiomeSource rules;

    public ReconciledBiomeSource(BiomeSource delegate, LayeredBiomeSource rules) {
        this.delegate = delegate;
        this.rules = rules;
    }

    /** The source this was wrapped around, which is what gets written back out when the world is saved. */
    public BiomeSource delegate() {
        return this.delegate;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // Every answer is either the delegate's own or a substitute from the layered pools, so the two
        // sets together are exactly what can come out. Feature ordering is built from this, and listing
        // too few is what leaves a biome's features missing rather than merely misplaced.
        return Stream.concat(this.delegate.possibleBiomes().stream(), this.rules.possibleBiomes().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        return this.rules.reconcile(this.delegate.getNoiseBiome(x, y, z, sampler), x, y, z, sampler);
    }

    @Override
    public void addDebugInfo(List<String> info, BlockPos pos, Climate.Sampler sampler) {
        this.delegate.addDebugInfo(info, pos, sampler);
        this.rules.addDebugInfo(info, pos, sampler);
    }
}
