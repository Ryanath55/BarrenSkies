package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkies;
import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The ground's ore range turned upside down and fitted to the island band.
 *
 * <p>Ore is written against absolute heights and nothing else: diamond belongs to the bottom of the world,
 * coal and copper to the top of it, iron and gold in between. Islands sit four hundred blocks above all of
 * that, in a band the ore rules have nothing to say about, so left alone they get whatever few veins happen
 * to have a range reaching that high and nothing else.
 *
 * <p>Mirroring rather than copying, so that height still means something. Copied straight up, every island
 * would carry the same mixture and the only way to find diamond would be to go back down. Flipped, the
 * bottom of the world becomes the top of the sky: the highest islands hold what the deepest stone held, the
 * lowest hold what lies just under a meadow, and an island's altitude is worth reading again.
 *
 * <p>Both sides are measured, and the mapping runs between the two measurements rather than between two
 * heights. Rock is what an ore vein needs and rock is what is counted: the deepest tenth of the ground's
 * stone is put where the highest tenth of the island's stone is, wherever either of those happen to be.
 *
 * <p>Measuring the ground is not fussiness, it is the whole correction. Coal and iron are rolled in
 * enormous numbers across ranges that run hundreds of blocks above where any ground exists -- upper coal to
 * 320, upper iron to 384 -- and the counts are large precisely because most of those rolls are thrown into
 * open air and wasted. Mirror the count without mirroring the waste and the islands are drowned in coal:
 * measured that way it arrived at three times its underground rate while diamond arrived at a tenth of
 * its own. So a roll is kept here only as often as the ground would have kept it, and the inflation
 * cancels itself.
 */
public final class MirroredOres {
    /**
     * The range the ground's ore is written against: the floor of the world to the top of a vanilla
     * overworld.
     *
     * <p>Not the top of this world, which is three hundred blocks higher and is where the islands are. Ore
     * ranges were written for a world ending at 320 and every anchor in them is either absolute or measured
     * from the floor, so 320 is the number they mean by the top even here.
     */
    private static final int GROUND_BOTTOM = -64;
    private static final int GROUND_TOP = 320;

    /** Columns a side used to measure where island rock is. Four noise lookups each and a column walk. */
    private static final int ISLAND_SAMPLE_SIDE = 24;

    /** Columns a side used to measure where the ground stops. A whole noise column each, so far fewer. */
    private static final int GROUND_SAMPLE_SIDE = 12;

    /** How finely the rock is divided. One step is half a percent. */
    private static final int QUANTILES = 200;

    /**
     * How much of the top of an island is grass, dirt and sand rather than stone ore can replace.
     *
     * <p>Left in, the highest slice of the rock is counted as somewhere ore can go and it is not, and the
     * ore aimed at it is the ore aimed deepest -- diamond, gold, redstone. They were being sent to the one
     * part of an island that cannot hold them.
     */
    private static final int SURFACE_SKIN = 4;

    /**
     * How much of a mirrored vein survives an island edge.
     *
     * <p>The last measured number, and the only one left that is not read off the world. A vein aimed into
     * island stone still finds an edge often enough to lose part of itself, where the same vein underground
     * would have been surrounded. Near enough to one that it is a correction rather than an answer, which
     * is the difference between this and the single constant it replaced.
     *
     * <p>At 0.835 the whole table measures 93 percent of the ground where the setting asks for 100, with
     * individual ores between 46 and 170 percent of their own underground rate.
     */
    private static final double ISLAND_VEIN_YIELD = 0.835D;

    /** What islandY answers for a roll the ground would have wasted, and so one the sky does not get. */
    public static final int NOWHERE = Integer.MIN_VALUE;

    private static final ThreadLocal<Boolean> MIRRORING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Both sides of the mirror, measured in the world they belong to.
     *
     * @param byQuantile the height at which each two hundredth of the island's stone has been passed,
     *     counting down from the top, so entry zero is the top of the stone and the last is the bottom
     * @param groundRockAbove for each height, how much of the ground has stone there rather than open air.
     *     A roll is kept this often, which is exactly how often the ground would have kept it
     * @param groundDepth for each height, how much of the ground's stone lies below it, from zero at the
     *     world floor to one at the surface. This is what maps onto the island quantiles
     * @param islandRockPerColumn stone in a column that has any island over it, which says how much ore an
     *     island chunk can take. Averaged over the empty sky as well it would answer a different question
     *     -- how common islands are -- and halving how many there are does not make the rest any poorer
     * @param groundRockPerColumn stone in a column of the barren ground, the same quantity to compare with
     */
    private record Calibration(
        Object key,
        int[] byQuantile,
        float[] groundRockAbove,
        float[] groundDepth,
        double islandRockPerColumn,
        double groundRockPerColumn
    ) {
    }

    private static volatile Calibration CALIBRATION;

    private MirroredOres() {
    }

    /**
     * Whether the ore being placed on this thread right now is the islands' copy rather than the ground's.
     *
     * <p>A thread local for the same reason the structure intent is one: the height an ore vein is given is
     * chosen several calls below anything that knows which pass it belongs to, and threading a flag through
     * would mean changing a signature every feature in the game implements.
     */
    public static boolean mirroring() {
        return MIRRORING.get();
    }

    /**
     * Starts the islands' own pass, having first made sure the world has been measured.
     *
     * <p>Measured here rather than at world load because here is the first place with everything needed to
     * measure from, and because a world generated nowhere near an island never pays for it at all.
     */
    public static void begin(ChunkGenerator generator, LevelHeightAccessor level, RandomState randomState) {
        calibrate(generator, level, randomState);
        MIRRORING.set(Boolean.TRUE);
    }

    public static void end() {
        MIRRORING.remove();
    }

    /**
     * Where a vein rolled for this depth belongs in the sky, or nowhere at all.
     *
     * <p>Nowhere as often as the ground would have wasted it. A roll above the ground surface finds air and
     * places nothing down there, and the ranges that do this do it constantly -- half of every diamond roll
     * is below the floor of the world, nearly every upper coal roll is above the top of the terrain. Those
     * counts were written large to survive the waste, so keeping the count while removing the waste is what
     * buries an island in coal.
     *
     * <p>What survives is placed by stone rather than by height. A vein a tenth of the way up the ground's
     * stone is put a tenth of the way down the island's stone, so a tenth of the ore goes into a tenth of
     * the rock however either of them is spread.
     */
    public static int islandY(int groundY, RandomSource random) {
        if (groundY < GROUND_BOTTOM || groundY > GROUND_TOP) {
            return NOWHERE;
        }
        Calibration measured = CALIBRATION;
        if (measured == null) {
            int floor = SkyIslandDensity.islandFloor();
            int ceiling = SkyIslandDensity.islandCeiling();
            double flat = (double) (groundY - GROUND_BOTTOM) / (GROUND_TOP - GROUND_BOTTOM);
            return ceiling - (int) Math.round(flat * (ceiling - floor));
        }

        int index = groundY - GROUND_BOTTOM;
        if (random.nextFloat() >= measured.groundRockAbove()[index]) {
            return NOWHERE;
        }

        int[] table = measured.byQuantile();
        double at = measured.groundDepth()[index] * QUANTILES;
        int step = Math.min(QUANTILES - 1, (int) at);
        return (int) Math.round(Mth.lerp(at - step, table[step], table[step + 1]));
    }

    /**
     * How many times over to run one ore feature, before the setting is applied.
     *
     * <p>A vein underground has one column of stone to hit and hits it as often as there is stone there,
     * which the survival roll already accounts for. A vein in the sky is aimed at a height rather than at
     * an island, and at any given height most of the band is open air, so it lands only where an island
     * happens to reach. That is what this makes up for: the taller the stretch of sky the ore is spread
     * through, the more rolls it takes to fill it, and the more stone the ground holds the fewer each of
     * its own veins was worth.
     *
     * <p>It follows the islands rather than being set against them. Thicker islands catch proportionally
     * more of the same rolls; a taller band needs proportionally more of them; neither changes how much
     * ore a block of island stone ends up holding.
     */
    public static double scale() {
        Calibration measured = CALIBRATION;
        if (measured == null || measured.groundRockPerColumn() <= 0.0D) {
            return 0.25D;
        }
        int[] table = measured.byQuantile();
        return (table[0] - table[QUANTILES]) / measured.groundRockPerColumn() * ISLAND_VEIN_YIELD;
    }

    private static void calibrate(
        ChunkGenerator generator, LevelHeightAccessor level, RandomState randomState
    ) {
        Calibration current = CALIBRATION;
        if (current != null && current.key() == randomState) {
            return;
        }
        long began = System.nanoTime();

        int floor = SkyIslandDensity.islandFloor();
        int ceiling = SkyIslandDensity.islandCeiling();
        int[] islandRock = new int[ceiling - floor + 1];
        long islandTotal = 0L;
        int islandColumns = 0;

        SkyIslandDensity.Field field = new SkyIslandDensity.Field(
            randomState.getOrCreateNoise(SkyIslandDensity.ISLANDS),
            randomState.getOrCreateNoise(SkyIslandDensity.ISLAND_RIDGES),
            randomState.getOrCreateNoise(SkyIslandDensity.ISLAND_DETAIL),
            randomState.getOrCreateNoise(SkyIslandDensity.ISLAND_LANDFORM),
            SkyIslandDensity.bandBottom(),
            SkyIslandDensity.bandTop(),
            BarrenSkiesConfig.ISLAND_LAYERS.get(),
            BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
            BarrenSkiesConfig.ISLAND_SCALE.get(),
            BarrenSkiesConfig.LANDFORM_STRENGTH.get(),
            BarrenSkiesConfig.LANDFORM_SQUASH.get(),
            BarrenSkiesConfig.LANDFORM_NOISE.get()
        );

        for (int i = 0; i < ISLAND_SAMPLE_SIDE; i++) {
            for (int j = 0; j < ISLAND_SAMPLE_SIDE; j++) {
                double x = i * 149.0D;
                double z = j * 173.0D;
                SkyIslandDensity.Column column = SkyIslandDensity.columnOf(field, x, z);
                boolean any = false;
                int into = 0;
                for (int y = ceiling; y >= floor; y -= 2) {
                    if (SkyIslandDensity.density(field, column, x, y, z) <= 0.0D) {
                        into = 0;
                        continue;
                    }
                    into += 2;
                    if (into > SURFACE_SKIN) {
                        islandRock[y - floor] += 2;
                        islandTotal += 2L;
                        any = true;
                    }
                }
                islandColumns += any ? 1 : 0;
            }
        }

        // Where the barren ground stops, asked of the generator a whole noise column at a time. Far fewer
        // samples than the island side because each one costs enormously more, and far fewer are needed:
        // this is one number a column against a whole profile.
        int span = GROUND_TOP - GROUND_BOTTOM + 1;
        float[] groundRockAbove = new float[span];
        float[] groundDepth = new float[span];
        int groundColumns = GROUND_SAMPLE_SIDE * GROUND_SAMPLE_SIDE;
        long groundTotal = 0L;
        for (int i = 0; i < GROUND_SAMPLE_SIDE; i++) {
            for (int j = 0; j < GROUND_SAMPLE_SIDE; j++) {
                int surface = generator.getBaseHeight(
                    i * 311, j * 337, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                int top = Mth.clamp(surface, GROUND_BOTTOM, GROUND_TOP);
                for (int y = GROUND_BOTTOM; y < top; y++) {
                    groundRockAbove[y - GROUND_BOTTOM] += 1.0F;
                    groundTotal++;
                }
            }
        }

        double cumulative = 0.0D;
        for (int y = 0; y < span; y++) {
            cumulative += groundRockAbove[y];
            groundDepth[y] = groundTotal == 0L ? 0.0F : (float) (cumulative / groundTotal);
            groundRockAbove[y] /= groundColumns;
        }

        int[] byQuantile = new int[QUANTILES + 1];
        if (islandTotal == 0L) {
            // No islands anywhere near. Nothing will ask for a height either, but a caller who does gets
            // the band itself rather than an empty table.
            for (int q = 0; q <= QUANTILES; q++) {
                byQuantile[q] = ceiling - (ceiling - floor) * q / QUANTILES;
            }
        } else {
            int top = ceiling;
            while (top > floor && islandRock[top - floor] == 0) {
                top--;
            }
            long seen = 0L;
            int q = 0;
            for (int y = top; y >= floor && q <= QUANTILES; y--) {
                while (q <= QUANTILES && seen >= (double) islandTotal * q / QUANTILES) {
                    byQuantile[q++] = y;
                }
                seen += islandRock[y - floor];
            }
            while (q <= QUANTILES) {
                byQuantile[q++] = floor;
            }
        }

        double islandRockPerColumn = islandColumns == 0 ? 0.0D : (double) islandTotal / islandColumns;
        double groundRockPerColumn = (double) groundTotal / groundColumns;
        CALIBRATION = new Calibration(
            randomState, byQuantile, groundRockAbove, groundDepth, islandRockPerColumn, groundRockPerColumn);
        BarrenSkies.LOG.info(
            "Ore mirror measured in {} ms. Island stone: {} of {} columns have any, holding {} blocks each. "
                + "Ground stone: {} blocks a column. The deepest ground lands at Y {}, the half way mark at "
                + "Y {} and the shallowest at Y {}, and each ore runs {} times a chunk before the setting.",
            (System.nanoTime() - began) / 1_000_000L,
            islandColumns, ISLAND_SAMPLE_SIDE * ISLAND_SAMPLE_SIDE,
            String.format("%.1f", islandRockPerColumn), String.format("%.1f", groundRockPerColumn),
            byQuantile[0], byQuantile[QUANTILES / 2], byQuantile[QUANTILES],
            String.format("%.3f", scale()));
    }
}
