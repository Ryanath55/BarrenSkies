package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

/**
 * A spring on the rim of a sky island that pours over the edge.
 *
 * <p>Streams Reflowing needs a fluid of its own because a river runs horizontally and vanilla water gives
 * up after seven blocks. A waterfall is the easy half of that problem: falling water has no such limit, so
 * this is built from vanilla water and inherits its physics. Nothing here needs maintaining afterwards, and
 * the water finds the sea on its own.
 *
 * <p>The shape is a small basin cut into the island top with a notch through the rim. The basin is filled
 * with source blocks, which never drain, so the notch spills for as long as the island exists. The water
 * sits flush with the surrounding ground: neighbouring surface blocks are solid at that height and hold it
 * in, and the notch is the one way out.
 */
public class IslandWaterfallFeature extends Feature<NoneFeatureConfiguration> {
    /** How far out to look for the edge of the island. Kept small so it stays inside the writable area. */
    private static final int EDGE_SEARCH = 7;

    /** How far the ground has to fall away before it counts as a rim rather than a dip in the surface. */
    private static final int MIN_DROP = 12;

    /** Radius of the basin, in blocks. Two gives a pool about five across. */
    private static final int BASIN_RADIUS = 2;

    /** How far down to look for an island surface from the height the placement picked. */
    private static final int SURFACE_SEARCH = 96;

    /**
     * How much of the fall to draw.
     *
     * <p>Only enough to start it. Falling water carries itself downwards for as long as there is air below,
     * so the game runs the other four or five hundred blocks to the sea without help, and writing them all
     * at generation would be a great many blocks and fluid ticks for no difference in the result.
     */
    private static final int FALL_STARTER = 24;

    public IslandWaterfallFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!BarrenSkiesConfig.ISLAND_WATERFALLS.get()) {
            return false;
        }

        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();

        // Only on the islands. Below the floor is the barren ground, and a spring pouring into a desert is
        // not what this is for.
        if (origin.getY() < BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - SkyIslandDensity.layerReach()) {
            return false;
        }

        int waterY = findIslandTop(level, origin);
        if (waterY == Integer.MIN_VALUE) {
            return false;
        }

        Direction outward = null;
        int reach = -1;
        // Start the search at a random direction. Scanning north first and keeping the strictly nearest
        // edge sent every waterfall on a symmetrical rim north, which was plain once they were plotted.
        int first = random.nextInt(4);
        for (int i = 0; i < 4; i++) {
            Direction candidate = Direction.from2DDataValue((first + i) % 4);
            int distance = rimDistance(level, origin.getX(), waterY, origin.getZ(), candidate);
            if (distance >= 0 && (outward == null || distance < reach)) {
                outward = candidate;
                reach = distance;
            }
        }
        if (outward == null) {
            return false;
        }

        // Thinned here rather than before the search. Applied up front it would be a gate on spots looked
        // at, nearly all of which are open sky and would have been turned down anyway, so the number in
        // the config would have had no honest relationship to how many waterfalls appeared. Applied here
        // it means what it says: one usable rim in this many gets a spring.
        if (random.nextInt(BarrenSkiesConfig.WATERFALL_RARITY.get()) != 0) {
            return false;
        }

        carveBasin(level, origin.getX(), waterY, origin.getZ(), random);
        carveSpillway(level, origin.getX(), waterY, origin.getZ(), outward, reach);
        containPool(level, origin.getX(), waterY, origin.getZ(), outward, reach);
        pourOver(level, origin.getX(), waterY, origin.getZ(), outward, reach);
        return true;
    }

    /**
     * The top solid block of the island surface below the given point.
     *
     * <p>Deliberately not the world surface heightmap. That describes the whole column, so on a stack of
     * islands it always returns the topmost one, and every waterfall ended up on the highest island in the
     * sky. The lower ones are the ones worth having water on, since a fall is something a player can swim
     * up before they can fly.
     */
    private static int findIslandTop(WorldGenLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(origin.getX(), origin.getY(), origin.getZ());
        if (!level.getBlockState(pos).isAir()) {
            // Started inside rock. Another attempt elsewhere will do better than digging out of it.
            return Integer.MIN_VALUE;
        }
        for (int step = 0; step < SURFACE_SEARCH; step++) {
            pos.move(Direction.DOWN);
            if (pos.getY() <= level.getMinBuildHeight()) {
                return Integer.MIN_VALUE;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            // Anything that is not plain rock -- water already here, the odd lip of a cave mouth -- is not
            // somewhere to put a spring.
            return state.isSolid() ? pos.getY() : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Blocks from the pool to the last solid ground before the drop, or -1 if there is no usable edge.
     *
     * <p>Walks outward at the pool's own height rather than comparing heightmaps, for the same reason: under
     * a higher island a heightmap describes that island, so nothing below the top of a stack could ever
     * find its own edge.
     */
    private static int rimDistance(WorldGenLevel level, int x, int waterY, int z, Direction direction) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int step = BASIN_RADIUS + 1; step <= EDGE_SEARCH; step++) {
            pos.set(x + direction.getStepX() * step, waterY, z + direction.getStepZ() * step);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return isSheerBelow(level, pos) ? step - 1 : -1;
            }
            if (!state.isSolid()) {
                return -1;
            }
            // Ground standing above the pool would dam the spillway before it ever reached the edge.
            if (!level.getBlockState(pos.above()).isAir()) {
                return -1;
            }
        }
        return -1;
    }

    /** Whether the air at the edge keeps going down, rather than being a one block step or a ledge. */
    private static boolean isSheerBelow(WorldGenLevel level, BlockPos edge) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(edge.getX(), edge.getY(), edge.getZ());
        for (int drop = 0; drop < MIN_DROP; drop++) {
            pos.move(Direction.DOWN);
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }

    /** A shallow pool cut into the island top, its edge left ragged so it does not read as a dug square. */
    private static void carveBasin(WorldGenLevel level, int x, int waterY, int z, RandomSource random) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int edge = BASIN_RADIUS * BASIN_RADIUS;
        for (int dx = -BASIN_RADIUS; dx <= BASIN_RADIUS; dx++) {
            for (int dz = -BASIN_RADIUS; dz <= BASIN_RADIUS; dz++) {
                int distance = dx * dx + dz * dz;
                if (distance > edge || (distance == edge && random.nextBoolean())) {
                    continue;
                }
                pos.set(x + dx, waterY, z + dz);
                if (level.getBlockState(pos).isSolid()) {
                    fill(level, pos, Blocks.WATER.defaultBlockState());
                }
            }
        }
    }

    /** The notch through the rim. One block wide, so the fall reads as a spout rather than a sheet. */
    private static void carveSpillway(WorldGenLevel level, int x, int waterY, int z, Direction outward, int reach) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int step = 1; step <= reach; step++) {
            pos.set(x + outward.getStepX() * step, waterY, z + outward.getStepZ() * step);
            fill(level, pos, Blocks.WATER.defaultBlockState());
        }
    }

    /**
     * Walls and floors the pool against everything except the spillway.
     *
     * <p>Without this the pool does not stay a pool. It sits flush with the island top, and the landform
     * noise moves that surface by several blocks from one column to the next, so a pool cut into a rise
     * simply runs off down every side that happens to be lower and floods the island instead of feeding the
     * fall. Any gap around or under the water is plugged with whatever the ground beneath it is, so the
     * repair takes the local stone or sand rather than announcing itself.
     */
    private static void containPool(WorldGenLevel level, int x, int waterY, int z, Direction outward, int reach) {
        int limit = BASIN_RADIUS + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -limit; dx <= limit; dx++) {
            for (int dz = -limit; dz <= limit; dz++) {
                pos.set(x + dx, waterY, z + dz);
                if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                    plugFloor(level, pos);
                    continue;
                }
                // The one way out. Everything from the pool to the lip stays open.
                if (onSpillway(dx, dz, outward, reach)) {
                    continue;
                }
                if (touchesWater(level, pos)) {
                    level.setBlock(pos, groundNear(level, pos), 2);
                }
            }
        }
    }

    private static boolean onSpillway(int dx, int dz, Direction outward, int reach) {
        for (int step = 1; step <= reach + 1; step++) {
            if (dx == outward.getStepX() * step && dz == outward.getStepZ() * step) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesWater(WorldGenLevel level, BlockPos pos) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(pos.relative(side)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Water with nothing under it drains straight through the island, so give it a bed. */
    private static void plugFloor(WorldGenLevel level, BlockPos water) {
        BlockPos below = water.below();
        if (level.getBlockState(below).isAir()) {
            level.setBlock(below, groundNear(level, below), 2);
        }
    }

    /** Whatever the island is made of hereabouts, so a patch does not stand out against it. */
    private static BlockState groundNear(WorldGenLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.isSolid()) {
            return below;
        }
        BlockState above = level.getBlockState(pos.above());
        return above.isSolid() ? above : Blocks.STONE.defaultBlockState();
    }

    /**
     * Starts the fall just past the lip.
     *
     * <p>Falling water rather than a source: a source would spread across the first ledge it met and flood
     * it. Landing in the sea or a river needs no handling of its own, because falling water stops at the
     * first thing it meets, and that is exactly where the fall should end.
     */
    private static void pourOver(WorldGenLevel level, int x, int waterY, int z, Direction outward, int reach) {
        BlockState falling = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(
            x + outward.getStepX() * (reach + 1), waterY, z + outward.getStepZ() * (reach + 1)
        );
        for (int drop = 0; drop < FALL_STARTER && pos.getY() > level.getMinBuildHeight(); drop++) {
            if (!level.getBlockState(pos).isAir()) {
                return;
            }
            fill(level, pos, falling);
            pos.move(Direction.DOWN);
        }
    }

    /** Places water and clears whatever sat on top of it, so the pool is open to the sky. */
    private static void fill(WorldGenLevel level, BlockPos pos, BlockState water) {
        level.setBlock(pos, water, 2);
        level.scheduleTick(pos, Fluids.WATER, 0);
        BlockPos above = pos.above();
        if (!level.getBlockState(above).isAir()) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), 2);
        }
    }
}
