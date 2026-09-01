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
    private static final int CELL = 160;

    /** How far a stream runs before it gives up, if the island has not already ended under it. */
    private static final int LENGTH = 140;

    /** Shorter than this is a notch in a rim, not a stream, so those plans are dropped. */
    private static final int MIN_RUN = 14;

    /**
     * How far inside an island, by the mask, a head has to be.
     *
     * <p>Half of all island ground sits below 0.13, so this puts every head well into the inner half. It is
     * also the length control: a head further in has further to run before it finds an edge, at the cost of
     * fewer cells having anywhere that qualifies at all.
     */
    private static final double INLAND = 0.17D;

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
    private static final double FOLLOW = 0.15D;

    /** Noise added to the heading on top of the slope. Small: a waver, not a course of its own. */
    private static final double WIGGLE = 0.045D;

    /** Half width in blocks at the drop, tapering to nothing at the head. */
    private static final int HALF_WIDTH = 1;

    /** The bed falls at least this much a block even over level ground, so water keeps moving on a deck. */
    private static final double SLOPE = 0.04D;

    /** Deeper than this and the plan is thrown away rather than cut as a trench. */
    private static final int MAX_CUT = 9;

    /** Ground falling faster than this in a block is the lip of a fall, and the channel stops there. */
    private static final double CLIFF = 19.0D;

    /**
     * How much of the channel before the drop is cut but left without a source of its own.
     *
     * <p>So that what goes over the edge is water already in motion. A source is a full block that never
     * drains, and one sitting on the brink is a still cube of water at the rim with the fall hanging under
     * it; flow arriving there is half a block high and carries the moving face, which is what an edge with a
     * stream running over it looks like. It also keeps sources off the rim itself, which is where they
     * spread sideways and came out as a curtain the first time round.
     *
     * <p>Bounded by how far flow travels: seven blocks over level ground, and the last stretch of a run is
     * usually falling rather than level, but a long dry lip is a channel with no water in the part of it
     * that shows most.
     */
    private static final int DRY_LIP = 2;

    /**
     * How far past its own half width a segment of channel reaches for columns.
     *
     * <p>Not cosmetic, and not a width control. Consecutive nodes are a block apart, so on a diagonal they
     * land in columns that touch only at a corner. Anything under about 0.71 leaves the two joined by that
     * corner alone, and a corner is not a connection: water does not flow diagonally, and the column left
     * between them stands full height in the middle of the finished channel. That is where the pillars came
     * from. Measured over fifty eight streams, sampling offsets left a block of ground standing inside the
     * channel in nearly six percent of its columns; this leaves it in one in seventeen hundred, and both
     * more and less reach than this do worse.
     */
    private static final double BRIDGE = 0.85D;

    /**
     * Cells either side of a chunk's own that are checked for streams reaching into it.
     *
     * <p>A head can sit anywhere in its cell and climb a little outside it, and the run is up to LENGTH
     * beyond that, so a stream from two cells away can still cross this chunk.
     */
    private static final int SCAN = 2;

    /**
     * Blocks of island rock left under the channel floor.
     *
     * <p>What stops a channel cutting clean through a rim. An island is thin at its edges and the channel is
     * at its deepest exactly there, so without this the two meet and the cut opens a slot right through,
     * leaving the ground either side of it hanging over the gap. Where even this cannot be had the column is
     * grooved a single block rather than passed over: a column left untouched in the middle of a channel
     * stands in it as a pillar, and a shallow bed is much the lesser of those.
     */
    private static final int FLOOR_KEEP = 2;

    /** Below this many blocks of rock a column is the very brink and is left alone. */
    private static final int MIN_THICKNESS = 2;

    /** Column tables cover the chunk and a block of margin, so a pillar on the boundary is still seen. */
    private static final int SPAN = 18;

    /** Sentinels for the per-chunk column tables: not looked at yet, and looked at and found nothing. */
    private static final int UNSCANNED = Integer.MIN_VALUE;
    private static final int NO_SURFACE = Integer.MIN_VALUE + 1;

    public IslandStreamFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * What every stream crossing this chunk wants of each of its columns.
     *
     * <p>Covers a block of ground beyond the chunk on every side. Nothing outside is ever written -- that
     * remains the rule that keeps decoration honest -- but a pillar is recognised by having channel on
     * both sides of it, and a pillar sitting against a chunk boundary has one of those sides in the
     * neighbour. Reading a block across is fine; it is only writing that is not.
     *
     * @param surface the top of the island rock, or NO_SURFACE where this column has no island in it
     * @param bottom the lowest solid block of the run beneath that surface
     * @param floor the elevation the channel floor is cut to, or UNSCANNED where nothing wants this column
     * @param wet whether a source block is laid at that floor
     */
    private record Columns(int[] surface, int[] bottom, int[] floor, boolean[] wet) {
        static Columns blank() {
            int size = SPAN * SPAN;
            Columns columns = new Columns(new int[size], new int[size], new int[size], new boolean[size]);
            Arrays.fill(columns.surface, UNSCANNED);
            Arrays.fill(columns.floor, UNSCANNED);
            return columns;
        }

        static int slot(int x, int z, int minX, int minZ) {
            return (x - minX + 1) * SPAN + (z - minZ + 1);
        }
    }

    /**
     * Every stream is measured against the chunk first and only then cut.
     *
     * <p>Carving as it went is what put blocks back into channels it had already opened. A column is reached
     * by several nodes -- consecutive ones overlap across most of their width -- and each of those wanted it
     * at a slightly different depth. Cutting on each visit meant a later, shallower pass laid its floor into
     * the middle of a channel an earlier, deeper one had already cleared, and left it standing there in the
     * water. Deciding first and cutting once means a column has one floor: the lowest anything asked for.
     */
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
        Columns columns = Columns.blank();
        boolean wanted = false;

        for (int cx = homeX - SCAN; cx <= homeX + SCAN; cx++) {
            for (int cz = homeZ - SCAN; cz <= homeZ + SCAN; cz++) {
                Plan plan = cached(context, cx, cz);
                if (plan != null) {
                    wanted |= mark(level, plan, minX, minZ, columns);
                }
            }
        }
        if (!wanted) {
            return false;
        }
        close(level, minX, minZ, columns);
        return carve(level, minX, minZ, columns);
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

        double heightAt(double x, double z, int layer) {
            return at(x, z, layer).surfaceY();
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
            double y = terrain.heightAt(px + Math.sin(a) * 24.0D, pz + Math.cos(a) * 24.0D, layer);
            if (y < lowest) {
                lowest = y;
                angle = a;
            }
        }

        List<Node> nodes = new ArrayList<>(LENGTH + 1);
        double previous = 0.0D;
        for (int step = 0; step <= LENGTH; step++) {
            SkyIslandDensity.Ground ground = terrain.at(px, pz, layer);
            // Three ways for the run to be over. The island ending under it, which is the lip of the fall.
            // The ground dropping faster than a channel could hold water on it, which stops a stream
            // carving a staircase down a face it should simply pour over. And a second island standing
            // over this column, where a channel would be a trench in the floor of a cave.
            if (ground.mask() <= 0.0D || ground.covered()) {
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
                        px + Math.sin(angle + turn) * FAN_REACH, pz + Math.cos(angle + turn) * FAN_REACH, layer
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
            double previous = bed;
            bed = Math.min(bed - SLOPE, node.bed() - want);
            // The least fall is not allowed to dig its own trench. Over a level deck it accumulates -- a
            // hundred and forty blocks at four hundredths is five and a half of them -- and would take the
            // cut past the cap on its own, throwing away a plan that had nothing wrong with it. Held at the
            // cap instead, which is still never rising, and never lifted above where the bed already was.
            bed = Math.max(bed, Math.min(previous, node.bed() - MAX_CUT));
            // Past the cap even so, which now only happens where the ground itself climbed into the
            // channel. That is a gorge rather than a stream, and it is thrown away rather than dug.
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
     * Works out what this stream wants of the columns inside this chunk, without touching any of them.
     *
     * <p>Columns are claimed by distance from the path rather than by stepping out along a perpendicular.
     * Sampling offsets left holes: an offset is rounded to a column, and on a diagonal two neighbouring
     * nodes round to the same few columns while the one between them is never named at all. It survives the
     * carve as a block of untouched ground standing full height in the finished channel. Measuring distance
     * to the segment instead claims every column the channel passes over, once, with no rounding to fall
     * through.
     *
     * @return whether any column in this chunk is wanted at all
     */
    private static boolean mark(WorldGenLevel level, Plan plan, int minX, int minZ, Columns columns) {
        int reach = SkyIslandDensity.layerReach();
        int bandBottom = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach;
        int bandTop = BarrenSkiesConfig.SKY_ISLAND_TOP.get() + reach * 2;
        List<Node> nodes = plan.nodes();
        // At least the head stays wet however short the run and however long the lip.
        int lastWet = Math.max(0, nodes.size() - 1 - DRY_LIP);
        boolean wanted = false;

        for (int step = 1; step < nodes.size(); step++) {
            Node from = nodes.get(step - 1);
            Node node = nodes.get(step);
            double radius = node.half() + BRIDGE;

            int x0 = Math.max(minX - 1, (int) Math.floor(Math.min(from.x(), node.x()) - radius));
            int x1 = Math.min(minX + 16, (int) Math.floor(Math.max(from.x(), node.x()) + radius));
            int z0 = Math.max(minZ - 1, (int) Math.floor(Math.min(from.z(), node.z()) - radius));
            int z1 = Math.min(minZ + 16, (int) Math.floor(Math.max(from.z(), node.z()) + radius));

            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double away = distanceToSegment(x + 0.5D, z + 0.5D, from, node);
                    if (away > radius) {
                        continue;
                    }
                    int slot = Columns.slot(x, z, minX, minZ);
                    if (columns.surface()[slot] == UNSCANNED) {
                        sound(level, x, z, bandBottom, bandTop, slot, columns);
                    }
                    // V shaped: the floor rises a block for every block out from the middle, and no
                    // further than the half width however far the bridging reach had to stretch.
                    int wants = (int) Math.floor(node.bed()) + Math.min(node.half(), (int) Math.round(away));
                    if (!claim(columns, slot, wants)) {
                        continue;
                    }
                    // The later node wins, and the later node is the one nearer the drop, so a column the
                    // lip reaches over stays dry even where a wet node also touched it.
                    columns.wet()[slot] = step <= lastWet;
                    wanted = true;
                }
            }
        }
        return wanted;
    }

    /**
     * Records that something wants this column opened to a given elevation, if it can be.
     *
     * <p>Where several nodes want the same column -- and consecutive ones overlap across most of their
     * width -- the lowest wins, so a column has one floor rather than being cut once for each of them.
     *
     * @return whether the column is claimed at all
     */
    private static boolean claim(Columns columns, int slot, int wants) {
        int surface = columns.surface()[slot];
        int bottom = columns.bottom()[slot];
        if (surface == NO_SURFACE || surface - bottom < MIN_THICKNESS - 1) {
            return false;
        }
        int floor = Math.min(wants, surface - 1);
        floor = Math.max(floor, surface - MAX_CUT);
        // Leave rock under it, so the channel cannot open a slot through a rim.
        floor = Math.max(floor, bottom + FLOOR_KEEP);
        if (floor > surface - 1) {
            // Too thin for a channel with rock to spare. Take what there is rather than passing over the
            // column: one block of ground beneath the water and, where the island is thinner still, the
            // surface block alone. Passing over it is what ended a channel short of the rim it was running
            // for, since the columns too thin to cut are exactly the last few before an edge.
            floor = Math.min(surface, Math.max(bottom + 1, surface - 1));
        }
        if (columns.floor()[slot] == UNSCANNED || floor < columns.floor()[slot]) {
            columns.floor()[slot] = floor;
        }
        return true;
    }

    /**
     * Opens any column the channel has surrounded but not claimed.
     *
     * <p>Two ways one arises. A tight enough bend has the channel pass either side of a column without the
     * path itself ever crossing it, and a column whose rock ran out shallow takes a floor of its own that
     * can stand well above its neighbours'. Both leave a block of ground with channel on both sides of it,
     * which is the definition of the pillars, so both are answered the same way: give it the lower of the
     * two floors bracketing it.
     *
     * <p>Only columns bracketed on an axis are touched. A channel wall is bounded on one side and stays.
     */
    private static void close(WorldGenLevel level, int minX, int minZ, Columns columns) {
        int reach = SkyIslandDensity.layerReach();
        int bandBottom = BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach;
        int bandTop = BarrenSkiesConfig.SKY_ISLAND_TOP.get() + reach * 2;

        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                int slot = Columns.slot(x, z, minX, minZ);
                if (columns.floor()[slot] != UNSCANNED) {
                    continue;
                }
                int west = columns.floor()[slot - SPAN];
                int east = columns.floor()[slot + SPAN];
                int north = columns.floor()[slot - 1];
                int south = columns.floor()[slot + 1];
                int bracket;
                boolean wet;
                if (west != UNSCANNED && east != UNSCANNED) {
                    bracket = Math.min(west, east);
                    wet = columns.wet()[slot - SPAN] && columns.wet()[slot + SPAN];
                } else if (north != UNSCANNED && south != UNSCANNED) {
                    bracket = Math.min(north, south);
                    wet = columns.wet()[slot - 1] && columns.wet()[slot + 1];
                } else {
                    continue;
                }
                if (columns.surface()[slot] == UNSCANNED) {
                    sound(level, x, z, bandBottom, bandTop, slot, columns);
                }
                if (claim(columns, slot, bracket)) {
                    columns.wet()[slot] = wet;
                }
            }
        }
    }

    private static double distanceToSegment(double px, double pz, Node from, Node to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double length = dx * dx + dz * dz;
        double along = length <= 0.0D ? 0.0D : ((px - from.x()) * dx + (pz - from.z()) * dz) / length;
        along = Math.max(0.0D, Math.min(1.0D, along));
        return Math.hypot(px - (from.x() + dx * along), pz - (from.z() + dz * along));
    }

    /**
     * Finds the top of the island rock in a column and how far down it goes.
     *
     * <p>The run of rock stops at the first gap, so a cave under the surface ends it as surely as the
     * underside does. That is deliberate: a channel cut into the roof of a cave is a hole, not a stream bed.
     */
    private static void sound(
        WorldGenLevel level, int x, int z, int bandBottom, int bandTop, int slot, Columns columns
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int surface = NO_SURFACE;
        int air = 0;
        for (int y = bandTop; y >= bandBottom; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                air++;
                continue;
            }
            if (air >= OPEN_SKY && state.isSolid()) {
                surface = y;
                break;
            }
            air = 0;
        }
        columns.surface()[slot] = surface;
        if (surface == NO_SURFACE) {
            return;
        }

        int bottom = surface;
        while (bottom > bandBottom) {
            pos.set(x, bottom - 1, z);
            if (!level.getBlockState(pos).isSolid()) {
                break;
            }
            bottom--;
        }
        columns.bottom()[slot] = bottom;
    }

    /**
     * Opens every column the streams claimed, once each, and only inside this chunk.
     *
     * <p>The floor is cut away like everything above it and the water, where there is water, is laid in the
     * space that leaves. Wet and dry columns are cut to the same elevation on purpose: water will not flow
     * uphill, so a dry lip whose bed sat where the wet channel's water sits would meet the flow with a one
     * block step and stop it dead a stride short of going over.
     *
     * <p>Nothing is laid under the water. It does not need it: the floor is never taken below the rock the
     * column stands on, so the block beneath it is ground already. Conjuring one is what used to leave
     * stubs of stone hanging off the island rims.
     */
    private static boolean carve(WorldGenLevel level, int minX, int minZ, Columns columns) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean carved = false;

        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                int slot = Columns.slot(x, z, minX, minZ);
                int floor = columns.floor()[slot];
                if (floor == UNSCANNED) {
                    continue;
                }
                for (int y = columns.surface()[slot]; y >= floor; y--) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (columns.wet()[slot]) {
                    pos.set(x, floor, z);
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
                    level.scheduleTick(pos, Fluids.WATER, 0);
                }
                carved = true;
            }
        }
        return carved;
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
