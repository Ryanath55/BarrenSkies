package com.barrenskies.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The two members the lower sky's fill pass needs that live on the superclass rather than on
 * SkyLightEngine itself.
 *
 * <p>Shadowing them from the SkyLightEngine mixin does not work: mixin resolves shadows against the
 * target class, and both of these are declared on LightEngine above it. The failure is loud and at
 * startup, which is the right kind, but the fix is to ask the class that actually declares them.
 */
@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    /** Queues a block whose light has gone up, so the flood carries on from it. */
    @Invoker("enqueueIncrease")
    void barrenskies$enqueueIncrease(long packedPos, long queueEntry);

    /** Where the light values themselves are kept, a nibble a block. */
    @Accessor("storage")
    LayerLightSectionStorage<?> barrenskies$storage();
}
