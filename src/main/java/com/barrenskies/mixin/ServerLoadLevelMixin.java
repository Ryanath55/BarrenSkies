package com.barrenskies.mixin;

import com.barrenskies.worldgen.compat.BiomeSourceOwnership;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The moment biome placement is settled for the run.
 *
 * <p>Deliberately not an event. Blueprint installs its modded biome source from the call site just above
 * this method, after ServerAboutToStart has already been fired, so a listener on that event -- at any
 * priority -- runs too early to see it. The head of the load itself is after every such call site and
 * still before the levels exist, which is where a change to a generator has to happen: the structure
 * placement state is built from the biome source as each level is created.
 */
@Mixin(MinecraftServer.class)
public class ServerLoadLevelMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void barrenskies$reclaimBiomePlacement(CallbackInfo callback) {
        BiomeSourceOwnership.reclaim((MinecraftServer) (Object) this);
    }
}
