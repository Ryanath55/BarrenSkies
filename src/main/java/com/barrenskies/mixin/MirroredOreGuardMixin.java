package com.barrenskies.mixin;

import com.barrenskies.worldgen.feature.MirroredOres;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the islands' ore pass off the ground.
 *
 * <p>The heights are moved by OrePlacementMixin, which can only move the heights it is asked about: an ore
 * placed by something other than a height range would keep the height the ground gave it and be placed a
 * second time in the ground itself, doubling exactly the ore the mirroring is supposed to be moving. No
 * ore in the game is written that way and none in Terralith is, but nothing guarantees it of the next mod
 * along, and the failure would look like ordinary generosity rather than like a bug.
 *
 * <p>So the invariant is stated where it can be enforced instead of assumed: on the islands' pass, nothing
 * is placed below the island floor.
 */
@Mixin(ConfiguredFeature.class)
public abstract class MirroredOreGuardMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void barrenskies$keepOffTheGround(
        WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (MirroredOres.mirroring() && pos.getY() < SkyIslandDensity.islandFloor()) {
            callback.setReturnValue(false);
        }
    }
}
