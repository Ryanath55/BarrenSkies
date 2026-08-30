package com.barrenskies.worldgen;

import com.barrenskies.BarrenSkies;
import com.barrenskies.BarrenSkiesConfig;
import com.barrenskies.worldgen.compat.ModdedBiomes;
import com.google.common.base.Suppliers;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

/**
 * Splits the overworld climate space by height: ocean, arid and cave biomes below the island band,
 * everything else above it.
 */
public class LayeredBiomeSource extends BiomeSource {
    public static final MapCodec<LayeredBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                RegistryOps.retrieveGetter(Registries.BIOME),
                // Read the overworld climate space from the registry rather than vanilla's hardcoded preset.
                // Datapack worldgen mods such as Terralith ship their own copy of this file, so this is what
                // picks their biomes up.
                RegistryOps.retrieveElement(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
            )
            .apply(instance, LayeredBiomeSource::new)
    );

    private final HolderGetter<Biome> biomes;
    private final Holder<MultiNoiseBiomeSourceParameterList> overworldParameters;
    private final Supplier<Layers> layers = Suppliers.memoize(this::buildLayers);

    public LayeredBiomeSource(HolderGetter<Biome> biomes, Holder<MultiNoiseBiomeSourceParameterList> overworldParameters) {
        this.biomes = biomes;
        this.overworldParameters = overworldParameters;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.layers.get().all().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        Layers current = this.layers.get();
        Climate.ParameterList<Holder<Biome>> pool = QuartPos.toBlock(y) >= current.skyBottom() ? current.sky() : current.lower();
        return pool.findValue(sampler.sample(x, y, z));
    }

    /**
     * The biomes available to the sky islands, sorted cold to warm so an island can be picked to suit the
     * local temperature. Anything already used on the barren surface is excluded, along with water
     * biomes, since neither belongs on a floating island.
     */
    public List<Holder<Biome>> skyBiomes() {
        return this.layers.get().skyBiomes();
    }

    @Override
    public void addDebugInfo(List<String> info, BlockPos pos, Climate.Sampler sampler) {
        Layers current = this.layers.get();
        info.add("Barren Skies layer: " + (pos.getY() >= current.skyBottom() ? "sky islands" : "surface / caves"));
    }

    private Layers buildLayers() {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> all =
            new ArrayList<>(this.overworldParameters.value().parameters().values());
        if (BarrenSkiesConfig.INCLUDE_MODDED_BIOMES.get()) {
            all.addAll(ModdedBiomes.collect(this.biomes));
        }

        long oceanContinentalnessMax = Climate.quantizeCoord(BarrenSkiesConfig.OCEAN_CONTINENTALNESS_MAX.get().floatValue());
        long aridTemperatureMin = Climate.quantizeCoord(BarrenSkiesConfig.ARID_TEMPERATURE_MIN.get().floatValue());
        boolean aridRequiresNoRain = BarrenSkiesConfig.ARID_REQUIRES_NO_RAIN.get();

        // Everything at a positive depth is underground: the cave biomes proper, plus the copy vanilla makes
        // of each surface biome at depth 1.0 so that deep stone keeps a sensible biome. Both pass through
        // untouched, which is what keeps layer one vanilla.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> caves = new ArrayList<>();
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> surface = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : all) {
            (entry.getFirst().depth().min() > 0L ? caves : surface).add(entry);
        }

        // The substitutes we draw from: vanilla's own hot, rainless land biomes (desert and the badlands family).
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> aridPalette =
            aridPalette(surface, oceanContinentalnessMax, aridTemperatureMin, aridRequiresNoRain);

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> lower = new ArrayList<>(caves);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> sky = new ArrayList<>(surface);
        int substituted = 0;

        if (aridPalette.isEmpty()) {
            // Nothing to swap in, so leave the surface vanilla rather than shipping a broken climate space.
            BarrenSkies.LOG.warn("No arid biomes matched the current config; leaving the barren surface as vanilla.");
            lower.addAll(surface);
        } else {
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : surface) {
                // Every climate point keeps its slot. Only the biome attached to it changes, so the terrain
                // shape the noise router builds always agrees with the biome the debug screen reports.
                if (keepOnSurface(entry, oceanContinentalnessMax, aridTemperatureMin, aridRequiresNoRain)) {
                    lower.add(entry);
                } else {
                    lower.add(Pair.of(entry.getFirst(), nearestAridBiome(entry.getFirst(), aridPalette)));
                    substituted++;
                }
            }
        }

        BarrenSkies.LOG.info(
            "Barren Skies surface: {} of {} climate points rewritten to arid, palette of {}. Resulting biomes: {}",
            substituted,
            surface.size(),
            aridPalette.size(),
            lower.stream()
                .skip(caves.size())
                .map(entry -> entry.getSecond().unwrapKey().map(key -> key.location().toString()).orElse("?"))
                .distinct()
                .sorted()
                .toList()
        );

        Set<Holder<Biome>> everything = new LinkedHashSet<>();
        lower.forEach(entry -> everything.add(entry.getSecond()));
        sky.forEach(entry -> everything.add(entry.getSecond()));

        // Whatever ended up on the barren surface has no business also being on a floating island, and
        // neither do oceans or beaches, which need a shoreline to make sense.
        Set<Holder<Biome>> barren = new LinkedHashSet<>();
        lower.stream().skip(caves.size()).forEach(entry -> barren.add(entry.getSecond()));
        java.util.function.Predicate<Holder<Biome>> suitsSky =
            biome -> !barren.contains(biome)
                && !isWater(biome)
                && !biome.is(BiomeTags.IS_BEACH)
                // Rivers need a valley to run along and a sea to reach. As an island they are just a
                // misnamed patch of ground.
                && !biome.is(BiomeTags.IS_RIVER)
                && !biome.is(BarrenSkiesTags.DENIED_IN_SKY);

        // The island pool is remapped the same way the surface is, rather than filtered. Dropping entries
        // would leave holes for the nearest surviving entry to fill, which is how deserts and oceans kept
        // turning up on islands.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> skyPalette = sky.stream().filter(entry -> suitsSky.test(entry.getSecond())).toList();
        if (!skyPalette.isEmpty()) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> remapped = new ArrayList<>(sky.size());
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : sky) {
                remapped.add(
                    suitsSky.test(entry.getSecond()) ? entry : Pair.of(entry.getFirst(), nearestBiome(entry.getFirst(), skyPalette))
                );
            }
            sky = remapped;
        }

        List<Holder<Biome>> skyBiomes = sky.stream()
            .map(Pair::getSecond)
            .distinct()
            .sorted(java.util.Comparator.comparing(biome -> biome.value().getBaseTemperature()))
            .toList();
        BarrenSkies.LOG.info(
            "Barren Skies sky islands: {} biomes, coldest {}, warmest {}.",
            skyBiomes.size(),
            skyBiomes.isEmpty() ? "none" : skyBiomes.getFirst().unwrapKey().map(key -> key.location().toString()).orElse("?"),
            skyBiomes.isEmpty() ? "none" : skyBiomes.getLast().unwrapKey().map(key -> key.location().toString()).orElse("?")
        );

        BarrenSkies.LOG.info("Barren Skies biome pools: {} entries below the island band, {} above.", lower.size(), sky.size());
        return new Layers(
            new Climate.ParameterList<>(List.copyOf(lower)),
            new Climate.ParameterList<>(List.copyOf(sky)),
            Set.copyOf(everything),
            skyBiomes,
            BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - com.barrenskies.worldgen.sky.SkyIslandDensity.layerReach()
        );
    }

    private static boolean keepOnSurface(
        Pair<Climate.ParameterPoint, Holder<Biome>> entry, long oceanContinentalnessMax, long aridTemperatureMin, boolean aridRequiresNoRain
    ) {
        Holder<Biome> biome = entry.getSecond();
        if (biome.is(BarrenSkiesTags.DENIED_ON_SURFACE)) {
            return false;
        }
        if (biome.is(BarrenSkiesTags.ALLOWED_ON_SURFACE)) {
            return true;
        }
        // Ocean generation is left exactly as the base worldgen made it.
        return isWater(biome) || isOceanic(entry.getFirst(), oceanContinentalnessMax) || isArid(entry, aridTemperatureMin, aridRequiresNoRain);
    }

    /**
     * Water biomes are identified by tag rather than by climate. The continentalness where terrain drops
     * below sea level moves when another mod supplies the density functions, but the tags stay accurate.
     */
    private static boolean isWater(Holder<Biome> biome) {
        // Oceans only. Rivers and beaches follow the land they cut through, so a frozen river or snowy
        // beach surviving in the middle of a desert reads as a bug rather than as variety.
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
    }

    /**
     * Builds the pool of biomes the barren surface is painted from. If the configured cutoff excludes
     * everything, this falls back to the hottest land biomes that do exist rather than leaving the
     * surface vanilla, so a badly chosen number degrades the result instead of disabling the mod.
     */
    private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> aridPalette(
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> surface, long oceanContinentalnessMax, long aridTemperatureMin, boolean aridRequiresNoRain
    ) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> land = surface.stream()
            // A denied biome must not come back as a substitute either, or it reappears in someone else's slot.
            .filter(entry -> !entry.getSecond().is(BarrenSkiesTags.DENIED_ON_SURFACE))
            .filter(entry -> !entry.getSecond().is(BarrenSkiesTags.NEVER_PAINTED))
            .filter(entry -> !isWater(entry.getSecond()))
            .filter(entry -> !isOceanic(entry.getFirst(), oceanContinentalnessMax))
            .filter(entry -> !aridRequiresNoRain || !entry.getSecond().value().hasPrecipitation())
            .toList();

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> matched = land.stream()
            .filter(entry -> midpoint(entry.getFirst().temperature()) >= aridTemperatureMin)
            .toList();
        if (!matched.isEmpty()) {
            return matched;
        }

        long hottest = land.stream().mapToLong(entry -> midpoint(entry.getFirst().temperature())).max().orElse(Long.MIN_VALUE);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> fallback = land.stream()
            .filter(entry -> midpoint(entry.getFirst().temperature()) == hottest)
            .toList();
        BarrenSkies.LOG.warn(
            "aridTemperatureMin={} is hotter than any available biome; falling back to the hottest band at {}.",
            Climate.unquantizeCoord(aridTemperatureMin),
            Climate.unquantizeCoord(hottest)
        );
        return fallback;
    }

    /**
     * Climate-based ocean detection, used only for biomes the water tags do not cover. Testing the whole
     * range rather than its middle catches far too much: mods routinely give a land biome a continentalness
     * range that reaches down into ocean values, and treating those as ocean leaves the surface vanilla.
     */
    private static boolean isOceanic(Climate.ParameterPoint point, long oceanContinentalnessMax) {
        return midpoint(point.continentalness()) <= oceanContinentalnessMax;
    }

    private static boolean isArid(Pair<Climate.ParameterPoint, Holder<Biome>> entry, long aridTemperatureMin, boolean aridRequiresNoRain) {
        return midpoint(entry.getFirst().temperature()) >= aridTemperatureMin
            && (!aridRequiresNoRain || !entry.getSecond().value().hasPrecipitation());
    }

    /**
     * Vanilla splits climate into a handful of wide bands, so comparing against a band edge makes these
     * cutoffs behave as step functions. Comparing against the middle of the band keeps a value chosen
     * between two edges doing something sensible.
     */
    private static long midpoint(Climate.Parameter parameter) {
        return (parameter.min() + parameter.max()) / 2L;
    }

    /** Nearest entry across the whole climate space, used when swapping one biome pool for another. */
    private static Holder<Biome> nearestBiome(Climate.ParameterPoint point, List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette) {
        Holder<Biome> best = palette.getFirst().getSecond();
        long bestDistance = Long.MAX_VALUE;
        for (Pair<Climate.ParameterPoint, Holder<Biome>> candidate : palette) {
            Climate.ParameterPoint other = candidate.getFirst();
            long distance = square(gap(point.temperature(), other.temperature()))
                + square(gap(point.humidity(), other.humidity()))
                + square(gap(point.continentalness(), other.continentalness()))
                + square(gap(point.erosion(), other.erosion()))
                + square(gap(point.weirdness(), other.weirdness()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getSecond();
            }
        }
        return best;
    }

    /**
     * Picks the arid biome vanilla would put on this shape of ground, so plateaus stay badlands, eroded
     * ground stays eroded, and so on.
     */
    private static Holder<Biome> nearestAridBiome(Climate.ParameterPoint point, List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette) {
        Holder<Biome> best = palette.getFirst().getSecond();
        long bestDistance = Long.MAX_VALUE;
        for (Pair<Climate.ParameterPoint, Holder<Biome>> candidate : palette) {
            long distance = shapeDistance(point, candidate.getFirst());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.getSecond();
            }
        }
        return best;
    }

    /** Distance across the terrain-shape axes only. Temperature and humidity are what we are deliberately overriding. */
    private static long shapeDistance(Climate.ParameterPoint a, Climate.ParameterPoint b) {
        return square(gap(a.continentalness(), b.continentalness()))
            + square(gap(a.erosion(), b.erosion()))
            + square(gap(a.weirdness(), b.weirdness()))
            + square(gap(a.depth(), b.depth()));
    }

    private static long gap(Climate.Parameter from, Climate.Parameter to) {
        long midpoint = (from.min() + from.max()) / 2L;
        return Math.max(0L, Math.max(to.min() - midpoint, midpoint - to.max()));
    }

    private static long square(long value) {
        return value * value;
    }

    private record Layers(
        Climate.ParameterList<Holder<Biome>> lower,
        Climate.ParameterList<Holder<Biome>> sky,
        Set<Holder<Biome>> all,
        List<Holder<Biome>> skyBiomes,
        int skyBottom
    ) {
    }
}
