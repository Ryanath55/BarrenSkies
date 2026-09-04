package com.barrenskies.mixin;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.feature.MirroredOres;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Decides what height an ore vein is given, which is the whole of the ore mirroring.
 *
 * <p>Two changes, and which one applies depends on whose pass is running. On the islands' pass every height
 * is turned upside down and folded into the island band, so a vein the rules meant for bedrock ends up on
 * the highest island and one meant for just under the grass ends up on the lowest. On the ground's own pass
 * a vein aimed above the island floor is dropped instead of placed.
 *
 * <p>Dropping rather than clamping, and only for ore. Clamping would pile everything that overshot into one
 * layer at the floor; dropping loses nothing, because the ground stops well below the island floor and a
 * vein aimed above it was going to land in open air. What it does stop is the handful of ranges that reach
 * that high anyway -- upper coal, upper iron, emerald -- from quietly seeding the islands with a second,
 * unmirrored helping of exactly the ores the mirroring means to keep at the bottom.
 *
 * <p>Ore alone, because everything else placed by height wants the height it asked for. Dungeons are
 * offered the whole world by a range that ends at the top of it, and taking that away would empty the
 * islands of them.
 */
@Mixin(HeightRangePlacement.class)
public abstract class OrePlacementMixin {
    @Inject(method = "getPositions", at = @At("RETURN"), cancellable = true)
    private void barrenskies$mirrorOre(
        PlacementContext context, RandomSource random, BlockPos pos,
        CallbackInfoReturnable<Stream<BlockPos>> callback
    ) {
        if (MirroredOres.mirroring()) {
            callback.setReturnValue(
                callback.getReturnValue()
                    .map(at -> at.atY(MirroredOres.islandY(at.getY(), random)))
                    .filter(at -> at.getY() != MirroredOres.NOWHERE)
            );
            return;
        }
        if (!isOre(context) || BarrenSkiesConfig.ISLAND_ORE_RARITY.get() <= 0) {
            return;
        }
        int floor = SkyIslandDensity.islandFloor();
        callback.setReturnValue(callback.getReturnValue().filter(at -> at.getY() < floor));
    }

    private static boolean isOre(PlacementContext context) {
        return context.topFeature()
            .map(placed -> (Feature<?>) placed.feature().value().feature())
            .filter(feature -> feature instanceof OreFeature || feature instanceof ScatteredOreFeature)
            .isPresent();
    }
}
