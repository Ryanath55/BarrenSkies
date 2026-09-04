package com.barrenskies.mixin;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to the generator's biome source.
 *
 * <p>The field is final and there is no setter, but replacing it is how a mod takes over biome placement
 * for a dimension -- Blueprint does exactly this for its modded biome slices. Reaching the same field is
 * what lets the layer rules be reapplied over the top of whatever went in.
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    @Mutable
    @Accessor("biomeSource")
    void barrenskies$setBiomeSource(BiomeSource source);
}
