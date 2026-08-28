package com.barrenskies.client;

import com.barrenskies.BarrenSkies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = BarrenSkies.MOD_ID, dist = Dist.CLIENT)
public class BarrenSkiesClient {
    public BarrenSkiesClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
