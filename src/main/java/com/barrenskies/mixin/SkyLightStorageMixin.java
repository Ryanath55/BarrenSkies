package com.barrenskies.mixin;

import com.barrenskies.worldgen.IslandLight;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({SkyLightSectionStorage.class})
public abstract class SkyLightStorageMixin {
   @Redirect(
      method = {"getLightValue(JZ)I"},
      at = @At(
         value = "INVOKE",
         target = "Lit/unimi/dsi/fastutil/longs/Long2IntOpenHashMap;get(J)I"
      )
   )
   private int barrenskies$stopScanAtSplit(Long2IntOpenHashMap topSections, long zeroNode, long packedPos, boolean updateAll) {
      int top = topSections.get(zeroNode);
      if (IslandLight.split() && BlockPos.getY(packedPos) < IslandLight.splitAt()) {
         int splitSection = SectionPos.blockToSectionCoord(IslandLight.splitAt()) + 1;
         return Math.min(top, splitSection);
      } else {
         return top;
      }
   }
}
