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
 * <p>Worked out from its mouth backwards. A hash over a fixed grid scatters candidate outlets through each
 * cell; the few that land on a rim are asked, at one lookup, whether there is sea below; and the course is
 * then walked inland and uphill from whichever passes, and handed back the other way round. Planning it the
 * way the water runs -- scatter heads on the high ground, walk each one down, keep whatever survives -- puts
 * every cheap and decisive question last, because a descent does not know where it will come out until it
 * has got there. Measured on the version that did: eight hundred and sixty two courses walked in full, and
 * a hundred and seventy of the completed ones thrown away over the sea test alone. From the mouth, that
 * test is the first thing asked and costs one sample, reaching an edge is where the walk begins rather than
 * something to hope for, and running out of length stops being a failure at all, since away from a rim is
 * inland. The same square of world went from forty three streams to five hundred and ten.
 *
 * <p>Every quantity that matters -- how far along, how deep, how wide, what elevation the water sits at --
 * is a property of distance travelled, which is why this is a path and not a noise field. A contour of a
 * noise has no along: it cannot taper, cannot widen towards one end, and cannot slope.
 *
 * <p>The bed is the guarantee. It only ever falls, whatever the ground above it does, so a stream can never
 * read as running upstream even where it crosses ground that rises. Where the ground falls away the bed
 * falls with it and the channel stays shallow, which is a cascade; where the ground climbs, the bed holds
 * and the channel cuts deeper, which is a gorge, until the cut is deeper than water would plausibly have
 * carved and the plan is abandoned rather than trenched through.
 *
 * <p>All the planning runs on noise -- the same density the generator builds, evaluated as arithmetic --
 * and none of it on the generated world. That is what keeps it inside one chunk. The whole path is known
 * to every chunk it crosses, so each one writes only the blocks that fall within itself and none outside.
 * Reading across chunks during decoration is fine and writing across it is not, which is the distinction
 * an earlier version got wrong, and it is what left trees floating over ground that had been cut away.
 */
public class IslandStreamFeature extends Feature<NoneFeatureConfiguration> {
    /** Grid the stream heads are hashed over. Roughly one candidate per island. */
    private static final int CELL = 160;

    /** How far a stream runs before it gives up, if the island has not already ended under it. */
    private static final int LENGTH = 120;

    /** Shorter than this is a notch in a rim, not a stream, so those plans are dropped. */
    private static final int MIN_RUN = 20;

    /** Air needed above a surface before it counts as open ground rather than the roof of a cave. */
    private static final int OPEN_SKY = 6;

    /** Candidate mouths sampled per cell. Cheap: a height lookup and, for the few on a rim, a biome one. */
    private static final int OUTLET_TRIES = 64;

    /** How many of those get a course walked from them before the cell is given up on. */
    private static final int OUTLET_RETRIES = 4;

    /** How near an edge, by the mask, a mouth has to be before it is walked out to the brink. */
    private static final double RIM_BAND = 0.08D;

    /** How far out to the real rock edge a mouth may be carried past where the mask says the island ends. */
    private static final int RIM_REACH = 28;

    /**
     * How the course is steered: a fan of candidate headings, none further off the current one than this,
     * and the one furthest inland wins.
     *
     * <p>Inland by the mask, which is the only signal here that means one thing. Steering by height was
     * tried both ways and both fail: the steepest climb takes the ridge between two valleys, and the
     * gentlest takes the line that contours along a slope without climbing, which on the outside of an
     * island is the rim itself. A lean sideways into the lower flank was tried on top of that to find
     * valley floors, and once the steering stopped chasing height it moved the measured result by two
     * hundredths of a block, so it was taken out again.
     */
    private static final double[] FAN = { -0.62D, -0.31D, 0.0D, 0.31D, 0.62D };
    private static final int FAN_REACH = 8;
    private static final int STEER_EVERY = 2;

    /** How much of the turn towards the ground furthest inland it takes each time it steers. */
    private static final double FOLLOW = 0.30D;

    /**
     * How far the waver may throw a single step off the course, in radians.
     *
     * <p>Applied to the step and not kept. Added into the heading, as it was, it accumulated: a noise with
     * a wavelength longer than the whole run, integrated a hundred and twenty times, is not a waver but a
     * slow steady turn away from whatever the walk was following. About a tenth of a turn either way.
     */
    private static final double WAVER = 0.20D;

    /** Steps with nothing further in than here before that is called the head. */
    private static final int STALL = 12;

    /** How deep the channel is cut where it starts, before the ramp towards the mouth. */
    private static final int HEAD_DEPTH = 1;

    /** Half width in blocks at the drop, tapering to nothing at the head. */
    private static final int HALF_WIDTH = 1;

    /**
     * The bed falls at least this much a block even over level ground.
     *
     * <p>Which is what makes the fall visible along the length of a channel rather than only at the two
     * ends of it. Since the course is steered by how far inland it is and not by height, the ground under
     * it no longer descends of its own accord, and at two hundredths a run of forty blocks dropped less
     * than a block over its whole length -- level, to look at. This is a twentieth: two blocks over that
     * same run, on top of the three the depth ramp adds.
     */
    private static final double SLOPE = 0.05D;

    /** Deeper than this and the plan is thrown away rather than cut as a trench. */
    private static final int MAX_CUT = 9;

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

    /**
     * A gap in a column this tall or shorter is something inside the island rather than the end of it.
     *
     * <p>Sized against the carver, not guessed: island tunnels are cut by a noise band whose mouths run to
     * about eight blocks. Anything taller is the sky under the island. Too small and every tunnel reads as
     * the underside again, which is the fault this exists to fix; too large and a channel can be cut across
     * the top of a genuine void and left standing on a bed of its own making.
     */
    private static final int CAVE_SKIP = 8;

    /**
     * How far either side of the height field the real surface is looked for.
     *
     * <p>The three dimensional terms can move it by their amplitude times the layer reach, and normal noise
     * runs past one, so this is about twice the nominal for the two of them together.
     */
    private static final int SLACK = 16;

    /** And how far either side of the last answer it looks before falling back to that wide window. */
    private static final int NEAR = 4;

    /**
     * How close two streams may come before the later of them is dropped.
     *
     * <p>Which is later is decided by the cell hash and not by who was planned first, so every chunk that
     * looks at the pair agrees without having to know what any other chunk did.
     */
    private static final int SEPARATION = 10;

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
    private record Plan(int startX, int startZ, int layer, List<Node> nodes,
                        double minX, double maxX, double minZ, double maxZ) {
        static Plan of(int startX, int startZ, int layer, List<Node> nodes) {
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (Node node : nodes) {
                minX = Math.min(minX, node.x());
                maxX = Math.max(maxX, node.x());
                minZ = Math.min(minZ, node.z());
                maxZ = Math.max(maxZ, node.z());
            }
            return new Plan(startX, startZ, layer, nodes, minX, maxX, minZ, maxZ);
        }
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

    /**
     * A second cache, for plans before the separation rule has looked at them.
     *
     * <p>Deciding whether two streams are too close means planning both, and planning a neighbour must not
     * ask about its neighbours in turn or nothing would ever finish. So there are two rounds: a plan on its
     * own merits, cached here, and then the same plan with its neighbours weighed against it, cached above.
     */
    private static final ThreadLocal<Map<CellKey, Optional<Plan>>> DRAFTS =
        ThreadLocal.withInitial(() -> new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CellKey, Optional<Plan>> eldest) {
                return size() > 256;
            }
        });

    private static Plan cached(FeaturePlaceContext<NoneFeatureConfiguration> context, int cellX, int cellZ) {
        CellKey key = new CellKey(context.level().getSeed(), cellX, cellZ);
        return PLANS.get()
            .computeIfAbsent(key, k -> Optional.ofNullable(settled(context, k)))
            .orElse(null);
    }
    private static Plan draft(FeaturePlaceContext<NoneFeatureConfiguration> context, CellKey key) {
        return DRAFTS.get()
            .computeIfAbsent(key, k -> Optional.ofNullable(plan(context, k)))
            .orElse(null);
    }

    /**
     * A plan, unless a neighbouring one has the better claim to the ground it crosses.
     *
     * <p>Which of a pair gives way is settled by their cell hashes and not by which was worked out first,
     * so every chunk that sees the two of them agrees without needing to know what any other chunk decided.
     * Two channels crossing would each cut the other's bed away and leave both draining sideways.
     */
    private static Plan settled(FeaturePlaceContext<NoneFeatureConfiguration> context, CellKey key) {
        Plan mine = draft(context, key);
        if (mine == null) {
            return null;
        }
        long rank = mix(key.seed() ^ (key.x() * 7919L) ^ (key.z() * 104729L));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                CellKey other = new CellKey(key.seed(), key.x() + dx, key.z() + dz);
                long theirs = mix(other.seed() ^ (other.x() * 7919L) ^ (other.z() * 104729L));
                if (theirs >= rank) {
                    continue;
                }
                Plan near = draft(context, other);
                if (near != null && crosses(mine, near)) {
                    return null;
                }
            }
        }
        return mine;
    }

    /** Whether the two courses are far enough apart that no pair of their nodes could be close. */
    private static boolean apart(Plan mine, Plan other) {
        return mine.minX() - other.maxX() > SEPARATION || other.minX() - mine.maxX() > SEPARATION
            || mine.minZ() - other.maxZ() > SEPARATION || other.minZ() - mine.maxZ() > SEPARATION;
    }

    /** Whether two channels ever come within a channel's width and then some of each other. */
    private static boolean crosses(Plan mine, Plan other) {
        // The boxes first, since almost every pair asked about is two streams on different islands that
        // never come near. Worth little: measured, it moved a survey of two thousand cells from 8.4
        // seconds to 8.1, so the cost of planning is the walking and not this.
        if (apart(mine, other)) {
            return false;
        }
        for (Node node : mine.nodes()) {
            for (Node theirs : other.nodes()) {
                double dx = node.x() - theirs.x();
                double dz = node.z() - theirs.z();
                if (dx * dx + dz * dz < SEPARATION * SEPARATION) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The island, bound to one world, so the planner can ask it about any coordinate.
     *
     * <p>Two surfaces here and the difference between them matters. {@link #heightAt} is the height field
     * alone: smooth, cheap, and the right thing to steer by, since which way an island falls over eight
     * blocks is a question about its shape and not about the texture on it. {@link #trueSurface} is where
     * the generator will actually put the top block, three dimensional terms and caves included, and it is
     * the only thing a channel may be measured against. Planning on the first and cutting against the
     * second is what left every disagreement between them to be papered over by a clamp at carve time, and
     * each of those clamps is a step back up, a source in the wrong place, or a channel that stops early.
     */
    private record Terrain(SkyIslandDensity.Field field, NormalNoise streams) {
        SkyIslandDensity.Ground at(double x, double z, int layer) {
            return SkyIslandDensity.ground(
                this.field.islands(), this.field.ridges(), x, z,
                this.field.bandBottom(), this.field.bandTop(), this.field.layerCount(),
                this.field.threshold(), this.field.horizontalScale(), layer
            );
        }

        double heightAt(double x, double z, int layer) {
            return at(x, z, layer).surfaceY();
        }

        int trueSurface(double x, double z, int layer) {
            return trueSurface(x, z, layer, Integer.MIN_VALUE);
        }

        /**
         * Where the rock actually stops in a column, or MIN_VALUE if this layer has none here.
         *
         * <p>Given where the answer was a block ago, it looks there first. Consecutive steps are one block
         * apart and a surface does not move far in one block, so the near window almost always has it, and
         * the wide one -- sixteen either side of the height field, thirty three probes -- is only paid for
         * when the ground really did jump. This is the cost of the whole feature: every other lookup put
         * together is a fraction of the column scan, and the scan was being done from scratch every step.
         */
        int trueSurface(double x, double z, int layer, int hint) {
            SkyIslandDensity.Ground ground = at(x, z, layer);
            if (!ground.hasGround()) {
                return Integer.MIN_VALUE;
            }
            SkyIslandDensity.Column column = SkyIslandDensity.columnOf(this.field, x, z);
            if (hint != Integer.MIN_VALUE) {
                for (int y = hint + NEAR; y >= hint - NEAR; y--) {
                    if (SkyIslandDensity.density(this.field, column, x, y, z) > 0.0D) {
                        return y;
                    }
                }
            }
            int middle = (int) Math.round(ground.surfaceY());
            for (int y = middle + SLACK; y >= middle - SLACK; y--) {
                if (SkyIslandDensity.density(this.field, column, x, y, z) > 0.0D) {
                    return y;
                }
            }
            return Integer.MIN_VALUE;
        }

        boolean solid(double x, double y, double z) {
            return SkyIslandDensity.density(this.field, x, y, z) > 0.0D;
        }
    }

    private static Terrain terrainOf(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var random = context.level().getLevel().getChunkSource().randomState();
        return new Terrain(
            new SkyIslandDensity.Field(
                random.getOrCreateNoise(SkyIslandDensity.ISLANDS),
                random.getOrCreateNoise(SkyIslandDensity.ISLAND_RIDGES),
                random.getOrCreateNoise(SkyIslandDensity.ISLAND_DETAIL),
                random.getOrCreateNoise(SkyIslandDensity.ISLAND_LANDFORM),
                random.getOrCreateNoise(SkyIslandDensity.ISLAND_CAVES),
                BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
                BarrenSkiesConfig.SKY_ISLAND_TOP.get(),
                BarrenSkiesConfig.ISLAND_LAYERS.get(),
                BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
                BarrenSkiesConfig.ISLAND_SCALE.get(),
                BarrenSkiesConfig.LANDFORM_STRENGTH.get(),
                BarrenSkiesConfig.LANDFORM_SQUASH.get(),
                BarrenSkiesConfig.LANDFORM_NOISE.get(),
                BarrenSkiesConfig.ISLAND_CAVES.get()
            ),
            random.getOrCreateNoise(SkyIslandDensity.ISLAND_STREAMS)
        );
    }

    /** Whether this cell holds a stream at all, and if so where it starts and where it goes. */
    private static Plan plan(FeaturePlaceContext<NoneFeatureConfiguration> context, CellKey key) {
        long hash = mix(key.seed() ^ (key.x() * 341873128712L) ^ (key.z() * 132897987541L));
        if (Math.floorMod(hash, 100L) >= BarrenSkiesConfig.WATERFALL_ISLAND_CHANCE.get()) {
            return null;
        }

        Terrain terrain = terrainOf(context);

        // Outlets first, and the course walked back up from them.
        //
        // The other way round -- scatter heads, walk each one downhill, keep whatever survives -- spends
        // its whole budget before it learns anything. Measured on the version before this one: eight
        // hundred and sixty two channels walked, two hundred and forty eight reached an edge, and then a
        // hundred and seventy of those were thrown away because the edge they reached had desert under it
        // rather than sea. Four fifths of the completed work discarded on a test that costs one lookup and
        // could have been asked first. It could not be asked first, because where a channel comes out is
        // the last thing a descent knows.
        //
        // Starting at the mouth turns that around. Whether there is sea below is settled before a single
        // step is taken. Reaching an edge stops being something to hope for and becomes where the walk
        // begins. And running out of length stops being a failure at all: uphill from a rim is inland, so
        // the walk can simply stop when it has climbed far enough and hand back what it has.
        List<int[]> mouths = new ArrayList<>();
        for (int attempt = 0; attempt < OUTLET_TRIES && mouths.size() < OUTLET_RETRIES; attempt++) {
            long pick = mix(hash + attempt * 0x9E3779B97F4A7C15L);
            int x = key.x() * CELL + (int) Math.floorMod(pick, CELL);
            int z = key.z() * CELL + (int) Math.floorMod(pick >> 20, CELL);
            SkyIslandDensity.Ground ground = terrain.at(x, z, -1);
            // On an island, and near enough its edge that a rim can be found by walking to one.
            if (!ground.hasGround() || ground.covered() || ground.mask() <= 0.0D || ground.mask() > RIM_BAND) {
                continue;
            }
            if (!overWater(context, x, z)) {
                continue;
            }
            mouths.add(new int[] { x, z, ground.layer() });
        }

        for (int[] mouth : mouths) {
            // Out to where the rock actually stops, which is not where the mask says the island ends. The
            // mask crossing zero is where the height field's island ends; the detail and landform noise
            // carry real ground several blocks past that, and stopping at the mask left a channel ending
            // short of a lip it could see. Walked out to the last column with rock in it, and no further.
            int footX = mouth[0];
            int footZ = mouth[1];
            int layer = mouth[2];
            double outX = footX;
            double outZ = footZ;
            double bearing = brink(terrain, footX, footZ, layer);
            for (int step = 0; step < RIM_REACH; step++) {
                // The fall line is re-read as it goes. Held fixed, the walk leaves along whatever bearing
                // the first sample gave and misses a rim that curves away from it, which left one channel
                // in seven ending a few blocks inside ground it could see the end of.
                if (step % 4 == 0) {
                    bearing = brink(terrain, outX, outZ, layer);
                }
                double nx = outX + Math.sin(bearing);
                double nz = outZ + Math.cos(bearing);
                if (terrain.trueSurface(nx, nz, layer) == Integer.MIN_VALUE) {
                    break;
                }
                outX = nx;
                outZ = nz;
            }
            if (!overWater(context, (int) Math.floor(outX), (int) Math.floor(outZ))) {
                continue;
            }

            List<Node> line = ascend(terrain, outX, outZ, layer, bearing, Math.floorMod(hash >> 12, 4096L));
            if (line == null || line.size() - 1 < MIN_RUN || !profile(terrain, line, layer)) {
                continue;
            }
            return Plan.of((int) Math.floor(line.getFirst().x()), (int) Math.floor(line.getFirst().z()), layer, line);
        }
        return null;
    }

    /**

    /** Which way the island falls away here, so the mouth can be walked out to the brink. */
    private static double brink(Terrain terrain, double x, double z, int layer) {
        double bearing = 0.0D;
        double lowest = Double.POSITIVE_INFINITY;
        for (int spoke = 0; spoke < 16; spoke++) {
            double a = spoke / 16.0D * Math.PI * 2.0D;
            double y = terrain.heightAt(x + Math.sin(a) * 12.0D, z + Math.cos(a) * 12.0D, layer);
            if (y < lowest) {
                lowest = y;
                bearing = a;
            }
        }
        return bearing;
    }


    /**
     * Walks inland and uphill from a mouth, and hands back the course head first.
     *
     * <p>The same curve a descent would trace, walked the other way. Which is the point: from a mouth,
     * every direction that matters is towards more island, so the walk cannot run out of land under it and
     * cannot fail to reach an edge -- it started at one. It climbs for as long as it is asked to and stops
     * where the island stops, and either way what comes back is a whole course rather than a wasted one.
     *
     * <p>Null only where the mouth turned out not to be on open ground at all.
     */
    private static List<Node> ascend(
        Terrain terrain, double outX, double outZ, int layer, double bearing, long lane
    ) {
        double px = outX;
        double pz = outZ;
        // Inland is back the way the ground fell away.
        double angle = bearing + Math.PI;
        List<Node> nodes = new ArrayList<>(LENGTH + 1);
        int stalled = 0;
        int lastTop = Integer.MIN_VALUE;

        for (int step = 0; step <= LENGTH; step++) {
            SkyIslandDensity.Ground ground = terrain.at(px, pz, layer);
            if (ground.covered()) {
                break;
            }
            int top = terrain.trueSurface(px, pz, layer, lastTop);
            if (top == Integer.MIN_VALUE) {
                break;
            }
            lastTop = top;
            // The heading recorded is the one the water runs on, which is back down the way this came up.
            nodes.add(new Node(px, pz, angle + Math.PI, top, 0));

            // Inland, by the mask, and nothing else. Which way is up is the wrong question to steer by
            // and both answers to it fail: the steepest climb takes the ridge between two valleys, and
            // the gentlest -- on any even slope, which is what the whole outside of an island is --
            // takes the line that contours along it without climbing at all. From a rim, contouring is
            // following the rim. Measured, the course sat at mask 0.046 for its whole length, which is
            // to say a few blocks inside the edge from end to end, pouring over the side as it went.
            //
            // The mask has none of that trouble. It rises towards the middle of an island from every
            // direction and has exactly one thing to say, which is where the inside is. Where the course
            // sits across the valley is not this job at all -- that is the lean below, which is the only
            // part that should be reading heights.
            double inland = ground.mask();
            double bestTurn = 0.0D;
            double furthest = inland;
            for (double turn : FAN) {
                double m = terrain.at(
                    px + Math.sin(angle + turn) * FAN_REACH, pz + Math.cos(angle + turn) * FAN_REACH, layer
                ).mask();
                if (m > furthest) {
                    furthest = m;
                    bestTurn = turn;
                }
            }
            if (furthest <= inland) {
                // No way on is further in than this. The middle of the island, near enough, and the
                // place a stream starts.
                if (++stalled >= STALL) {
                    break;
                }
            } else {
                stalled = 0;
                angle += bestTurn * FOLLOW;
            }
            // A waver, applied to where this step goes without being kept. Added into the heading it was
            // integrated instead, and a noise this slow integrated over a hundred steps is not a waver at
            // all but a long steady curve away from the valley the walk was meant to be following.
            double wobble = terrain.streams().getValue(step * 0.28D, lane, 0.0D) * WAVER;
            px += Math.sin(angle + wobble);
            pz += Math.cos(angle + wobble);

            // Then sideways, into whichever flank is lower. A valley is a low across the line of travel,
            // and nothing that only compares directions ahead can find one: on an even slope the gentlest
            // way up is the one that contours along it, which is neither climbing a wall nor lying in a
            // trough. Measured, the course was running about a third of a block above the ground either
            // side of it. This is the only part of the walk that looks across itself, and it is what puts
            // the channel in the bottom of something rather than across the face of it.
        }
        if (nodes.size() - 1 < MIN_RUN) {
            return null;
        }
        java.util.Collections.reverse(nodes);
        return nodes;
    }

    /** Whether a bed at this elevation has the rock under it to hold water. */
    private static boolean footed(Terrain terrain, Node node, double bed) {
        int floor = (int) Math.floor(bed);
        for (int under = 1; under <= FLOOR_KEEP; under++) {
            if (!terrain.solid(node.x(), floor - under, node.z())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turns the centre line into a channel, in place: how wide it is at each node and where its floor sits.
     *
     * <p>Nodes come in carrying the ground height in the bed field and go out carrying the bed itself, which
     * is a running minimum of the ground less the depth wanted there. Shallow and narrow at the head, deep
     * and wide at the mouth, and never rising anywhere along the way.
     *
     * @return false if the channel would have to cut deeper than a stream plausibly could
     */
    private static boolean profile(Terrain terrain, List<Node> nodes, int layer) {
        int run = nodes.size() - 1;
        int maxDepth = BarrenSkiesConfig.STREAM_DEPTH.get();
        double bed = nodes.getFirst().bed() - HEAD_DEPTH + SLOPE;

        for (int step = 0; step <= run; step++) {
            Node node = nodes.get(step);
            double along = step / (double) run;
            // A groove at the head, a channel at the mouth. It began at two and the head read as a trench
            // that happened to start somewhere rather than as a stream beginning.
            double want = HEAD_DEPTH + (maxDepth - HEAD_DEPTH) * along;
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
            // The water has to sit on rock, and on enough of it. Checked against the same field the
            // generator builds from, so a cave under the channel or a rim too thin to hold one is known
            // now, rather than at carve time, where the only answers left are to raise this one column,
            // which is a step back up, or to skip it, which is a pillar or a channel ending in open ground.
            //
            // Where there is none, the bed drops to find some, as far as the cut cap allows: deeper is
            // always permitted -- it is up that the rule forbids -- so a shallow hollow under the channel
            // is passed by running along the bottom of it, which is what water meeting one would do.
            //
            // Where even that finds nothing the plan is kept anyway and the carve leaves those columns
            // uncut. Refusing it outright threw away a hundred and nineteen of the hundred and forty four
            // channels that had reached an edge, since caves sit at exactly the depth a stream is cut to
            // and almost every run meets one somewhere.
            double lowest = node.bed() - MAX_CUT;
            while (bed > lowest && !footed(terrain, node, bed)) {
                bed -= 1.0D;
            }
            if (bed < lowest) {
                bed = lowest;
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
                    // The first ring shares the middle's floor rather than stepping up from it, so the
                    // bottom of the channel is flat and as wide as the channel is: one column at the head,
                    // where the half width is nothing, and three by the mouth.
                    //
                    // Taken from the plan alone. It used to carry the deepest floor anything had asked
                    // for so far and cap every later column at it, on the grounds that the clamp in claim
                    // only ever lowers a floor and a lowered one would otherwise be a step back up. But
                    // the clamp lowers a floor to just under the ground, and the ground drops away hard
                    // approaching a rim -- so one column near the mouth pinned every column after it far
                    // below the island, the bed fill gave each of them a block to stand on, and the run
                    // came out as a tongue of stone hanging off the underside at every mouth. The plan's
                    // bed is already a running minimum. Carrying a second one over the top of it, built
                    // from the ground rather than from the plan, only ever dragged the channel down.
                    int rung = Math.min(node.half(), (int) Math.round(away));
                    int drop = Math.max(0, rung - 1);
                    if (!claim(columns, slot, (int) Math.floor(node.bed()) + drop)) {
                        continue;
                    }
                    if (drop == 0) {
                        // Water across the floor, and only the floor. A source on a bank sits a block
                        // above the one beside it and spills sideways out of the channel.
                        columns.wet()[slot] = step <= lastWet;
                    }
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
        if (surface == NO_SURFACE) {
            return false;
        }
        // Downward only. The plan was checked against the field the generator builds from, so it already
        // knows this column has rock to spare; the only clamp left is against the block interpolation,
        // which moves a surface by about one. Cutting a block deeper than asked is harmless. Raising the
        // floor is not, and every version of this that was allowed to raise it put a step back up in the
        // middle of a stream.
        int floor = Math.min(wants, surface - 1);
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
     * Finds the top of the island rock in a column and where the island under it ends.
     *
     * <p>Gaps narrower than a cave mouth are stepped over rather than treated as the bottom. Stopping at
     * the first one, which is what this used to do, does not find the underside of the island: it finds the
     * roof of whatever cave happens to be under that column. Everything downstream reads it as the island
     * being thin there. A column with a tunnel four blocks beneath it then refuses to be cut, and stands
     * proud in the middle of a channel its neighbours were cut nine blocks into -- and the pass that closes
     * pillars cannot help, because that column was claimed, just at a hopeless floor. The same wrong answer
     * at a rim measures the rock to keep from a cave ceiling rather than from the underside, so the cut
     * goes through what was below it after all. Both of the things still being seen, from one bad number.
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
        int gap = 0;
        for (int y = surface - 1; y >= bandBottom && gap <= CAVE_SKIP; y--) {
            pos.set(x, y, z);
            if (level.getBlockState(pos).isSolid()) {
                bottom = y;
                gap = 0;
            } else {
                gap++;
            }
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
     * <p>A column whose floor has nothing to stand on is left alone rather than given a block to stand on.
     * Laying one was meant for the roof of a cave, and measured over five hundred streams it was almost
     * never used for that: six hundred and seventy five beds laid across twenty four thousand nodes, and
     * six hundred and seventy four of them in the last ten nodes of a run. Which is to say it fired at the
     * mouth and essentially nowhere else, and at a mouth there is nothing underneath but sky, so each one
     * hung a block off the underside and together they made the tongue of stone standing off every rim.
     *
     * <p>Skipping them instead ends the channel a block or two inside the brink, at the last column with
     * rock under it, and the water goes over the rock rather than over something built for it.
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
                pos.set(x, floor - 1, z);
                if (!level.getBlockState(pos).isSolid()) {
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
