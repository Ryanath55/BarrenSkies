package com.barrenskies.mixin;

import com.barrenskies.worldgen.IslandLight;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carries a second copy of a chunk's sky sources, one that has never heard of the islands.
 *
 * <p>Vanilla keeps one number per column: the lowest block still open to the sky. That single number is
 * what the light engine asks when it decides whether a block is a sky source, and one number cannot
 * describe two skies. So this adds a second, filled by the same scan vanilla runs but started at the
 * island floor rather than at the top of the world, which makes it the answer the column would have given
 * if nothing existed above the split.
 *
 * <p>Kept on the vanilla object rather than in a map beside it, because the engine reaches these through
 * the chunk and there is exactly one hook to hand it something. See IslandLight for why the split works
 * at all.
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class SkyLightSourcesMixin implements IslandLight.GroundSky {
    @Unique
    private int[] barrenskies$groundY;

    @Override
    @Unique
    public int barrenskies$groundLowestSourceY(int x, int z) {
        int[] ground = this.barrenskies$groundY;
        return ground == null ? IslandLight.GroundSky.UNSET : ground[(z & 15) * 16 + (x & 15)];
    }

    /**
     * Fills the ground-only copy from the same chunk, ignoring every section at or above the split.
     *
     * <p>Walked here rather than reusing vanilla's own scan, which starts from the highest filled section
     * and is private. It is the same walk: down each column from the split until something occludes the
     * sky, and that block's height is where the lower sky begins.
     */
    @Inject(method = "fillFrom", at = @At("TAIL"))
    private void barrenskies$fillGround(ChunkAccess chunk, CallbackInfo callback) {
        if (!IslandLight.split()) {
            this.barrenskies$groundY = null;
            return;
        }
        int[] ground = this.barrenskies$groundY;
        if (ground == null) {
            ground = new int[256];
            this.barrenskies$groundY = ground;
        }

        int split = SkyIslandDensity.islandFloor();
        int bottom = chunk.getMinBuildHeight() - 1;
        int top = Math.min(split - 1, chunk.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int found = bottom;
                for (int y = top; y > bottom; y--) {
                    if (chunk.getBlockState(pos.set(x, y, z)).getLightBlock(chunk, pos) != 0) {
                        // The first thing that stops the sky. Its own top face is where light stands.
                        found = y + 1;
                        break;
                    }
                }
                ground[z * 16 + x] = found;
            }
        }
    }

    /**
     * Keeps the ground copy honest when a block below the split changes.
     *
     * <p>Only the one column, and only when the change is low enough to matter -- everything an island is
     * made of is above the split and cannot move this.
     */
    @Inject(method = "update", at = @At("TAIL"), cancellable = true)
    private void barrenskies$updateGround(
        BlockGetter level, int x, int y, int z, CallbackInfoReturnable<Boolean> callback
    ) {
        int[] ground = this.barrenskies$groundY;
        if (ground == null || !IslandLight.split()) {
            return;
        }
        int split = SkyIslandDensity.islandFloor();
        if (y >= split) {
            return;
        }

        int index = (z & 15) * 16 + (x & 15);
        int before = ground[index];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = Integer.MIN_VALUE;
        for (int at = split - 1; at > split - 1 - 512 && at > -2048; at--) {
            if (level.getBlockState(pos.set(x, at, z)).getLightBlock(level, pos) != 0) {
                found = at + 1;
                break;
            }
        }
        ground[index] = found;
        if (found != before) {
            // The column's lower sky moved, so the engine has to relight it whatever vanilla concluded.
            callback.setReturnValue(Boolean.TRUE);
        }
    }
}
