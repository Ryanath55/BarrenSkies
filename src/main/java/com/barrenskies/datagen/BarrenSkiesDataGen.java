package com.barrenskies.datagen;

import com.barrenskies.BarrenSkies;
import com.barrenskies.worldgen.BarrenSkiesTags;
import com.barrenskies.worldgen.BarrenSkiesWorldgen;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.biome.Biome;
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
        generator.addProvider(event.includeServer(), new BiomeTagProvider(output, lookup, event.getExistingFileHelper()));
    }

    /** Biomes other mods intend to float in the sky, which the climate rules would otherwise leave on the ground. */
    private static final List<String> SKY_BIOMES = List.of(
        "terralith:skylands_autumn", "terralith:skylands_spring", "terralith:skylands_summer", "terralith:skylands_winter"
    );

    /**
     * Biomes that are fine where the base worldgen puts them, but too distinctive to be spread across
     * other biomes' territory by the arid substitution.
     */
    private static final List<String> NEVER_PAINTED = List.of("terralith:red_oasis", "terralith:desert_oasis");

    /** Frozen oceans read as jarring against an otherwise hot, barren surface. */
    private static final List<String> UNWANTED_ON_SURFACE = List.of(
        "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "terralith:frozen_cliffs"
    );

    private static final class BiomeTagProvider extends TagsProvider<Biome> {
        BiomeTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existingFileHelper) {
            super(output, Registries.BIOME, lookup, BarrenSkies.MOD_ID, existingFileHelper);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            // Optional: these only exist when the mod that supplies them is installed.
            TagAppender<Biome> denied = this.tag(BarrenSkiesTags.DENIED_ON_SURFACE);
            SKY_BIOMES.forEach(id -> denied.addOptional(ResourceLocation.parse(id)));
            UNWANTED_ON_SURFACE.forEach(id -> denied.addOptional(ResourceLocation.parse(id)));

            TagAppender<Biome> neverPainted = this.tag(BarrenSkiesTags.NEVER_PAINTED);
            NEVER_PAINTED.forEach(id -> neverPainted.addOptional(ResourceLocation.parse(id)));

            this.tag(BarrenSkiesTags.ALLOWED_ON_SURFACE);
        }
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
