package com.barrenskies.worldgen.feature;

import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import com.mojang.serialization.Codec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Runs the biome's own ore over again, upside down, into the islands.
 *
 * <p>It places nothing itself. Every vein it puts in an island is placed by the ore feature that would have
 * placed it underground, with its own vein size, its own count, its own rarity roll and its own rule about
 * what stone it will replace; the only thing changed is the height it is handed, and that is changed by
 * OrePlacementMixin rather than here. Doing it this way is what makes the feature indifferent to which ores
 * exist: a modpack that adds its own is mirrored with the rest, because the list comes from the biome and
 * not from us.
 *
 * <p>The biome filter each ore already carries does the sorting. Emerald is offered to every island and
 * accepted only over the ones whose biome asks for emerald, exactly as it is underground, so an island
 * keeps the ore of the biome it actually is rather than the ore of the sea floor beneath it.
 */
public class IslandOreFeature extends Feature<NoneFeatureConfiguration> {
    public IslandOreFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        int rarity = BarrenSkiesConfig.ISLAND_ORE_RARITY.get();
        if (rarity <= 0 || MirroredOres.mirroring()) {
            // Off, or this is the mirrored pass reaching its own entry in the biome's list.
            return false;
        }

        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        if (!hasIsland(level, origin)) {
            return false;
        }

        List<Holder<PlacedFeature>> ores = oresOver(level, context.chunkGenerator(), origin);
        if (ores.isEmpty()) {
            return false;
        }

        RandomSource random = context.random();
        double each = rarity / 100.0D * MirroredOres.scale();
        boolean placed = false;
        MirroredOres.begin(
            context.chunkGenerator(), level, level.getLevel().getChunkSource().randomState());
        try {
            for (Holder<PlacedFeature> ore : ores) {
                int times = times(each, random);
                for (int i = 0; i < times; i++) {
                    placed |= ore.value().placeWithBiomeCheck(level, context.chunkGenerator(), random, origin);
                }
            }
        } finally {
            MirroredOres.end();
        }
        return placed;
    }

    /**
     * How many times to run one ore feature, for a multiplier that is rarely a whole number.
     *
     * <p>Rounded by chance rather than to the nearest, so that two thirds of a run means two runs in three
     * chunks instead of either one or none in all of them. A single ore rolled this way is lumpy, but a
     * biome has twenty of them and a player sees the sum.
     */
    private static int times(double each, RandomSource random) {
        int whole = (int) each;
        return whole + (random.nextDouble() < each - whole ? 1 : 0);
    }

    /**
     * Whether this chunk has any island in it at all, asked of the heightmap rather than of the noise.
     *
     * <p>Most chunks have none, and for those this is the whole cost of the feature. The heightmap is built
     * with the terrain and reports the top of the island where there is one, so a column reading above the
     * island floor is a column with island in it.
     */
    private static boolean hasIsland(WorldGenLevel level, BlockPos origin) {
        int floor = SkyIslandDensity.islandFloor();
        for (int dx = 0; dx < 16; dx += 4) {
            for (int dz = 0; dz < 16; dz += 4) {
                if (level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX() + dx, origin.getZ() + dz)
                    > floor) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The ore the biomes over this chunk generate, gathered the same way the chunk generator gathers it.
     *
     * <p>A union across the band rather than one lookup, because a chunk can hold islands of several biomes
     * at several heights and each brings its own ores. Being generous here costs nothing: an ore offered to
     * an island whose biome does not want it is refused by its own biome filter when it comes to be placed.
     */
    private static List<Holder<PlacedFeature>> oresOver(
        WorldGenLevel level, ChunkGenerator generator, BlockPos origin
    ) {
        int step = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        Set<Holder<PlacedFeature>> found = new LinkedHashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 2; dx < 16; dx += 6) {
            for (int dz = 2; dz < 16; dz += 6) {
                for (int y = SkyIslandDensity.islandFloor(); y <= SkyIslandDensity.islandCeiling(); y += 48) {
                    pos.set(origin.getX() + dx, y, origin.getZ() + dz);
                    List<HolderSet<PlacedFeature>> features =
                        generator.getBiomeGenerationSettings(level.getBiome(pos)).features();
                    if (step < features.size()) {
                        features.get(step).forEach(found::add);
                    }
                }
            }
        }
        found.removeIf(ore -> ore.value().feature().value().feature() instanceof IslandOreFeature);
        return List.copyOf(found);
    }
}
