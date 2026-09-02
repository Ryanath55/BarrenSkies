package com.barrenskies.worldgen;

import com.barrenskies.BarrenSkies;
import com.barrenskies.worldgen.feature.IslandStreamFeature;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Drops what the mod is holding for a world when that world goes away.
 *
 * <p>The stream planner keeps a bounded cache of courses on each worldgen thread, and that pool outlives
 * any one world. Without this, leaving a save and joining another leaves every one of those threads still
 * holding a few hundred courses belonging to a world nobody is in. Bounded rather than leaking, but held
 * for no reason, and the sort of thing that shows up in a heap dump looking much worse than it is.
 */
@EventBusSubscriber(modid = BarrenSkies.MOD_ID)
public final class LevelLifecycle {
    private LevelLifecycle() {
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel) {
            IslandStreamFeature.forget();
        }
    }
}
