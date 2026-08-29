package com.barrenskies.mixin;

import com.barrenskies.BarrenSkiesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Optionally removes the way biome temperature falls with height.
 *
 * <p>Minecraft subtracts about 0.00125 of temperature per block above Y 80. At sky island altitude that is
 * enough to push every biome below freezing, so jungles and savannas alike end up snow covered purely
 * because of where they sit. With this off, an island keeps the climate of the biome it actually is.
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
    @Inject(method = "getHeightAdjustedTemperature", at = @At("HEAD"), cancellable = true)
    private void barrenskies$skipAltitudeCooling(BlockPos pos, CallbackInfoReturnable<Float> callback) {
        if (!BarrenSkiesConfig.ALTITUDE_COOLING.get()) {
            callback.setReturnValue(((Biome) (Object) this).getBaseTemperature());
        }
    }
}
