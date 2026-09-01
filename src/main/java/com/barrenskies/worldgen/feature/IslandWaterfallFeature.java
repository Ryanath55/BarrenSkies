package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

/**
 * A stream that cuts down an island's top and cascades over the rim.
 *
 * <p>The first version of this was a spring beside a cliff and a single column of water falling out of it,
 * which read as plumbing rather than as landscape. What makes the streams in Streams Reflowing look like
 * water is that they are cut *into* the ground: a channel with banks standing over it, dropping in steps,
 * with a pool at each step spilling into the next. So this traces the downhill line across the island top,
 * carves a gully along it, and fills each step with its own source block. The fall off the rim is the end
 * of a stream rather than the whole of it.
 *
 * <p>Vanilla water does all the moving. Falling water has no horizontal limit to work around -- that is why
 * Streams Reflowing carries a fluid of its own and this does not -- so the fall is started and left alone.
 */
public class IslandWaterfallFeature extends Feature<NoneFeatureConfiguration> {
    /** How far the stream runs across the island top before it reaches the rim. */
    private static final int CHANNEL_LENGTH = 10;

    /** How far the ground must fall away past the lip for it to be a rim rather than a step. */
    private static final int MIN_DROP = 14;

    /** How far a step in the channel may drop before the trace treats it as the edge. */
    private static final int EDGE_SEARCH = 8;

    /** Vertical slack when looking for the surface of the next column along. */
    private static final int SURFACE_SLACK = 8;

    /** How far down to look for an island surface from the height the placement picked. */
    private static final int SURFACE_SEARCH = 96;

    /**
     * How much of the fall to draw. Only enough to start it: falling water carries itself down for as long
     * as there is air below, so the game runs the remaining hundreds of blocks to the sea by itself.
     */
    private static final int FALL_STARTER = 24;

    /** Size of the region that counts as one island for deciding whether it gets water at all. */
    private static final int ISLAND_CELL = 320;

    /** Rims per island-sized cell that pass the terrain and water tests. Measured, and used to thin down. */
    private static final int QUALIFYING_RIMS_PER_CELL = 8;

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

        if (origin.getY() < BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - SkyIslandDensity.layerReach()) {
            return false;
        }
        if (!islandTakesWater(level, origin, random)) {
            return false;
        }

        int surfaceY = findIslandTop(level, origin);
        if (surfaceY == Integer.MIN_VALUE) {
            return false;
        }

        // Trace the downhill line in each direction and keep the one that actually reaches a rim, starting
        // from a random direction so a symmetrical island does not always drain the same way.
        int first = random.nextInt(4);
        for (int i = 0; i < 4; i++) {
            Direction outward = Direction.from2DDataValue((first + i) % 4);
            int reach = rimDistance(level, origin.getX(), surfaceY, origin.getZ(), outward);
            if (reach < 0) {
                continue;
            }
            int[] lip = new int[] {origin.getX() + outward.getStepX() * reach, origin.getZ() + outward.getStepZ() * reach, surfaceY};
            int fallX = lip[0] + outward.getStepX();
            int fallZ = lip[1] + outward.getStepZ();
            // The whole point of a fall is where it ends up. Over the barren surface it would be a stream
            // of fresh water poured into a desert, so those rims are left dry.
            if (!landsInWater(level, fallX, fallZ)) {
                continue;
            }
            List<int[]> channel = buildChannel(level, origin.getX(), surfaceY, origin.getZ(), outward, reach, random);
            if (channel == null) {
                continue;
            }

            int[] end = channel.get(channel.size() - 1);
            carveChannel(level, channel, outward, random);
            pourOver(level, end[0] + outward.getStepX(), end[2] - 1, end[1] + outward.getStepZ(), outward);
            return true;
        }
        return false;
    }

    /**
     * Whether this stretch of sky is one of the islands that gets water, and whether this chunk is one of
     * its streams.
     *
     * <p>Density is decided per island rather than per chunk. Rolling the dice in every chunk gave a steady
     * scattering everywhere, so every island had a few and none had none, which is the opposite of what
     * makes them worth finding. Hashing a fixed grid of island-sized cells means most of the sky is simply
     * dry, and the cells that are not have a couple of streams at positions the hash fixes in advance.
     */
    private static boolean islandTakesWater(WorldGenLevel level, BlockPos origin, RandomSource random) {
        int cellX = Math.floorDiv(origin.getX(), ISLAND_CELL);
        int cellZ = Math.floorDiv(origin.getZ(), ISLAND_CELL);
        long hash = mix(level.getSeed() ^ (cellX * 341873128712L) ^ (cellZ * 132897987541L));

        if (Math.floorMod(hash, 100L) >= BarrenSkiesConfig.WATERFALL_ISLAND_CHANCE.get()) {
            return false;
        }

        // Thinned against how many rims in a cell actually qualify. Naming two chunks in the cell up front
        // and only building there does not work: a cell is four hundred chunks and only a handful of them
        // have a rim that runs downhill over open sea, so the odds of naming one of those are tiny and the
        // first attempt at this produced nothing at all. Eight per cell survive the terrain and water
        // tests -- measured, not guessed -- so thinning by eight over the number wanted lands near it.
        int thin = Math.max(1, QUALIFYING_RIMS_PER_CELL / BarrenSkiesConfig.WATERFALLS_PER_ISLAND.get());
        return random.nextInt(thin) == 0;
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

    /** Only over open water. Checked at sea level, where the barren surface and the sea part company. */
    private static boolean landsInWater(WorldGenLevel level, int x, int z) {
        if (!BarrenSkiesConfig.WATERFALLS_ONLY_OVER_WATER.get()) {
            return true;
        }
        int sea = level.getSeaLevel();
        Holder<Biome> biome = level.getBiome(new BlockPos(x, sea, z));
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return true;
        }
        // The biome at sea level is a cave biome wherever the ground stands above it, so under an island
        // that sits over land it reports nothing useful. Looking for the water itself settles it either
        // way, and is the more direct question anyway: does this land in water or not.
        for (int y = sea - 1; y >= sea - 4; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The top solid block of the island surface below the given point.
     *
     * <p>Not the world surface heightmap, which describes a whole column and so on stacked islands only
     * ever names the topmost. The lower islands are the ones worth having water on, since a fall is
     * something a player can swim up before they can fly.
     */
    private static int findIslandTop(WorldGenLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(origin.getX(), origin.getY(), origin.getZ());
        if (!level.getBlockState(pos).isAir()) {
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
            return state.isSolid() ? pos.getY() : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** The surface height of one column, searched near a height we already know, or MIN_VALUE for open air. */
    private static int surfaceNear(WorldGenLevel level, int x, int z, int nearY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, nearY + 2, z);
        for (int step = 0; step < SURFACE_SLACK + 2; step++) {
            BlockState state = level.getBlockState(pos);
            if (state.isSolid() && level.getBlockState(pos.above()).isAir()) {
                return pos.getY();
            }
            pos.move(Direction.DOWN);
        }
        return Integer.MIN_VALUE;
    }

    /**
     * How far out the island edge is in this direction, or -1 if there is no usable rim.
     *
     * <p>The rim is found first and the channel built back from it. Tracing downhill from wherever the
     * placement happened to land and hoping to arrive at an edge does not work: islands here are hundreds
     * of blocks across, so almost no starting point is within a stream's length of the rim, and the first
     * version of this found nothing at all in a whole spawn area.
     */
    private static int rimDistance(WorldGenLevel level, int x, int surfaceY, int z, Direction outward) {
        for (int step = 1; step <= EDGE_SEARCH; step++) {
            int nx = x + outward.getStepX() * step;
            int nz = z + outward.getStepZ() * step;
            int ny = surfaceNear(level, nx, nz, surfaceY);
            if (ny == Integer.MIN_VALUE) {
                return step >= 2 && isSheerBelow(level, nx, surfaceY, nz, outward) ? step - 1 : -1;
            }
            if (ny > surfaceY + 1) {
                // Ground climbing above the stream would dam it before it reached the edge.
                return -1;
            }
        }
        return -1;
    }

    /**
     * The line the stream takes, from a tail inland out to the lip.
     *
     * <p>Most of the length is the tail, added behind the starting point rather than found in front of it,
     * which is what gives the stream something to run down. It wanders a block to one side or the other as
     * it goes, and takes each column's own surface height, so the carve steps down with the ground instead
     * of cutting a level trench through it.
     */
    private static List<int[]> buildChannel(
        WorldGenLevel level, int x, int surfaceY, int z, Direction outward, int reach, RandomSource random
    ) {
        Direction side = outward.getClockWise();
        List<int[]> path = new ArrayList<>();
        int tail = CHANNEL_LENGTH - reach;
        int wander = 0;

        for (int step = -tail; step <= reach; step++) {
            if (step > -tail && random.nextInt(3) == 0) {
                wander = Math.max(-2, Math.min(2, wander + (random.nextBoolean() ? 1 : -1)));
            }
            int nx = x + outward.getStepX() * step + side.getStepX() * wander;
            int nz = z + outward.getStepZ() * step + side.getStepZ() * wander;
            int ny = surfaceNear(level, nx, nz, path.isEmpty() ? surfaceY : path.get(path.size() - 1)[2]);
            if (ny == Integer.MIN_VALUE) {
                // Ran off the island early. Whatever has been gathered so far is the stream, as long as it
                // is long enough to read as one.
                break;
            }
            path.add(new int[] {nx, nz, ny});
        }

        // The lip has to be the last node, or the fall would start somewhere the water never reaches.
        if (path.size() < 3) {
            return null;
        }
        int[] last = path.get(path.size() - 1);
        if (Math.abs(last[0] - (x + outward.getStepX() * reach)) > 2
            || Math.abs(last[1] - (z + outward.getStepZ() * reach)) > 2) {
            return null;
        }
        return path;
    }

    /** Whether the air past the lip keeps going down, rather than being a ledge onto more island. */
    private static boolean isSheerBelow(WorldGenLevel level, int x, int y, int z, Direction outward) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x + outward.getStepX(), y, z + outward.getStepZ());
        for (int drop = 0; drop < MIN_DROP; drop++) {
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
            pos.move(Direction.DOWN);
        }
        return true;
    }

    /**
     * Cuts the gully and fills it.
     *
     * <p>The water sits a block below the ground it runs through rather than flush with it, so the banks
     * stand over the stream and it reads as something cut into the island. Every step gets a source block
     * of its own: sources never drain, so each is a small pool spilling into the one below, which is the
     * stepped look a single long flow does not give.
     */
    private static void carveChannel(WorldGenLevel level, List<int[]> path, Direction outward, RandomSource random) {
        Direction side = outward.getClockWise();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < path.size(); i++) {
            int[] node = path.get(i);
            int waterY = node[2] - 1;
            // Widened by a block on one side or the other as it goes, which keeps the banks uneven. A
            // channel of constant width reads as a trench somebody dug.
            int widen = random.nextInt(3) - 1;
            int from = Math.min(0, widen);
            int to = Math.max(0, widen);

            for (int offset = from; offset <= to; offset++) {
                int wx = node[0] + side.getStepX() * offset;
                int wz = node[1] + side.getStepZ() * offset;
                pos.set(wx, waterY, wz);
                if (!level.getBlockState(pos).isSolid() && offset != 0) {
                    continue;
                }
                setWater(level, pos, Blocks.WATER.defaultBlockState());
                // Clear the bank down to the water so it is a channel and not a covered pipe.
                clear(level, wx, waterY + 1, wz);
                seal(level, wx, waterY, wz, side, outward, i == path.size() - 1);
            }
        }
    }

    /** Plugs the sides and floor of the stream so it runs down the channel instead of across the island. */
    private static void seal(WorldGenLevel level, int x, int y, int z, Direction side, Direction outward, boolean atLip) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (atLip && dir == outward) {
                continue;
            }
            pos.set(x + dir.getStepX(), y, z + dir.getStepZ());
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                level.setBlock(pos, groundNear(level, pos), 2);
            }
        }
        pos.set(x, y - 1, z);
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, groundNear(level, pos), 2);
        }
    }

    /** Starts the fall past the lip. */
    private static void pourOver(WorldGenLevel level, int x, int waterY, int z, Direction outward) {
        BlockState falling = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, waterY, z);
        for (int drop = 0; drop < FALL_STARTER && pos.getY() > level.getMinBuildHeight(); drop++) {
            if (!level.getBlockState(pos).isAir()) {
                return;
            }
            setWater(level, pos, falling);
            pos.move(Direction.DOWN);
        }
    }

    private static void setWater(WorldGenLevel level, BlockPos pos, BlockState water) {
        level.setBlock(pos, water, 2);
        level.scheduleTick(pos, Fluids.WATER, 0);
    }

    private static void clear(WorldGenLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** Whatever the island is made of hereabouts, so a repair does not stand out against it. */
    private static BlockState groundNear(WorldGenLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.isSolid()) {
            return below;
        }
        BlockState above = level.getBlockState(pos.above());
        return above.isSolid() ? above : Blocks.STONE.defaultBlockState();
    }
}
