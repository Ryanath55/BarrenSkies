package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * A stream cut across an island top: down from the high ground to an edge that stands over open sea.
 *
 * <p>The path is worked out rather than searched for. A hash over a fixed grid decides which cells hold a
 * stream and scatters candidate heads through them; the highest candidate wins, climbs to whatever crest it
 * landed on, and the channel runs downhill from there, steered every second block by probing ahead for the
 * lowest ground and wavered by a noise laid over the top of that. Every quantity that matters -- how far
 * along, how deep, how wide, what elevation the water sits at -- is a property of distance travelled, which
 * is why this is a path and not a noise field. A contour of a noise has no along: it cannot taper, cannot
 * widen towards one end, and cannot slope.
 *
 * <p>The bed is the guarantee. It only ever falls, whatever the ground above it does, so a stream can never
 * read as running upstream even where it crosses ground that rises. Where the ground falls away the bed
 * falls with it and the channel stays shallow, which is a cascade; where the ground climbs, the bed holds
 * and the channel cuts deeper, which is a gorge, until the cut is deeper than water would plausibly have
 * carved and the plan is abandoned rather than trenched through.
 *
 * <p>All the planning runs on the height field -- island mask, ridge, deck spline -- and none of it on the
 * generated world. That is what keeps it inside one chunk. The whole path is known to every chunk it
 * crosses, so each one writes only the blocks that fall within itself and none outside. Reading across
 * chunks during decoration is fine and writing across it is not, which is the distinction an earlier version
 * got wrong, and it is what left trees floating over ground that had been cut away.
 */
public class IslandStreamFeature extends Feature<NoneFeatureConfiguration> {
    /** Grid the stream heads are hashed over. Roughly one candidate per island. */
    private static final int CELL = 192;

    /** How far a stream runs before it gives up, if the island has not already ended under it. */
    private static final int LENGTH = 160;

    /** Shorter than this is a notch in a rim, not a stream, so those plans are dropped. */
    private static final int MIN_RUN = 20;

    /**
     * How far inside an island, by the mask, a head has to be.
     *
     * <p>Half of all island ground sits below 0.13, so this puts every head in the inner half. It is also
     * the length control: a head further in has further to run before it finds an edge. Measured over five
     * thousand blocks, raising it from 0.10 to 0.15 costs three streams in ten and takes the median run
     * from forty eight blocks to sixty five.
     */
    private static final double INLAND = 0.15D;

    /** Air needed above a surface before it counts as open ground rather than the roof of a cave. */
    private static final int OPEN_SKY = 6;

    /** Candidate heads scattered through a cell. The highest of them is the one that gets the stream. */
    private static final int HEAD_TRIES = 12;

    /** Then uphill from there, this far a step, for at most this many rounds. */
    private static final double CLIMB_STEP = 5.0D;
    private static final int CLIMB_ROUNDS = 10;

    /**
     * How the channel is steered: a fan of candidate headings, none further off the current one than this,
     * and the lowest ground wins.
     *
     * <p>A gradient would say the same thing in principle and much less in practice. The deck is flat in
     * bands, so over most of an island the local gradient is exactly zero and answers nothing at all, while
     * a probe eight blocks out still finds the cliff.
     */
    private static final double[] FAN = { -0.62D, -0.31D, 0.0D, 0.31D, 0.62D };
    private static final int FAN_REACH = 8;
    private static final int STEER_EVERY = 2;

    /** How much of the turn towards the lowest ground it actually takes each time it steers. */
    private static final double FOLLOW = 0.75D;

    /** Noise added to the heading on top of the slope. Small: a waver, not a course of its own. */
    private static final double WIGGLE = 0.05D;

    /** Half width in blocks at the drop, tapering to nothing at the head. */
    private static final int HALF_WIDTH = 2;

    /** The bed falls at least this much a block even over level ground, so water keeps moving on a deck. */
    private static final double SLOPE = 0.05D;

    /** Deeper than this and the plan is thrown away rather than cut as a trench. */
    private static final int MAX_CUT = 14;

    /** Ground falling faster than this in a block is the lip of a fall, and the channel stops there. */
    private static final double CLIFF = 8.0D;

    /**
     * How much of the channel before the drop is cut but left without a source of its own.
     *
     * <p>So that what goes over the edge is water already in motion. A source is a full block that never
     * drains, and one sitting on the brink is a still cube of water at the rim with the fall hanging under
     * it; flow arriving there is half a block high and carries the moving face, which is what an edge with a
     * stream running over it looks like. It also keeps sources off the rim itself, which is where they
     * spread sideways and came out as a curtain the first time round.
     *
     * <p>Kept short deliberately. Flow reaches seven blocks over level ground before it dies, and the last
     * stretch of a run is usually falling rather than level, but a long dry lip is a channel with no water
     * in the part of it that shows most.
     */
    private static final int DRY_LIP = 3;

    /**
     * Cells either side of a chunk's own that are checked for streams reaching into it.
     *
     * <p>A head can sit anywhere in its cell and climb a little outside it, and the run is up to LENGTH
     * beyond that, so a stream from two cells away can still cross this chunk.
     */
    private static final int SCAN = 2;

    /** Sentinels for the per-chunk surface cache: not looked at yet, and looked at and found nothing. */
    private static final int UNSCANNED = Integer.MIN_VALUE;
    private static final int NO_SURFACE = Integer.MIN_VALUE + 1;

    public IslandStreamFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!BarrenSkiesConfig.ISLAND_WATERFALLS.get()) {
            return false;
        }

        WorldGenLevel level = context.level();
        int minX = context.origin().getX() & ~15;
        int minZ = context.origin().getZ() & ~15;
        int homeX = Math.floorDiv(minX, CELL);
        int homeZ = Math.floorDiv(minZ, CELL);
        boolean carved = false;

        // Finding a surface means scanning three hundred blocks of column, and the channel comes back to the
        // same column several times over -- consecutive nodes overlap across most of their width. Once per
        // column per chunk instead, shared across every stream that crosses it.
        int[] surfaces = new int[256];
        Arrays.fill(surfaces, UNSCANNED);

        for (int cx = homeX - SCAN; cx <= homeX + SCAN; cx++) {
            for (int cz = homeZ - SCAN; cz <= homeZ + SCAN; cz++) {
                Plan plan = cached(context, cx, cz);
                if (plan != null) {
                    carved |= walk(level, plan, minX, minZ, surfaces);
                }
            }
        }
        return carved;
    }

    /** One block of the centre line: where it is, which way it points, and what elevation its floor is at. */
    private record Node(double x, double z, double angle, double bed, int half) {
    }

    /** A stream known to start on high ground and to reach an edge, worked out before anything is cut. */
    private record Plan(int startX, int startZ, List<Node> nodes) {
    }

    private record CellKey(long seed, int x, int z) {
    }

    /**
     * Plans are worked out once per cell and kept, because the same stream is planned by every chunk it
     * crosses.
     *
     * <p>A stream is ten chunks long and each chunk checks two cells either side of itself, so without this
     * one channel is replanned some hundreds of times, and a plan is a few thousand noise lookups. Held per
     * thread, because worldgen runs on several and this needs no locking at all to be worth having.
     */
    private static final ThreadLocal<Map<CellKey, Optional<Plan>>> PLANS =
        ThreadLocal.withInitial(() -> new LinkedHashMap<>(128, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CellKey, Optional<Plan>> eldest) {
                return size() > 128;
            }
        });

    private static Plan cached(FeaturePlaceContext<NoneFeatureConfiguration> context, int cellX, int cellZ) {
        CellKey key = new CellKey(context.level().getSeed(), cellX, cellZ);
        return PLANS.get()
            .computeIfAbsent(key, k -> Optional.ofNullable(plan(context, k)))
            .orElse(null);
    }

    /** The height field, bound to one world, so the planner can ask it about any coordinate. */
    private record Terrain(
        NormalNoise islands, NormalNoise ridges, NormalNoise streams,
        int bandBottom, int bandTop, int layers, double threshold, double scale
    ) {
        SkyIslandDensity.Ground at(double x, double z, int layer) {
            return SkyIslandDensity.ground(
                this.islands, this.ridges, x, z,
                this.bandBottom, this.bandTop, this.layers, this.threshold, this.scale, layer
            );
        }

        double heightAt(double x, double z) {
            return at(x, z, -1).surfaceY();
        }
    }

    private static Terrain terrainOf(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var random = context.level().getLevel().getChunkSource().randomState();
        return new Terrain(
            random.getOrCreateNoise(SkyIslandDensity.ISLANDS),
            random.getOrCreateNoise(SkyIslandDensity.ISLAND_RIDGES),
            random.getOrCreateNoise(SkyIslandDensity.ISLAND_STREAMS),
            BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
            BarrenSkiesConfig.SKY_ISLAND_TOP.get(),
            BarrenSkiesConfig.ISLAND_LAYERS.get(),
            BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
            BarrenSkiesConfig.ISLAND_SCALE.get()
        );
    }

    /** Whether this cell holds a stream at all, and if so where it starts and where it goes. */
    private static Plan plan(FeaturePlaceContext<NoneFeatureConfiguration> context, CellKey key) {
        long hash = mix(key.seed() ^ (key.x() * 341873128712L) ^ (key.z() * 132897987541L));
        if (Math.floorMod(hash, 100L) >= BarrenSkiesConfig.WATERFALL_ISLAND_CHANCE.get()) {
            return null;
        }

        Terrain terrain = terrainOf(context);

        // The highest of a scatter of candidates, not the first one that happens to be inland. A stream has
        // to start at the top or it has nowhere to go, and the decks differ by forty blocks between ridge
        // bands, so which candidate is taken decides most of what the stream then does.
        int startX = 0;
        int startZ = 0;
        int layer = -1;
        double bestY = Double.NEGATIVE_INFINITY;
        for (int attempt = 0; attempt < HEAD_TRIES; attempt++) {
            long pick = mix(hash + attempt * 0x9E3779B97F4A7C15L);
            int x = key.x() * CELL + (int) Math.floorMod(pick, CELL);
            int z = key.z() * CELL + (int) Math.floorMod(pick >> 20, CELL);
            SkyIslandDensity.Ground ground = terrain.at(x, z, -1);
            if (ground.mask() < INLAND || !ground.hasGround() || ground.surfaceY() <= bestY) {
                continue;
            }
            bestY = ground.surfaceY();
            startX = x;
            startZ = z;
            layer = ground.layer();
        }
        if (layer < 0) {
            return null;
        }

        // Then uphill for as long as it keeps climbing, which moves the head onto the crest above the
        // candidate instead of leaving it partway down whatever slope the candidate happened to land on.
        for (int round = 0; round < CLIMB_ROUNDS; round++) {
            boolean stepped = false;
            for (int spoke = 0; spoke < 8; spoke++) {
                double angle = spoke / 8.0D * Math.PI * 2.0D;
                int x = (int) Math.round(startX + Math.sin(angle) * CLIMB_STEP);
                int z = (int) Math.round(startZ + Math.cos(angle) * CLIMB_STEP);
                SkyIslandDensity.Ground ground = terrain.at(x, z, layer);
                if (ground.mask() < INLAND || ground.surfaceY() <= bestY + 0.5D) {
                    continue;
                }
                bestY = ground.surfaceY();
                startX = x;
                startZ = z;
                stepped = true;
            }
            if (!stepped) {
                break;
            }
        }

        List<Node> line = descend(terrain, startX, startZ, layer, Math.floorMod(hash >> 12, 4096L));
        if (line == null || line.size() - 1 < MIN_RUN || !profile(line)) {
            return null;
        }
        // Asked of the far end, because that is where the water goes over. The rule is that a stream must
        // not pour onto the barren surface, and only the last node has any say in whether it does.
        Node foot = line.getLast();
        if (!overWater(context, (int) Math.floor(foot.x()), (int) Math.floor(foot.z()))) {
            return null;
        }
        return new Plan(startX, startZ, line);
    }

    /**
     * Walks the centre line downhill from a head until the island runs out under it.
     *
     * <p>The centre line only. Width, depth and the elevation of the bed are worked out afterwards, by
     * {@link #profile}, because all three are measured against the length of the run and the run is not
     * known until the walk has finished. Null means it was still on the island at the longest run, which is
     * not a stream: water that never reaches an edge is a puddle.
     */
    private static List<Node> descend(Terrain terrain, int startX, int startZ, int layer, long lane) {
        double px = startX + 0.5D;
        double pz = startZ + 0.5D;

        // Which way is down at island scale. Asked once, at the head, where the local view is usually a
        // level deck with no slope in it at all and the fall is fifty blocks away.
        double angle = 0.0D;
        double lowest = Double.POSITIVE_INFINITY;
        for (int spoke = 0; spoke < 12; spoke++) {
            double a = spoke / 12.0D * Math.PI * 2.0D;
            double y = terrain.heightAt(px + Math.sin(a) * 24.0D, pz + Math.cos(a) * 24.0D);
            if (y < lowest) {
                lowest = y;
                angle = a;
            }
        }

        List<Node> nodes = new ArrayList<>(LENGTH + 1);
        double previous = 0.0D;
        for (int step = 0; step <= LENGTH; step++) {
            SkyIslandDensity.Ground ground = terrain.at(px, pz, layer);
            // Two ways for the run to be over, and both of them are the lip of the fall: the island ending
            // under it, or the ground dropping away faster than a channel could hold water on it. The
            // second is what stops a stream carving a staircase down a face it should simply pour over.
            if (ground.mask() <= 0.0D) {
                return nodes;
            }
            if (step > 0 && ground.surfaceY() < previous - CLIFF) {
                return nodes;
            }
            previous = ground.surfaceY();
            // The ground height rides in the bed field until profile turns it into an actual bed.
            nodes.add(new Node(px, pz, angle, ground.surfaceY(), 0));

            if (step % STEER_EVERY == 0) {
                double bestTurn = 0.0D;
                double bestY = Double.POSITIVE_INFINITY;
                for (double turn : FAN) {
                    double y = terrain.heightAt(
                        px + Math.sin(angle + turn) * FAN_REACH, pz + Math.cos(angle + turn) * FAN_REACH
                    );
                    if (y < bestY) {
                        bestY = y;
                        bestTurn = turn;
                    }
                }
                angle += bestTurn * FOLLOW;
            }
            angle += terrain.streams().getValue(step * 0.28D, lane, 0.0D) * WIGGLE;
            px += Math.sin(angle);
            pz += Math.cos(angle);
        }
        return null;
    }

    /**
     * Turns the centre line into a channel, in place: how wide it is at each node and where its floor sits.
     *
     * <p>Nodes come in carrying the ground height in the bed field and go out carrying the bed itself, which
     * is a running minimum of the ground less the depth wanted there. Shallow and narrow at the head, deep
     * and wide at the drop, and never rising anywhere along the way.
     *
     * @return false if the channel would have to cut deeper than a stream plausibly could, in which case
     *     there is no stream here at all
     */
    private static boolean profile(List<Node> nodes) {
        int run = nodes.size() - 1;
        int maxDepth = BarrenSkiesConfig.STREAM_DEPTH.get();
        double bed = nodes.getFirst().bed() - 2.0D + SLOPE;

        for (int step = 0; step <= run; step++) {
            Node node = nodes.get(step);
            double along = step / (double) run;
            double want = 2.0D + (maxDepth - 2.0D) * along;
            bed = Math.min(bed - SLOPE, node.bed() - want);
            if (node.bed() - bed > MAX_CUT) {
                return false;
            }
            nodes.set(step, new Node(
                node.x(), node.z(), node.angle(), bed,
                (int) Math.round(HALF_WIDTH * Math.pow(along, 0.7D))
            ));
        }
        return true;
    }

    /**
     * Cuts whatever of the channel falls inside this chunk.
     *
     * <p>Offsets across the channel are tested for the chunk individually rather than the centre line being
     * tested for all of them. A channel three wide whose middle sits on a chunk boundary would otherwise
     * have one side written by a chunk it does not belong to and the other side written by nobody.
     */
    private static boolean walk(WorldGenLevel level, Plan plan, int minX, int minZ, int[] surfaces) {
        int reach = SkyIslandDensity.layerReach();
        int bandBottom = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach;
        int bandTop = BarrenSkiesConfig.SKY_ISLAND_TOP.get() + reach * 2;
        List<Node> nodes = plan.nodes();
        int lastWet = nodes.size() - 1 - DRY_LIP;
        boolean carved = false;

        for (int step = 0; step < nodes.size(); step++) {
            Node node = nodes.get(step);
            boolean source = step <= lastWet;
            // Across the channel, perpendicular to where it is pointing.
            double sideX = Math.cos(node.angle());
            double sideZ = -Math.sin(node.angle());

            for (int offset = -node.half(); offset <= node.half(); offset++) {
                int x = (int) Math.floor(node.x() + sideX * offset);
                int z = (int) Math.floor(node.z() + sideZ * offset);
                if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) {
                    continue;
                }
                int slot = (x - minX) * 16 + (z - minZ);
                if (surfaces[slot] == UNSCANNED) {
                    surfaces[slot] = islandSurface(level, x, z, bandBottom, bandTop);
                }
                int surface = surfaces[slot];
                if (surface == NO_SURFACE) {
                    continue;
                }
                // Cut down to an elevation, not by a depth. The bed is the thing the plan guarantees never
                // rises; the ground over it wanders a few blocks with the detail and landform noise, and
                // taking a fixed depth off that wander is exactly what would put a step back up in the
                // middle of a stream. V shaped, so the floor rises a block for each block out from the
                // middle. Always at least one block, so the channel stays continuous where the ground has
                // dropped below the plan, and never more than the deepest cut, so it cannot open a shaft.
                int floor = (int) Math.floor(node.bed() + Math.abs(offset));
                floor = Math.min(floor, surface - 1);
                floor = Math.max(floor, surface - MAX_CUT);
                cut(level, x, z, surface, floor, source);
                carved = true;
            }
        }
        return carved;
    }

    /** The topmost open island surface in a column, or NO_SURFACE if this column has no island in it. */
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
        return NO_SURFACE;
    }

    /**
     * Opens one column of the channel, and lays water in the bottom of it unless this is the lip.
     *
     * <p>The channel is cut to the same floor either way, and the floor is always given something to stand
     * on. That matters more than it looks: water will not flow uphill, so if the dry columns kept their bed
     * where the wet ones keep their water, the flow would meet a one block step at the brink and stop dead
     * a stride short of going over.
     *
     * <p>One block of water, never a filled channel. An earlier version filled the whole width with source
     * blocks, and a source never drains: fourteen of them side by side at an island edge emptied over it all
     * at once and came out as a curtain rather than a stream.
     */
    private static void cut(WorldGenLevel level, int x, int z, int surface, int floor, boolean source) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = surface; y >= floor; y--) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        pos.set(x, floor - 1, z);
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        if (source) {
            pos.set(x, floor, z);
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
            level.scheduleTick(pos, Fluids.WATER, 0);
        }
    }

    /**
     * Whether the sea is under this point.
     *
     * <p>Asked of the biome source directly rather than of the world, because the biome source is noise and
     * answers for any coordinate, while the world can only answer for chunks that exist. That matters here:
     * the chunk deciding whether a stream runs is rarely the chunk the stream ends in.
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
