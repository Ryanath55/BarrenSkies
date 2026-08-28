package com.barrenskies.datagen;

import com.barrenskies.BarrenSkies;
import com.barrenskies.worldgen.BarrenSkiesWorldgen;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = BarrenSkies.MOD_ID)
public final class BarrenSkiesDataGen {
    private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
        .add(Registries.DIMENSION_TYPE, BarrenSkiesWorldgen::bootstrapDimensionTypes)
        .add(Registries.NOISE_SETTINGS, BarrenSkiesWorldgen::bootstrapNoiseSettings)
        .add(Registries.WORLD_PRESET, BarrenSkiesWorldgen::bootstrapWorldPresets);

    @SubscribeEvent
    public static void gather(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        DatapackBuiltinEntriesProvider entries = new DatapackBuiltinEntriesProvider(output, lookup, BUILDER, Set.of(BarrenSkies.MOD_ID));
        generator.addProvider(event.includeServer(), entries);
        // The tag references the preset this provider generates, so it needs the patched lookup rather than the vanilla one.
        generator.addProvider(event.includeServer(), new PresetTagProvider(output, entries.getRegistryProvider(), event.getExistingFileHelper()));
    }

    private static final class PresetTagProvider extends TagsProvider<WorldPreset> {
        PresetTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existingFileHelper) {
            super(output, Registries.WORLD_PRESET, lookup, BarrenSkies.MOD_ID, existingFileHelper);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            this.tag(WorldPresetTags.NORMAL).add(BarrenSkiesWorldgen.WORLD_PRESET);
        }
    }

    private BarrenSkiesDataGen() {
    }
}
