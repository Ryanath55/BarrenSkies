package com.barrenskies.mixin;

import com.barrenskies.worldgen.StructureIntent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes which structure is being built, for the second time a structure needs siting.
 *
 * <p>The first note, on Structure.generate, covers the point the structure is placed at. It is not enough
 * on its own: a shipwreck ignores that point entirely and works its height out again from the finished
 * world when its pieces are laid down, and so do ocean ruins and buried treasure. Everything they ask at
 * that stage goes through the world rather than the generator, which is a different call and a different
 * phase, so it needs its own note.
 *
 * <p>Only bookkeeping, like the other one. WorldGenHeightMixin is what reads it.
 */
@Mixin(StructureStart.class)
public abstract class StructurePlaceMixin {
    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void barrenskies$noteStructure(
        WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
        BoundingBox box, ChunkPos chunkPos, CallbackInfo callback
    ) {
        StructureIntent.begin(((StructureStart) (Object) this).getStructure(), level.registryAccess());
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void barrenskies$forgetStructure(
        WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
        BoundingBox box, ChunkPos chunkPos, CallbackInfo callback
    ) {
        StructureIntent.end();
    }
}
