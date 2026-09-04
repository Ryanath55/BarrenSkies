package com.barrenskies.worldgen.compat;

import com.barrenskies.BarrenSkies;
import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.mixin.ChunkGeneratorAccessor;
import com.barrenskies.worldgen.LayeredBiomeSource;
import com.barrenskies.worldgen.sky.SkyIslandChunkGenerator;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Takes the last word on biome placement back after another mod has taken it.
 *
 * <p>Blueprint replaces the chunk generator's biome source with its own wrapper just before the server
 * loads its levels, and it is not alone in doing that. Whoever wraps last decides every lookup, so this
 * runs at the top of the load itself -- after any wrapping done at the call site above it, and before the
 * levels are built and the first chunk is generated -- and wraps once more, with a source that reapplies
 * the layer rules to whatever answer comes back.
 *
 * <p>Nothing is unwrapped or removed. The other mod keeps its slot and keeps choosing; the difference is
 * that its choice is now subject to the same rules as everyone else's.
 */
public final class BiomeSourceOwnership {
    public static void reclaim(MinecraftServer server) {
        if (!BarrenSkiesConfig.RECONCILE_MODDED_PLACEMENT.get()) {
            return;
        }

        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stem : stems.entrySet()) {
            ChunkGenerator generator = stem.getValue().generator();
            if (!(generator instanceof SkyIslandChunkGenerator sky)
                || !(sky.declaredBiomeSource() instanceof LayeredBiomeSource rules)) {
                continue;
            }

            BiomeSource installed = generator.getBiomeSource();
            // Nobody wrapped us, or we already did this. Either way there is nothing to reconcile.
            if (installed == rules || installed instanceof ReconciledBiomeSource) {
                continue;
            }

            ((ChunkGeneratorAccessor) generator).barrenskies$setBiomeSource(new ReconciledBiomeSource(installed, rules));
            BarrenSkies.LOG.info(
                "The biome source for {} was replaced by {}; the layer rules have been reapplied on top of it, "
                    + "so the biomes it places are held to the same climate rules as everything else.",
                stem.getKey().location(),
                installed.getClass().getName()
            );
        }
    }

    private BiomeSourceOwnership() {
    }
}
