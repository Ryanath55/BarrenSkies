package com.barrenskies.worldgen.compat;

import com.barrenskies.BarrenSkies;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Collects biome/climate pairs contributed by other worldgen mods. */
public final class ModdedBiomes {
    public static List<Pair<Climate.ParameterPoint, Holder<Biome>>> collect(HolderGetter<Biome> getter) {
        if (!ModList.get().isLoaded("terrablender")) {
            return List.of();
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            BarrenSkies.LOG.warn("TerraBlender is present but no server is running yet, so its biomes were skipped this time.");
            return List.of();
        }

        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        try {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> pairs = TerraBlenderHooks.collect(getter, registry);
            BarrenSkies.LOG.info("Picked up {} biome entries from TerraBlender regions.", pairs.size());
            return pairs;
        } catch (Throwable t) {
            BarrenSkies.LOG.error("Failed to read TerraBlender regions; continuing with vanilla biomes only.", t);
            return List.of();
        }
    }

    private ModdedBiomes() {
    }
}
