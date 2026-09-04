package com.barrenskies.mixin;

import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The storage internals the two passes need, which are protected and in another package.
 *
 * <p>Invokers rather than reflection or an access transformer: they break at build time if a signature
 * moves, which is the only kind of breakage worth having.
 */
@Mixin(SkyLightSectionStorage.class)
public interface SkyLightStorageAccessor {
    @Invoker("hasLightDataAtOrBelow")
    boolean barrenskies$hasLightDataAtOrBelow(int sectionY);

    @Invoker("isAboveData")
    boolean barrenskies$isAboveData(long sectionPos);

    @Invoker("getBottomSectionY")
    int barrenskies$getBottomSectionY();
}
