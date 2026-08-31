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

        // One palette per kind. A denied ocean is replaced by an ocean, a denied river by a river, and only
        // ordinary land is replaced by the hot, rainless biomes the barren surface is made of.
        java.util.EnumMap<Kind, List<Pair<Climate.ParameterPoint, Holder<Biome>>>> palettes =
            new java.util.EnumMap<>(Kind.class);
        palettes.put(Kind.LAND, aridPalette(surface, oceanContinentalnessMax, aridTemperatureMin, aridRequiresNoRain));
        for (Kind kind : new Kind[] {Kind.OCEAN, Kind.RIVER, Kind.BEACH}) {
            palettes.put(kind, waterPalette(surface, kind));
        }

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> lower = new ArrayList<>(caves);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> sky = new ArrayList<>(surface);
        int substituted = 0;

        if (palettes.get(Kind.LAND).isEmpty()) {
            // Nothing to swap in, so leave the surface vanilla rather than shipping a broken climate space.
            BarrenSkies.LOG.warn("No arid biomes matched the current config; leaving the barren surface as vanilla.");
            lower.addAll(surface);
        } else {
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : surface) {
                // Every climate point keeps its slot. Only the biome attached to it changes, so the terrain
                // shape the noise router builds always agrees with the biome the debug screen reports.
                Kind kind = Kind.of(entry.getSecond());
                if (keepOnSurface(entry, kind, oceanContinentalnessMax, aridTemperatureMin, aridRequiresNoRain)) {
                    lower.add(entry);
                    continue;
                }
                List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette = palettes.get(kind);
                if (palette.isEmpty()) {
                    // Nothing of the right kind to swap in. Keeping a biome we would rather not have beats
                    // putting land in the water, which is the failure that actually breaks structures.
                    lower.add(entry);
                    continue;
                }
                lower.add(Pair.of(entry.getFirst(), nearest(entry.getFirst(), palette, LayeredBiomeSource::shapeDistance)));
                substituted++;
            }
        }

        BarrenSkies.LOG.info(
            "Barren Skies surface: {} of {} climate points substituted, arid palette of {}. Resulting biomes: {}",
            substituted,
            surface.size(),
            palettes.get(Kind.LAND).size(),
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
        // Oceans, rivers and beaches all need a shoreline or a valley to make sense of them; on an island
        // they are just misnamed ground. Dry land only up there.
        java.util.function.Predicate<Holder<Biome>> suitsSky =
            biome -> !barren.contains(biome)
                && Kind.of(biome) == Kind.LAND
                && !biome.is(BarrenSkiesTags.DENIED_IN_SKY);

        // The island pool is remapped the same way the surface is, rather than filtered. Dropping entries
        // would leave holes for the nearest surviving entry to fill, which is how deserts and oceans kept
        // turning up on islands.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> skyPalette = sky.stream().filter(entry -> suitsSky.test(entry.getSecond())).toList();
        if (!skyPalette.isEmpty()) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> remapped = new ArrayList<>(sky.size());
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : sky) {
                remapped.add(
                    suitsSky.test(entry.getSecond()) ? entry : Pair.of(entry.getFirst(), nearest(entry.getFirst(), skyPalette, LayeredBiomeSource::climateDistance))
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

    /**
     * How a biome relates to water.
     *
     * <p>This is the distinction that decides what a biome may be replaced by, and getting it wrong is
     * what put villages in rivers. Terrain shape comes from the density functions and knows nothing about
     * this substitution, so a river is a water-filled channel whether or not we call it a desert. Swap the
     * biome for a land one and every structure that trusts the biome — which is all of them — places on
     * water. A biome tied to water therefore only ever gets swapped for another biome of the same kind.
     */
    private enum Kind {
        OCEAN, RIVER, BEACH, LAND;

        static Kind of(Holder<Biome> biome) {
            if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) {
                return OCEAN;
            }
            if (biome.is(BiomeTags.IS_RIVER)) {
                return RIVER;
            }
            if (biome.is(BiomeTags.IS_BEACH)) {
                return BEACH;
            }
            return LAND;
        }
    }

    private static boolean keepOnSurface(
        Pair<Climate.ParameterPoint, Holder<Biome>> entry, Kind kind,
        long oceanContinentalnessMax, long aridTemperatureMin, boolean aridRequiresNoRain
    ) {
        Holder<Biome> biome = entry.getSecond();
        if (biome.is(BarrenSkiesTags.DENIED_ON_SURFACE)) {
            return false;
        }
        if (biome.is(BarrenSkiesTags.ALLOWED_ON_SURFACE)) {
            return true;
        }
        // Anything tied to water keeps its place. Only a denied one is swapped, and only for its own kind.
        if (kind != Kind.LAND) {
            return true;
        }
        return isOceanic(entry.getFirst(), oceanContinentalnessMax) || isArid(entry, aridTemperatureMin, aridRequiresNoRain);
    }

    /**
     * The acceptable biomes of one water-tied kind, used to replace a denied ocean, river or beach with
     * one we do want rather than with dry land.
     */
    private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> waterPalette(
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> surface, Kind kind
    ) {
        return surface.stream()
            .filter(entry -> Kind.of(entry.getSecond()) == kind)
            .filter(entry -> !entry.getSecond().is(BarrenSkiesTags.DENIED_ON_SURFACE))
            .filter(entry -> !entry.getSecond().is(BarrenSkiesTags.NEVER_PAINTED))
            .toList();
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
            // Dry land only. A beach or river used as an arid substitute would be painted across ground
            // that has no shoreline anywhere near it.
            .filter(entry -> Kind.of(entry.getSecond()) == Kind.LAND)
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

    /** The closest biome in a palette under the given idea of closeness. */
    private static Holder<Biome> nearest(
        Climate.ParameterPoint point,
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette,
        java.util.function.ToLongBiFunction<Climate.ParameterPoint, Climate.ParameterPoint> distance
    ) {
        Holder<Biome> best = palette.getFirst().getSecond();
        long bestDistance = Long.MAX_VALUE;
        for (Pair<Climate.ParameterPoint, Holder<Biome>> candidate : palette) {
            long measured = distance.applyAsLong(point, candidate.getFirst());
            if (measured < bestDistance) {
                bestDistance = measured;
                best = candidate.getSecond();
            }
        }
        return best;
    }

    /**
     * Distance across the terrain-shape axes only, for picking a replacement on the barren surface. It
     * ignores temperature and humidity because those are exactly what the substitution is overriding, so
     * a plateau stays badlands and eroded ground stays eroded.
     */
    private static long shapeDistance(Climate.ParameterPoint a, Climate.ParameterPoint b) {
        return square(gap(a.continentalness(), b.continentalness()))
            + square(gap(a.erosion(), b.erosion()))
            + square(gap(a.weirdness(), b.weirdness()))
            + square(gap(a.depth(), b.depth()));
    }

    /**
     * Distance across the whole climate space, for picking a replacement in the sky. Up there temperature
     * and humidity are worth keeping: a jungle slot should become another warm, wet biome.
     */
    private static long climateDistance(Climate.ParameterPoint a, Climate.ParameterPoint b) {
        return square(gap(a.temperature(), b.temperature()))
            + square(gap(a.humidity(), b.humidity()))
            + square(gap(a.continentalness(), b.continentalness()))
            + square(gap(a.erosion(), b.erosion()))
            + square(gap(a.weirdness(), b.weirdness()));
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
