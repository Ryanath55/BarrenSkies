package com.barrenskies.mixin;

import com.barrenskies.worldgen.feature.MirroredOres;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the islands' ore throwing itself away for being near an edge.
 *
 * <p>Several ores are written to be buried: diamond discards a vein if any neighbouring block is air, and
 * so do buried lapis and the larger copper. Underground that is a rule about cave walls and it costs
 * almost nothing, because almost nothing down there is near air. An island is nearly all edge -- thin,
 * undercut, and carved through -- so the same rule throws most of the vein away, and what it throws away
 * is the deep ore, which is to say the ore the mirroring exists to move up here.
 *
 * <p>This cannot leave a vein floating, and the guarantee is structural rather than measured. The check
 * being skipped is the second of two in canPlaceOre, and the first is whether the block already there is
 * stone the ore is allowed to replace. Air is not, so a vein reaching into open sky places nothing whether
 * this runs or not. What changes is only that a vein already replacing real stone keeps the part of itself
 * that shows in a cliff face.
 */
@Mixin(OreFeature.class)
public abstract class OreAirExposureMixin {
    @Inject(method = "shouldSkipAirCheck", at = @At("HEAD"), cancellable = true)
    private static void barrenskies$keepEdgeVeins(
        RandomSource random, float chance, CallbackInfoReturnable<Boolean> callback
    ) {
        if (MirroredOres.mirroring()) {
            callback.setReturnValue(Boolean.TRUE);
        }
    }
}
