package com.barrenskies;

import com.barrenskies.worldgen.compat.ReconciledBiomeSource;
import com.barrenskies.worldgen.sky.SkyIslandDensity;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Counts which biomes the world places where a player would actually stand, and on the islands above.
 * Off unless asked for.
 *
 * <p>Run with -Dbarrenskies.census=N, or gradlew runServer -Pcensus=N, to survey an N thousand block
 * square around the origin. The whole premise of the mod is a claim about which biomes reach which layer,
 * and that claim is cheap to check directly rather than by flying around reading the F3 screen.
 *
 * <p>Each ground sample is taken at the height of the ground in that column, not at a fixed height. It
 * has to be: the climate depth axis is measured from the surface, so a fixed height is underground in
 * one column and open air in the next, and a survey taken at one reads as a mixture of both.
 *
 * <p>Written for the compat work. A mod that places biomes by wrapping the biome source, as Blueprint
 * does for Team Abnormals' biomes, shows up here as its own namespace appearing in the ground list, which
 * is the failure the layer rules exist to prevent.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = BarrenSkies.MOD_ID)
public final class BiomeCensus {
    private BiomeCensus() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        int side = Integer.getInteger("barrenskies.census", 0);
        if (side <= 0) {
            return;
        }

        ServerLevel level = event.getServer().overworld();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeSource source = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();

        // The ground, never an island: the same ceiling the structure placement uses, so a column with an
        // island over it still reports the ground under it.
        LevelHeightAccessor ground = LevelHeightAccessor.create(
            level.getMinBuildHeight(), SkyIslandDensity.islandFloor() - level.getMinBuildHeight()
        );

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> replaced = new TreeMap<>();
        Map<String, Integer> depths = new TreeMap<>();
        int half = side * 500;
        int step = 256;
        for (int x = -half; x <= half; x += step) {
            for (int z = -half; z <= half; z += step) {
                int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ground, randomState);
                int quartY = QuartPos.fromBlock(y);
                Holder<Biome> placed = source.getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z), sampler);
                count(counts, placed);
                // What the layer rules took out, when another mod is placing biomes underneath them. The
                // list is the answer to whether the rules are working, and to which of that mod's biomes
                // the barren surface is quietly costing you.
                if (source instanceof ReconciledBiomeSource reconciled) {
                    Holder<Biome> before = reconciled.delegate()
                        .getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z), sampler);
                    if (before != placed) {
                        count(replaced, before);
                    }
                }
                // What the depth axis reads at the surface itself, which is the number the rules are
                // written against and the one worth being sure of.
                double depth = Climate.unquantizeCoord(
                    sampler.sample(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z)).depth()
                );
                depths.merge(String.format("%+.1f", Math.floor(depth * 10) / 10), 1, Integer::sum);
            }
        }
        report("ground", counts);
        report("replaced on the ground", replaced);
        BarrenSkies.LOG.info("[census] depth at ground level: {}", depths);

        Map<String, Integer> sky = new TreeMap<>();
        int islandY = SkyIslandDensity.islandFloor() + 32;
        for (int x = -half; x <= half; x += 64) {
            for (int z = -half; z <= half; z += 64) {
                count(sky, source.getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(islandY), QuartPos.fromBlock(z), sampler
                ));
            }
        }
        report("islands", sky);
    }

    private static void count(Map<String, Integer> counts, Holder<Biome> biome) {
        counts.merge(name(biome), 1, Integer::sum);
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("?");
    }

    private static void report(String label, Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        BarrenSkies.LOG.info("[census] {}: {} biomes over {} samples", label, counts.size(), total);
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> BarrenSkies.LOG.info(
                "[census]   {} {} {} ({}%)", label, entry.getKey(), entry.getValue(), entry.getValue() * 100 / Math.max(1, total)
            ));
    }
}
