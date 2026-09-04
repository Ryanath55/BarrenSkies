package com.barrenskies.mixin;

import com.barrenskies.worldgen.GroundHeight;
import com.barrenskies.worldgen.StructureIntent;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers how high a column is with the ground alone, while a structure that needs the ground is built.
 *
 * <p>Telling the generator to leave islands out of the height it reports was only half of it, and the
 * missing half is why shipwrecks kept flying after that was fixed. A shipwreck is placed at the point the
 * generator gives it and then, when its pieces are actually laid down, throws that point away: it averages
 * the world height over its own footprint and moves itself there. Ocean ruins and buried treasure do the
 * same. That height comes from the chunk's heightmap, which is the finished world with the islands in it,
 * so the ship was sited on the sea floor and then lifted into the sky.
 *
 * <p>Left alone unless a ground structure is mid-build. Everything in worldgen asks the world how high a
 * column is, and an island's own trees and grass need the true answer.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenHeightMixin implements WorldGenLevel {
    @Inject(
        method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
        at = @At("RETURN"),
        cancellable = true
    )
    private void barrenskies$groundHeight(
        Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> callback
    ) {
        if (!StructureIntent.placingOnGround() || callback.getReturnValueI() <= SkyIslandDensity.islandFloor()) {
            // Nothing asking, or the answer is ground already. Much the commonest case, and it costs a
            // comparison.
            return;
        }
        callback.setReturnValue(GroundHeight.of(
            this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)), type, x, z
        ));
    }
}
