package com.barrenskies;

import com.barrenskies.worldgen.BarrenSkiesWorldgen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(BarrenSkies.MOD_ID)
public class BarrenSkies {
    public static final String MOD_ID = "barrenskies";
    public static final Logger LOG = LoggerFactory.getLogger("BarrenSkies");

    public BarrenSkies(IEventBus modBus, ModContainer container) {
        BarrenSkiesWorldgen.register(modBus);
        com.barrenskies.worldgen.feature.BarrenSkiesFeatures.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, BarrenSkiesConfig.SPEC);
    }
}
