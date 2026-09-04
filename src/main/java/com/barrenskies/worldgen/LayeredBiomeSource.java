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

    /**
     * Where the ground stops being the surface.
     *
     * <p>Vanilla puts its surface biomes at a depth of exactly zero and starts its underground bands at
     * 0.2, so that is the line. Measured rather than assumed: sampled at the actual height of the ground,
     * a thousand columns came back between -0.8 and +0.3, with a quarter of them a little above zero.
     * Testing for depth above zero therefore called a quarter of the open surface underground and left
     * every modded biome standing on it, which is the whole bug this is here to fix.
     */
    private static final long UNDERGROUND_DEPTH = Climate.quantizeCoord(0.2F);

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

    /**
     * Applies the layer rules to a biome some other mod chose for this position.
     *
     * <p>Blueprint's modded biome slices, and anything else that wraps a dimension's biome source, answer
     * the lookup before this class ever sees it, so the climate space built above is simply skipped for
     * every point they claim. That is how a rainforest ends up on the barren surface. Reconciling here
     * puts the same decisions back on top of whatever came out: the surface pass asks whether the biome
     * belongs on arid ground and substitutes from the same palette if it does not, and the sky pass lets
     * dry land through and remaps everything else. Their biomes, our layers, which is the compat model
     * the rest of the mod already follows.
     *
     * <p>The climate is sampled only when a decision actually needs it. Most lookups come back with a
     * biome this class chose itself -- a slice hands the position straight back whenever its own table
     * says the original source owns it -- and those are recognised from the pool alone.
     */
    public Holder<Biome> reconcile(Holder<Biome> biome, int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        Layers current = this.layers.get();
        return QuartPos.toBlock(quartY) >= current.skyBottom()
            ? reconcileSky(current.rules(), biome, quartX, quartY, quartZ, sampler)
            : reconcileSurface(current.rules(), biome, quartX, quartY, quartZ, sampler);
    }

    private static Holder<Biome> reconcileSurface(
        Rules rules, Holder<Biome> biome, int quartX, int quartY, int quartZ, Climate.Sampler sampler
    ) {
        // Whatever the climate pass already decided belongs down here, including the cave biomes it left
        // alone, needs no second look. One lookup, and it is what the great majority of positions take.
        if (rules.lowerBiomes().contains(biome)) {
            return biome;
        }
        boolean denied = biome.is(BarrenSkiesTags.DENIED_ON_SURFACE);
        Kind kind = Kind.of(biome);
        if (!denied && (biome.is(BarrenSkiesTags.ALLOWED_ON_SURFACE) || kind != Kind.LAND)) {
            return biome;
        }

        Climate.TargetPoint target = sampler.sample(quartX, quartY, quartZ);
        // Underground, where the cave biomes and the deep copies of the surface ones live. Layer one is
        // vanilla by design, so nothing down here is ours to swap.
        if (target.depth() >= UNDERGROUND_DEPTH) {
            return biome;
        }
        boolean oceanic = target.continentalness() <= rules.oceanContinentalnessMax();
        if (!denied
            && (oceanic
                || (target.temperature() >= rules.aridTemperatureMin()
                    && (!rules.aridRequiresNoRain() || !biome.value().hasPrecipitation())))) {
            return biome;
        }

        // As in the climate pass: the replacement follows the terrain rather than the biome that was
        // here, so a point at ocean continentalness is refilled with an ocean whatever was painted on it.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette = rules.palettes().get(oceanic ? Kind.OCEAN : kind);
        if (palette == null || palette.isEmpty()) {
            return biome;
        }
        return nearest(target, palette, LayeredBiomeSource::shapeDistanceFrom);
    }

    private static Holder<Biome> reconcileSky(
        Rules rules, Holder<Biome> biome, int quartX, int quartY, int quartZ, Climate.Sampler sampler
    ) {
        // A modded biome that is dry land and not barren-surface material is exactly what an island wants,
        // so it passes straight through. This is the one place the slices are welcome.
        if (suitsSky(rules.barren(), biome) || rules.skyPalette().isEmpty()) {
            return biome;
        }
        return nearest(sampler.sample(quartX, quartY, quartZ), rules.skyPalette(), LayeredBiomeSource::climateDistanceFrom);
    }

    private static boolean suitsSky(Set<Holder<Biome>> barren, Holder<Biome> biome) {
        return !barren.contains(biome) && Kind.of(biome) == Kind.LAND && !biome.is(BarrenSkiesTags.DENIED_IN_SKY);
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

        java.util.Map<String, int[]> swaps = new java.util.TreeMap<>();
        Set<String> misplaced = new java.util.TreeSet<>();
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
                // Which pool the replacement comes from follows the terrain, not the biome that used to
                // be here. A climate point at ocean continentalness is under water whatever was attached
                // to it, so replacing it with an inland desert puts sand and camels on the sea floor.
                // Terralith's skylands are exactly that case: they sit at deep ocean continentalness
                // because they are islands floating above open sea, and denying them from the surface
                // turned every one of their points into desert.
                Kind target = isOceanic(entry.getFirst(), oceanContinentalnessMax) ? Kind.OCEAN : kind;
                List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette = palettes.get(target);
                if (palette.isEmpty()) {
                    // Nothing of the right kind to swap in. Keeping a biome we would rather not have beats
                    // putting land in the water, which is the failure that actually breaks structures.
                    lower.add(entry);
                    continue;
                }
                Holder<Biome> replacement = nearest(entry.getFirst(), palette, LayeredBiomeSource::shapeDistance);
                if (target == Kind.OCEAN && Kind.of(replacement) == Kind.LAND) {
                    misplaced.add(name(entry.getSecond()) + " -> " + name(replacement));
                }
                lower.add(Pair.of(entry.getFirst(), replacement));
                substituted++;
                swaps.computeIfAbsent(
                    name(entry.getSecond()) + " [c " + Climate.unquantizeCoord(entry.getFirst().continentalness().min())
                        + ".." + Climate.unquantizeCoord(entry.getFirst().continentalness().max()) + "] -> " + name(replacement),
                    key -> new int[1]
                )[0]++;
            }
        }

        // Kept at debug: this is the log that found desert being painted over deep ocean, and the next
        // substitution bug will show up the same way.
        // Kept at debug: this is the log that caught desert being painted over deep ocean, and the next
        // substitution bug will show up the same way.
        swaps.forEach((label, count) -> BarrenSkies.LOG.debug("  swap x{}: {}", count[0], label));

        // Self check, and it only ever fires on our own mistakes. Land that the base worldgen itself puts
        // at ocean continentalness -- mushroom fields, Terralith's island biomes -- is its business and
        // generates as islands. Land that *we* substituted in there is the failure that put camels on the
        // sea floor, because the terrain is water whatever the biome says.
        if (!misplaced.isEmpty()) {
            BarrenSkies.LOG.error(
                "Substituted dry land at ocean continentalness: {}. This puts land biomes under water and "
                    + "will misplace structures and mob spawns. Please report it.",
                misplaced
            );
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
        java.util.function.Predicate<Holder<Biome>> suitsSky = biome -> suitsSky(barren, biome);

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

        Set<Holder<Biome>> lowerBiomes = new LinkedHashSet<>();
        lower.forEach(entry -> lowerBiomes.add(entry.getSecond()));

        return new Layers(
            new Climate.ParameterList<>(List.copyOf(lower)),
            new Climate.ParameterList<>(List.copyOf(sky)),
            Set.copyOf(everything),
            skyBiomes,
            com.barrenskies.worldgen.sky.SkyIslandDensity.islandFloor(),
            new Rules(
                java.util.Map.copyOf(palettes),
                skyPalette,
                Set.copyOf(lowerBiomes),
                Set.copyOf(barren),
                oceanContinentalnessMax,
                aridTemperatureMin,
                aridRequiresNoRain
            )
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
    private static <P> Holder<Biome> nearest(
        P point,
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> palette,
        java.util.function.ToLongBiFunction<P, Climate.ParameterPoint> distance
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
        return gap((from.min() + from.max()) / 2L, to);
    }

    /** The same measure taken from one sampled value rather than from the middle of a range. */
    private static long gap(long from, Climate.Parameter to) {
        return Math.max(0L, Math.max(to.min() - from, from - to.max()));
    }

    /** {@link #shapeDistance} measured from a point the climate was actually sampled at. */
    private static long shapeDistanceFrom(Climate.TargetPoint from, Climate.ParameterPoint to) {
        return square(gap(from.continentalness(), to.continentalness()))
            + square(gap(from.erosion(), to.erosion()))
            + square(gap(from.weirdness(), to.weirdness()))
            + square(gap(from.depth(), to.depth()));
    }

    /** {@link #climateDistance} measured from a point the climate was actually sampled at. */
    private static long climateDistanceFrom(Climate.TargetPoint from, Climate.ParameterPoint to) {
        return square(gap(from.temperature(), to.temperature()))
            + square(gap(from.humidity(), to.humidity()))
            + square(gap(from.continentalness(), to.continentalness()))
            + square(gap(from.erosion(), to.erosion()))
            + square(gap(from.weirdness(), to.weirdness()));
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("?");
    }

    private static long square(long value) {
        return value * value;
    }

    private record Layers(
        Climate.ParameterList<Holder<Biome>> lower,
        Climate.ParameterList<Holder<Biome>> sky,
        Set<Holder<Biome>> all,
        List<Holder<Biome>> skyBiomes,
        int skyBottom,
        Rules rules
    ) {
    }

    /**
     * What the two passes above decided, kept in a form that can decide the same thing again for a single
     * sampled point. Only {@link #reconcile} uses it, and only when another mod has taken the lookup over.
     */
    private record Rules(
        java.util.Map<Kind, List<Pair<Climate.ParameterPoint, Holder<Biome>>>> palettes,
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> skyPalette,
        Set<Holder<Biome>> lowerBiomes,
        Set<Holder<Biome>> barren,
        long oceanContinentalnessMax,
        long aridTemperatureMin,
        boolean aridRequiresNoRain
    ) {
    }
}
