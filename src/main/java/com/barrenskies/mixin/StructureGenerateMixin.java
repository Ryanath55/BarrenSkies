package com.barrenskies.mixin;

import com.barrenskies.worldgen.StructureIntent;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which structure is being placed, so the generator can answer "where is the surface" for the thing
 * actually asking rather than for everything at once.
 *
 * <p>Only bookkeeping: it takes no decision and changes no behaviour on its own. See StructureIntent for
 * what the note is for and why it is a thread local rather than a parameter.
 */
@Mixin(Structure.class)
public abstract class StructureGenerateMixin {
    @Inject(method = "generate", at = @At("HEAD"))
    private void barrenskies$noteStructure(
        RegistryAccess registries, ChunkGenerator generator, BiomeSource biomeSource, RandomState randomState,
        StructureTemplateManager templates, long seed, ChunkPos chunkPos, int references,
        LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome,
        CallbackInfoReturnable<StructureStart> callback
    ) {
        StructureIntent.begin((Structure) (Object) this, registries);
    }

    @Inject(method = "generate", at = @At("RETURN"))
    private void barrenskies$forgetStructure(
        RegistryAccess registries, ChunkGenerator generator, BiomeSource biomeSource, RandomState randomState,
        StructureTemplateManager templates, long seed, ChunkPos chunkPos, int references,
        LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome,
        CallbackInfoReturnable<StructureStart> callback
    ) {
        StructureIntent.end();
    }
}
