package com.barrenskies.mixin;

import com.barrenskies.worldgen.IslandLight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Asks the column where its sky starts twice, and answers with whichever sky the block belongs to.
 *
 * <p>The engine decides everything about sky light from one comparison -- is this block at or above the
 * lowest block in its column that can still see the sky -- and it reads that height from a single number
 * per column. One number cannot hold two skies, so the number stays as it is and the reads are split
 * instead: a block below the island floor is answered from the ground-only copy that SkyLightSourcesMixin
 * carries, and everything at or above it gets vanilla's own answer, islands and all.
 *
 * <p>Redirects rather than local capture throughout. Every fact needed is in the arguments of the call
 * being replaced or of the method containing it, which are signatures that will break loudly if they ever
 * change, rather than positions in a stack frame that would break silently.
 */
@Mixin(SkyLightEngine.class)
public abstract class SkyLightEngineMixin {
    @Shadow
    @org.jetbrains.annotations.Nullable
    private ChunkSkyLightSources getChunkSources(int chunkX, int chunkZ) {
        throw new AssertionError();
    }

    /**
     * The running case: one block has changed and the engine is asking which sky lit it.
     */
    @Redirect(
        method = "checkNode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/SkyLightEngine;getLowestSourceY(III)I"
        )
    )
    private int barrenskies$splitOnCheck(SkyLightEngine self, int x, int z, int fallback, long levelPos) {
        ChunkSkyLightSources sources =
            this.getChunkSources(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        int vanilla = sources == null
            ? fallback
            : sources.getLowestSourceY(SectionPos.sectionRelative(x), SectionPos.sectionRelative(z));
        return IslandLight.lowestSourceY(sources, x, z, BlockPos.getY(levelPos), vanilla);
    }

    /**
     * Notes which section is being filled, so the column question below can be answered for the right sky.
     */
    @Redirect(
        method = "propagateLightSources",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(III)J")
    )
    private long barrenskies$noteSection(int sectionX, int sectionY, int sectionZ) {
        IslandLight.fillingSection(SectionPos.sectionToBlockCoord(sectionY) + 15);
        return SectionPos.asLong(sectionX, sectionY, sectionZ);
    }

    /**
     * The first-light case, asked once per column per section, for the section noted just above.
     */
    @Redirect(
        method = "propagateLightSources",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;getLowestSourceY(II)I"
        )
    )
    private int barrenskies$splitOnFill(ChunkSkyLightSources sources, int x, int z, ChunkPos chunkPos) {
        int vanilla = sources.getLowestSourceY(x, z);
        return IslandLight.lowestSourceY(sources, x, z, IslandLight.fillingSection(), vanilla);
    }

    /**
     * The lower sky's own first-light pass, run after vanilla has finished the upper one.
     *
     * <p>This is the hook everything else was missing, and the reason five correct hooks added up to no
     * effect at all. Vanilla fills a chunk by walking down it a section at a time and stops the moment a
     * section holds no column whose sky starts below it -- {@code if (!flag) break;}. Under an island the
     * topmost section holding the island's own surface is exactly such a section, so the walk stops there.
     * Which is right for vanilla: below an island there are no sources. It means the redirect above, the
     * one that would have answered for the ground, sits in a branch the loop has already left. The ground
     * sections are never written at all, which is why the empty gap read fifteen -- nothing stored, so the
     * read fell through to open sky -- while the surface under the island read nought.
     *
     * <p>So the lower sky is filled here instead of being smuggled into the upper pass, which is what two
     * passes meant in the first place. The walk below is vanilla's own with two changes: it starts at the
     * split rather than at the top of the world, and every height it reads comes from the ground-only
     * heightmap. It cannot reach above the split, so it can neither light an island's caves nor disturb
     * what the first pass has just written.
     */
    @Inject(method = "propagateLightSources", at = @At("TAIL"))
    private void barrenskies$lowerSky(ChunkPos chunkPos, CallbackInfo callback) {
        if (!IslandLight.split()) {
            return;
        }
        if (!(this.getChunkSources(chunkPos.x, chunkPos.z) instanceof IslandLight.GroundSky ground)) {
            return;
        }
        IslandLight.GroundSky north = this.barrenskies$groundSky(chunkPos.x, chunkPos.z - 1);
        IslandLight.GroundSky south = this.barrenskies$groundSky(chunkPos.x, chunkPos.z + 1);
        IslandLight.GroundSky west = this.barrenskies$groundSky(chunkPos.x - 1, chunkPos.z);
        IslandLight.GroundSky east = this.barrenskies$groundSky(chunkPos.x + 1, chunkPos.z);

        int split = IslandLight.splitAt();
        int baseX = SectionPos.sectionToBlockCoord(chunkPos.x);
        int baseZ = SectionPos.sectionToBlockCoord(chunkPos.z);
        Object storage = ((LightEngineAccessor) this).barrenskies$storage();
        int bottomSection = ((SkyLightStorageAccessor) storage).barrenskies$getBottomSectionY();
        LayerLightStorageAccessor store = (LayerLightStorageAccessor) storage;

        for (int sectionY = SectionPos.blockToSectionCoord(split - 1); sectionY >= bottomSection; sectionY--) {
            DataLayer layer =
                store.barrenskies$getDataLayerToWrite(SectionPos.asLong(chunkPos.x, sectionY, chunkPos.z));
            if (layer == null) {
                continue;
            }
            int sectionBottom = SectionPos.sectionToBlockCoord(sectionY);
            // Never above the split, even for the section the split itself falls inside of.
            int sectionTop = Math.min(sectionBottom + 15, split - 1);
            boolean more = false;

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int lowest = ground.barrenskies$groundLowestSourceY(x, z);
                    if (lowest == IslandLight.GroundSky.UNSET || lowest > sectionTop) {
                        continue;
                    }
                    int toNorth = z == 0
                        ? barrenskies$sourceIn(north, x, 15) : ground.barrenskies$groundLowestSourceY(x, z - 1);
                    int toSouth = z == 15
                        ? barrenskies$sourceIn(south, x, 0) : ground.barrenskies$groundLowestSourceY(x, z + 1);
                    int toWest = x == 0
                        ? barrenskies$sourceIn(west, 15, z) : ground.barrenskies$groundLowestSourceY(x - 1, z);
                    int toEast = x == 15
                        ? barrenskies$sourceIn(east, 0, z) : ground.barrenskies$groundLowestSourceY(x + 1, z);
                    int tallestNeighbour = Math.max(Math.max(toNorth, toSouth), Math.max(toWest, toEast));

                    for (int y = sectionTop; y >= Math.max(sectionBottom, lowest); y--) {
                        layer.set(x, SectionPos.sectionRelative(y), z, 15);
                        // Only blocks with somewhere to spread to are worth queueing: the source itself,
                        // and anything standing lower than a neighbouring column's own source.
                        if (y == lowest || y < tallestNeighbour) {
                            ((LightEngineAccessor) this).barrenskies$enqueueIncrease(
                                BlockPos.asLong(baseX + x, y, baseZ + z),
                                LightEngine.QueueEntry.increaseSkySourceInDirections(
                                    y == lowest, y < toNorth, y < toSouth, y < toWest, y < toEast));
                        }
                    }
                    if (lowest < sectionBottom) {
                        more = true;
                    }
                }
            }
            if (!more) {
                break;
            }
        }
    }

    @Unique
    private IslandLight.GroundSky barrenskies$groundSky(int chunkX, int chunkZ) {
        return this.getChunkSources(chunkX, chunkZ) instanceof IslandLight.GroundSky sky ? sky : null;
    }

    /**
     * A neighbouring column's lower sky, or a height nothing can stand below when that chunk is not
     * loaded -- which leaves the edge unqueued rather than guessing at it, as vanilla's own empty chunk
     * sources do.
     */
    @Unique
    private static int barrenskies$sourceIn(IslandLight.GroundSky sky, int x, int z) {
        if (sky == null) {
            return Integer.MAX_VALUE;
        }
        int found = sky.barrenskies$groundLowestSourceY(x, z);
        return found == IslandLight.GroundSky.UNSET ? Integer.MAX_VALUE : found;
    }

    /**
     * A column's neighbours, asked so a change knows how far up it has to be pushed.
     */
    @Redirect(
        method = "addSourcesAbove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/SkyLightEngine;getLowestSourceY(III)I"
        )
    )
    private int barrenskies$splitOnNeighbours(
        SkyLightEngine self, int x, int z, int fallback, int columnX, int columnZ, int maxY, int bottomSectionY
    ) {
        ChunkSkyLightSources sources =
            this.getChunkSources(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        int vanilla = sources == null
            ? fallback
            : sources.getLowestSourceY(SectionPos.sectionRelative(x), SectionPos.sectionRelative(z));
        return IslandLight.lowestSourceY(sources, x, z, maxY, vanilla);
    }

    /**
     * Stops the upper sky wiping the lower one when it rewrites a column.
     *
     * <p>checkNode does not only test the block it was asked about; it rewrites the whole column's sources
     * from the same number, removing everything below and adding everything above. Left alone, a check on
     * a block inside an island removes the ground's sky all the way to bedrock, and a check on a block at
     * ground level puts it back -- so the two skies take turns clobbering each other and, because island
     * blocks are checked far more often, the island always wins.
     */
    @Redirect(
        method = "removeSourcesBelow",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;hasLightDataAtOrBelow(I)Z"
        )
    )
    private boolean barrenskies$removeStopsAtSplit(
        SkyLightSectionStorage storage, int sectionY, int x, int z, int minY, int bottomSectionY
    ) {
        if (IslandLight.split() && minY >= IslandLight.splitAt()
            && SectionPos.sectionToBlockCoord(sectionY) + 15 < IslandLight.splitAt()) {
            return false;
        }
        return ((SkyLightStorageAccessor) storage).barrenskies$hasLightDataAtOrBelow(sectionY);
    }

    /**
     * Stops the lower sky climbing into the islands when it rewrites a column.
     *
     * <p>The mirror of the above and just as necessary. Adding sources upward from the ground surface would
     * not stop at the island's underside -- it would set full sky on the island's rock and, worse, on the
     * air inside its caves, which is the same breakage in the other direction.
     */
    @Redirect(
        method = "addSourcesAbove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;isAboveData(J)Z"
        )
    )
    private boolean barrenskies$addStopsAtSplit(
        SkyLightSectionStorage storage, long sectionPos, int x, int z, int maxY, int bottomSectionY
    ) {
        if (IslandLight.split() && maxY < IslandLight.splitAt()
            && SectionPos.sectionToBlockCoord(SectionPos.y(sectionPos)) >= IslandLight.splitAt()) {
            return true;
        }
        return ((SkyLightStorageAccessor) storage).barrenskies$isAboveData(sectionPos);
    }

    /**
     * Makes the split opaque to light itself, in both directions.
     *
     * <p>Sky light does not only come from sources; it floods, and a flood does not care which sky it
     * started in. The islands' darkness spreads downward out of the shade beneath them, block by block,
     * straight through the split and into ground the lower sky had already lit -- and where it arrives
     * first it writes nought over the fifteen. So no propagation step may cross the split: a block below
     * it and a block above it are no longer neighbours as far as sky light is concerned, which is what
     * having two skies means and what every other hook here was only approximating.
     */
    @Redirect(
        method = { "propagateIncrease", "propagateDecrease" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;"
                + "shouldPropagateInDirection(JLnet/minecraft/core/Direction;)Z"
        )
    )
    private boolean barrenskies$noCrossingTheSplit(long queueEntry, Direction direction, long packedPos) {
        if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, direction)) {
            return false;
        }
        if (!IslandLight.split() || direction.getStepY() == 0) {
            return true;
        }
        int split = IslandLight.splitAt();
        int from = BlockPos.getY(packedPos);
        return from < split == from + direction.getStepY() < split;
    }
}
