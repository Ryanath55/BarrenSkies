package com.barrenskies.worldgen.compat;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

/** Touching this class loads TerraBlender types, so only reach it through {@link ModdedBiomes}. */
final class TerraBlenderHooks {
    static List<Pair<Climate.ParameterPoint, Holder<Biome>>> collect(HolderGetter<Biome> getter, Registry<Biome> registry) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> out = new ArrayList<>();
        for (Region region : Regions.get(RegionType.OVERWORLD)) {
            region.addBiomes(registry, pair -> {
                if (pair.getSecond().equals(Region.DEFERRED_PLACEHOLDER)) {
                    return;
                }
                getter.get(pair.getSecond()).ifPresent(holder -> out.add(Pair.of(pair.getFirst(), (Holder<Biome>) holder)));
            });
        }
        return out;
    }

    private TerraBlenderHooks() {
    }
}
