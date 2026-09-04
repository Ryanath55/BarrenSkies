package com.barrenskies.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The one storage member the lower sky's fill pass needs that is declared a class further up than the
 * sky-specific storage: the nibble array for a section, created on demand.
 *
 * <p>Separate from SkyLightStorageAccessor for the same reason LightEngineAccessor is separate from the
 * engine mixin -- an invoker resolves against the class it names and no further.
 */
@Mixin(LayerLightSectionStorage.class)
public interface LayerLightStorageAccessor {
    @Invoker("getDataLayerToWrite")
    DataLayer barrenskies$getDataLayerToWrite(long sectionPos);
}
