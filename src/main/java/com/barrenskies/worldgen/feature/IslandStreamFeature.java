package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.Fluids;

/**
 * A stream cut across an island top, running out to an edge that stands over open sea.
 *
 * <p>The path is worked out rather than searched for. A hash over a fixed grid decides which cells hold a
 * stream and gives each one a starting point and a heading; the line is then walked a block at a time,
 * turning gently as it goes, with the channel deepening and widening along its length. Every quantity that
 * matters -- how far along, how deep, how wide -- is a property of distance travelled, which is why this is
 * a path and not a noise field. A contour of a noise has no along, so it cannot taper, cannot widen towards
 * one end, and cannot slope; it can only be a band of even width, which is what the previous attempt was.
 *
 * <p>Being computed rather than traced is also what keeps it inside one chunk. The whole path is known to
 * every chunk it crosses, so each one writes only the blocks that fall within itself and none outside.
 * Reading across chunks during decoration is fine and writing across it is not, which is the distinction the
 * version before last got wrong, and it is what left trees floating over ground that had been cut away.
 */
public class IslandStreamFeature extends Feature<NoneFeatureConfiguration> {
    /** Grid the stream starts are hashed over. Roughly one candidate per island. */
    private static final int CELL = 192;

    /** How far a stream runs before it gives up, if the island has not already ended under it. */
    private static final int LENGTH = 160;

    /** Air needed above a surface before it counts as open ground rather than the roof of a cave. */
    private static final int OPEN_SKY = 6;

    /** How sharply the path may turn per block. Small, so it snakes rather than wanders. */
    private static final double TURN = 0.10D;

    /** Shorter than this is a notch in a rim, not a stream, so those headings are passed over. */
    private static final int MIN_RUN = 20;

    /** How far inside an island, by the mask, a stream has to start. */
    private static final double INLAND = 0.10D;

    public IslandStreamFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!BarrenSkiesConfig.ISLAND_WATERFALLS.get()) {
            return false;
        }

        WorldGenLevel level = context.level();
        NormalNoise meander = level.getLevel().getChunkSource().randomState()
            .getOrCreateNoise(SkyIslandDensity.ISLAND_STREAMS);

        int minX = context.origin().getX() & ~15;
        int minZ = context.origin().getZ() & ~15;
        boolean carved = false;

        // A stream is at most LENGTH blocks long, so only starts within one cell of this one can reach it.
        int homeX = Math.floorDiv(minX, CELL);
        int homeZ = Math.floorDiv(minZ, CELL);
        for (int cx = homeX - 1; cx <= homeX + 1; cx++) {
            for (int cz = homeZ - 1; cz <= homeZ + 1; cz++) {
                long hash = mix(level.getSeed() ^ (cx * 341873128712L) ^ (cz * 132897987541L));
                if (Math.floorMod(hash, 100L) >= BarrenSkiesConfig.WATERFALL_ISLAND_CHANCE.get()) {
                    continue;
                }
                Plan plan = plan(context, meander, hash, cx, cz, minX, minZ);
                if (plan == null) {
                    continue;
                }
                carved |= walk(level, meander, plan, minX, minZ);
            }
        }
        return carved;
    }

    /** A stream that is known to start on an island and to reach an edge, worked out before anything is cut. */
    private record Plan(int startX, int startZ, double heading, double lane, int run) {
    }

    /**
     * Decides where a stream starts, which way it goes and how far it runs, using no terrain at all.
     *
     * <p>All of it comes from the island mask, which is noise and so answers for any coordinate without a
     * chunk having to exist. That is what makes this possible from a chunk the stream does not start in, and
     * it is worth the trouble because the alternative wastes nearly everything: a start picked blindly in a
     * cell lands on an island about one time in five, and a heading picked blindly runs inland and stops in
     * the middle of one about as often. Choosing both against the mask means a stream that is planned is a
     * stream that runs from island to edge, and the run length it comes back with is what the widening and
     * the deepening are then measured against, so both reach their full extent exactly at the drop.
     */
    private static Plan plan(
        FeaturePlaceContext<NoneFeatureConfiguration> context, NormalNoise meander, long hash,
        int cellX, int cellZ, int minX, int minZ
    ) {
        NormalNoise islands = context.level().getLevel().getChunkSource().randomState()
            .getOrCreateNoise(SkyIslandDensity.ISLANDS);
        int layers = BarrenSkiesConfig.ISLAND_LAYERS.get();
        double threshold = BarrenSkiesConfig.ISLAND_THRESHOLD.get();
        double scale = BarrenSkiesConfig.ISLAND_SCALE.get();

        int startX = 0;
        int startZ = 0;
        boolean onIsland = false;
        for (int attempt = 0; attempt < 8 && !onIsland; attempt++) {
            long pick = mix(hash + attempt * 0x9E3779B97F4A7C15L);
            startX = cellX * CELL + (int) Math.floorMod(pick, CELL);
            startZ = cellZ * CELL + (int) Math.floorMod(pick >> 20, CELL);
            // Well inside, not clinging to a rim: a stream wants somewhere to run from.
            onIsland = SkyIslandDensity.islandStrength(islands, startX, startZ, layers, threshold, scale) >= INLAND;
        }
        if (!onIsland) {
            return null;
        }
        // Nothing this far off can reach the chunk, and working out its heading means walking the mask a
        // hundred and sixty steps for each of eight directions. Cheap to rule out, expensive not to.
        int nearX = Math.max(minX, Math.min(startX, minX + 15));
        int nearZ = Math.max(minZ, Math.min(startZ, minZ + 15));
        long dx = startX - nearX;
        long dz = startZ - nearZ;
        if (dx * dx + dz * dz > (long) (LENGTH + 4) * (LENGTH + 4)) {
            return null;
        }
        if (!overWater(context, startX, startZ)) {
            return null;
        }

        double lane = Math.floorMod(hash >> 12, 4096L);
        double bestHeading = 0.0D;
        int bestRun = -1;
        for (int spoke = 0; spoke < 8; spoke++) {
            double heading = (spoke + Math.floorMod(hash >> 44, 100L) / 100.0D) / 8.0D * Math.PI * 2.0D;
            int run = runLength(meander, islands, startX, startZ, heading, lane, layers, threshold, scale);
            // The longest way out that still finds one. Taking the shortest is what water would really do,
            // and it made every stream a stub off the nearest rim: measured over four thousand blocks the
            // median run was thirty-nine. Preferring the long way took that to a hundred and twenty-four,
            // which is a channel that crosses an island rather than nicking a corner off it.
            if (run >= MIN_RUN && run < LENGTH && (bestRun < 0 || run > bestRun)) {
                bestRun = run;
                bestHeading = heading;
            }
        }
        return bestRun < 0 ? null : new Plan(startX, startZ, bestHeading, lane, bestRun);
    }

    /** How many steps the path takes before the island under it runs out. */
    private static int runLength(
        NormalNoise meander, NormalNoise islands, int startX, int startZ,
        double heading, double lane, int layers, double threshold, double scale
    ) {
        double px = startX + 0.5D;
        double pz = startZ + 0.5D;
        double angle = heading;
        for (int step = 0; step <= LENGTH; step++) {
            angle += meander.getValue(step * 0.28D, lane, 0.0D) * TURN;
            px += Math.sin(angle);
            pz += Math.cos(angle);
            if (SkyIslandDensity.islandStrength(islands, (int) Math.floor(px), (int) Math.floor(pz), layers, threshold, scale) <= 0.0D) {
                return step;
            }
        }
        return LENGTH;
    }

    /**
     * Walks the stream and carves whatever falls inside this chunk.
     *
     * <p>Offsets across the channel are tested for the chunk individually rather than the centre line being
     * tested for all of them. A channel three wide whose middle sits on a chunk boundary would otherwise
     * have one side written by a chunk it does not belong to and the other side written by nobody.
     */
    private static boolean walk(WorldGenLevel level, NormalNoise meander, Plan plan, int minX, int minZ) {
        int maxDepth = BarrenSkiesConfig.STREAM_DEPTH.get();
        int reach = SkyIslandDensity.layerReach();
        int bandBottom = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach;
        int bandTop = BarrenSkiesConfig.SKY_ISLAND_TOP.get() + reach * 2;

        double px = plan.startX() + 0.5D;
        double pz = plan.startZ() + 0.5D;
        double angle = plan.heading();
        boolean carved = false;

        for (int step = 0; step <= plan.run(); step++) {
            angle += meander.getValue(step * 0.28D, plan.lane(), 0.0D) * TURN;
            px += Math.sin(angle);
            pz += Math.cos(angle);

            double along = step / (double) plan.run();
            // Narrow and shallow where it starts, deeper and wider by the time it reaches the edge. Both
            // grow along the path, which together are the slope: a channel that cuts further down as it
            // goes drops its water a step at a time even across ground that is level.
            int half = along < 0.30D ? 0 : (along < 0.65D ? 1 : 2);
            int depth = 2 + (int) Math.round((maxDepth - 2) * along);

            // Across the channel, perpendicular to where it is pointing.
            double sideX = Math.cos(angle);
            double sideZ = -Math.sin(angle);

            for (int offset = -half; offset <= half; offset++) {
                int x = (int) Math.floor(px + sideX * offset);
                int z = (int) Math.floor(pz + sideZ * offset);
                if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) {
                    continue;
                }
                // V shaped: full depth along the middle, one block shallower for each step out.
                int cut = depth - Math.abs(offset);
                if (cut < 2) {
                    continue;
                }
                int surface = islandSurface(level, x, z, bandBottom, bandTop);
                if (surface == Integer.MIN_VALUE) {
                    continue;
                }
                cut(level, x, z, surface, cut);
                carved = true;
            }
        }
        return carved;
    }

    /** The topmost open island surface in a column, or MIN_VALUE if this column has no island in it. */
    private static int islandSurface(WorldGenLevel level, int x, int z, int bandBottom, int bandTop) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int air = 0;
        for (int y = bandTop; y >= bandBottom; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                air++;
                continue;
            }
            if (air >= OPEN_SKY && state.isSolid()) {
                return y;
            }
            air = 0;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Opens one column of the channel and lays water in the bottom of it.
     *
     * <p>One block of water, not two, and only at the floor. The version before this filled the whole width
     * with source blocks, and a source never drains: fourteen blocks of them side by side at an island edge
     * emptied over it all at once and came out as a curtain rather than a stream.
     */
    private static void cut(WorldGenLevel level, int x, int z, int surface, int depth) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bed = surface - depth;
        for (int y = surface; y > bed; y--) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        pos.set(x, bed, z);
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
        level.scheduleTick(pos, Fluids.WATER, 0);

        pos.set(x, bed - 1, z);
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
    }

    /**
     * Whether the sea is under this point.
     *
     * <p>Asked of the biome source directly rather than of the world, because the biome source is noise and
     * answers for any coordinate, while the world can only answer for chunks that exist. That matters here:
     * the chunk deciding whether a stream runs is often not the chunk the stream starts in.
     */
    private static boolean overWater(FeaturePlaceContext<NoneFeatureConfiguration> context, int x, int z) {
        if (!BarrenSkiesConfig.WATERFALLS_ONLY_OVER_WATER.get()) {
            return true;
        }
        Climate.Sampler sampler = context.level().getLevel().getChunkSource().randomState().sampler();
        Holder<Biome> biome = context.chunkGenerator().getBiomeSource().getNoiseBiome(
            QuartPos.fromBlock(x), QuartPos.fromBlock(context.level().getSeaLevel()), QuartPos.fromBlock(z), sampler
        );
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_RIVER);
    }

    private static long mix(long value) {
        long h = value;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
