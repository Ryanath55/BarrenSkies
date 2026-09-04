package com.barrenskies;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Generates a block of chunks on startup and reports what it cost. Off unless asked for.
 *
 * <p>Run with -Dbarrenskies.bench=N, or gradlew runServer -Pbench=N, to generate an N by N block of chunks
 * well away from spawn, so the spawn area.s own generation is not measured a second time. Reports wall
 * time and time a chunk.
 *
 * <p>It does the same over three regions far apart, and reports what each leaves behind once collected.
 * Generated chunks stay loaded, so some retention is expected and correct; what matters is whether the
 * three numbers are level or climbing. Climbing means something is kept per world without bound.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = BarrenSkies.MOD_ID)
public final class Bench {
    private Bench() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        int side = Integer.getInteger("barrenskies.bench", 0);
        if (side <= 0) {
            return;
        }
        ServerLevel level = event.getServer().overworld();

        // Three separate regions, far apart and far from spawn. The timings come from the first; the other
        // two are there for the heap. Anything that keeps per-world state without bound grows with each
        // one, and generated chunks stay loaded either way, so what matters is whether the growth is the
        // chunks or something else on top of them.
        long[] retained = new long[3];
        // Pass 0 is not a measurement of this mod, it is a measurement of the JIT: 31.7 seconds against
        // 19.4 for the identical work one pass later. Everything reported below comes from a warm pass,
        // and the three raw totals are printed so the spread between regions stays visible.
        final int WARM = 1;
        long[] passMillis = new long[3];
        List<Long> perChunk = new ArrayList<>(side * side);
        long took = 0L;
        long heapBefore = 0L;
        long heapAfter = 0L;
        long heapCollected = 0L;

        for (int pass = 0; pass < 3; pass++) {
            int originX = 2000 + pass * 4000;
            int originZ = 2000 + pass * 4000;
            System.gc();
            sleep();
            long before = used();
            Sampler sampler = null;
            // Off unless asked for: sampling stops the world to read every stack, and at a millisecond
            // apart that is worth 12 percent of the pass it runs in -- which is larger than most of what
            // it would be used to measure. -Dbarrenskies.profile=1 turns it on, and then the timings from
            // that run are the profiler's and not the mod's.
            if (pass == WARM && Integer.getInteger("barrenskies.profile", 0) > 0) {
                sampler = new Sampler();
                sampler.start(1000L);
            }
            long began = System.nanoTime();
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    long one = System.nanoTime();
                    level.getChunk(originX + x, originZ + z, ChunkStatus.FULL, true);
                    if (pass == WARM) {
                        perChunk.add(System.nanoTime() - one);
                    }
                }
            }
            long elapsed = System.nanoTime() - began;
            if (sampler != null) {
                sampler.stop();
                sampler.report("where a chunk goes", 22);
            }
            long after = used();
            System.gc();
            sleep();
            long collected = used();
            retained[pass] = (collected - before) >> 20;
            passMillis[pass] = elapsed / 1_000_000L;
            if (pass == WARM) {
                took = elapsed;
                heapBefore = before;
                heapAfter = after;
                heapCollected = collected;
            }
        }

        BarrenSkies.LOG.info(
            "[bench] all three passes, {} chunks each: {} ms, {} ms, {} ms. Same regions and the same seed "
                + "every run, so a change worth keeping moves all three the same way -- and anything smaller "
                + "than the spread between them is not a measurement.",
            side * side, passMillis[0], passMillis[1], passMillis[2]);

        perChunk.sort(null);
        BarrenSkies.LOG.info(
            "[bench] {} chunks ({}x{}) in {} ms. Per chunk: mean {}, median {}, p90 {}, worst {} ms. "
                + "Heap {} MB before, {} MB after, {} MB once collected.",
            side * side, side, side, took / 1_000_000L,
            String.format("%.1f", took / 1_000_000.0 / (side * side)),
            String.format("%.1f", perChunk.get(perChunk.size() / 2) / 1_000_000.0),
            String.format("%.1f", perChunk.get(perChunk.size() * 9 / 10) / 1_000_000.0),
            String.format("%.1f", perChunk.getLast() / 1_000_000.0),
            heapBefore >> 20, heapAfter >> 20, heapCollected >> 20
        );
        BarrenSkies.LOG.info(
            "[bench] retained after a collection, three separate regions in turn: {} MB, {} MB, {} MB. "
                + "Level growth here is the loaded chunks; growth beyond that is not.",
            retained[0], retained[1], retained[2]
        );

        // Section count is the other half of the story: a taller world writes more of them per chunk,
        // which is what a save costs on disk and what a load costs coming back.
        // How high the ground actually gets, which is what decides how far the island band could come
        // down. Asked of the ground itself and not of the heightmap, since that reports the island above
        // a column rather than the peak below it. Every fourth column of every chunk generated.
        int floor = com.barrenskies.worldgen.sky.SkyIslandDensity.islandFloor();
        int highest = level.getMinBuildHeight();
        java.util.TreeMap<Integer, Integer> byBand = new java.util.TreeMap<>();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int cx = 0; cx < side; cx++) {
            for (int cz = 0; cz < side; cz++) {
                var c = level.getChunk(2000 + cx, 2000 + cz, ChunkStatus.FULL, false);
                if (c == null) {
                    continue;
                }
                int baseX = (2000 + cx) << 4;
                int baseZ = (2000 + cz) << 4;
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        for (int y = floor - 1; y > 0; y--) {
                            pos.set(baseX + x, y, baseZ + z);
                            if (!c.getBlockState(pos).isAir()) {
                                highest = Math.max(highest, y);
                                byBand.merge(y / 32 * 32, 1, Integer::sum);
                                break;
                            }
                        }
                    }
                }
            }
        }
        BarrenSkies.LOG.info("[bench] highest ground below the island floor: Y {}. Islands begin at Y {}, "
            + "so the clearance is {} blocks. Column tops by 32 block band: {}",
            highest, floor, floor - highest, byBand.descendingMap());

        // The other height a structure is given, and the one that kept shipwrecks flying after the
        // generator was told to answer with the ground. A piece works its own height out while it is being
        // built, from the finished world rather than from the generator. Measured here against the ground
        // reading that now replaces it: in a column with no island above it the two must agree exactly, or
        // the replacement is wrong about something other than islands.
        var oceanFloor = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG;
        int groundColumns = 0;
        int islandColumns = 0;
        int disagreed = 0;
        int stillAbove = 0;
        int highestGroundAnswer = level.getMinBuildHeight();
        for (int cx = 0; cx < side; cx++) {
            for (int cz = 0; cz < side; cz++) {
                var c = level.getChunk(2000 + cx, 2000 + cz, ChunkStatus.FULL, false);
                if (c == null) {
                    continue;
                }
                int baseX = (2000 + cx) << 4;
                int baseZ = (2000 + cz) << 4;
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        int world = c.getHeight(oceanFloor, x, z) + 1;
                        int ground = com.barrenskies.worldgen.GroundHeight.of(c, oceanFloor, baseX + x, baseZ + z);
                        if (world > floor) {
                            islandColumns++;
                            stillAbove += ground > floor ? 1 : 0;
                            highestGroundAnswer = Math.max(highestGroundAnswer, ground);
                        } else {
                            groundColumns++;
                            disagreed += ground == world ? 0 : 1;
                        }
                    }
                }
            }
        }
        BarrenSkies.LOG.info("[bench] the height a piece reads while it is being built: {} columns answered "
            + "ground and {} answered island. Of the island ones, {} still came back above the floor once "
            + "the ground reading replaced it, the highest at Y {}. Of the ground ones, {} disagreed with "
            + "the world's own heightmap.",
            groundColumns, islandColumns, stillAbove, highestGroundAnswer, disagreed);

        // What the generator tells a structure the surface is, against where the ground actually stops.
        // A structure that wants the sea floor asks this, and if it comes back with an island the
        // structure is built on the island -- or, once the island is not solid all the way down, in the
        // air beside it. That is the shape of the flying shipwrecks.
        var generator = event.getServer().overworld().getChunkSource().getGenerator();
        var randomState = event.getServer().overworld().getChunkSource().randomState();
        int agreed = 0;
        int aboveGround = 0;
        int worst = 0;
        for (int i = 0; i < 400; i++) {
            int x = 32000 + (i % 20) * 37;
            int z = 32000 + (i / 20) * 41;
            int asked = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                level, randomState);
            if (asked <= floor) {
                agreed++;
            } else {
                aboveGround++;
                worst = Math.max(worst, asked);
            }
        }
        BarrenSkies.LOG.info("[bench] the overworld generator is {}", generator.getClass().getName());

        // The same question asked on behalf of different structures. A shipwreck must never be offered an
        // island; a village may have one. Nothing asking at all is a world spawn and wants the ground.
        var structures = event.getServer().registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        for (String name : new String[] { "minecraft:shipwreck", "minecraft:ocean_ruin_warm",
            "minecraft:mineshaft", "minecraft:village_plains", "minecraft:pillager_outpost" }) {
            var structure = structures.get(net.minecraft.resources.ResourceLocation.parse(name));
            if (structure == null) {
                continue;
            }
            com.barrenskies.worldgen.StructureIntent.begin(structure, event.getServer().registryAccess());
            int offered = 0;
            for (int i = 0; i < 400; i++) {
                int x = 32000 + (i % 20) * 37;
                int z = 32000 + (i / 20) * 41;
                if (generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                    level, randomState) > floor) {
                    offered++;
                }
            }
            BarrenSkies.LOG.info("[bench] {}: wants ground {}, offered an island in {} of 400 columns",
                name, com.barrenskies.worldgen.StructureIntent.wantsGround(), offered);
            com.barrenskies.worldgen.StructureIntent.end();
        }
        BarrenSkies.LOG.info("[bench] of 400 columns asked for a surface height, {} answered below the island "
            + "floor and {} answered above it, the highest at Y {}. Anything above the floor is an island "
            + "being offered to a structure that wanted the ground.", agreed, aboveGround, worst);

        var densityFunctions = event.getServer().registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.DENSITY_FUNCTION);
        BarrenSkies.LOG.info("[bench] density functions containing cave, noodle, pillar or cheese: {}",
            densityFunctions.keySet().stream()
                .map(net.minecraft.resources.ResourceLocation::toString)
                .filter(id -> id.contains("cave") || id.contains("noodle") || id.contains("pillar")
                    || id.contains("cheese") || id.contains("spaghetti"))
                .sorted().toList());

        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
            event.getServer().registryAccess());
        for (String id : new String[] { "minecraft:overworld/caves/noodle", "minecraft:overworld/caves/pillars",
            "minecraft:overworld/caves/spaghetti_2d", "minecraft:overworld/caves/entrances" }) {
            var fn = densityFunctions.get(net.minecraft.resources.ResourceLocation.parse(id));
            if (fn == null) { continue; }
            String json = net.minecraft.world.level.levelgen.DensityFunction.DIRECT_CODEC
                .encodeStart(ops, fn).result().map(Object::toString).orElse("(could not encode)");
            BarrenSkies.LOG.info("[bench] {} = {}", id, json.length() > 1400 ? json.substring(0, 1400) + " ..." : json);
        }


        // Each piece of the cave mask, sampled where the islands ought to be. Sampled at points the scalar
        // island field says are well inside rock, so a piece that reads negative there is a piece that is
        // carving solid island away.
        // The vertical extent an island can occupy: the band, plus the reach a layer fades over.
        int islandFloor = com.barrenskies.worldgen.sky.SkyIslandDensity.islandFloor();
        int islandCeiling = com.barrenskies.worldgen.sky.SkyIslandDensity.islandCeiling();
        var partsField = new com.barrenskies.worldgen.sky.SkyIslandDensity.Field(
            randomState.getOrCreateNoise(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLANDS),
            randomState.getOrCreateNoise(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_RIDGES),
            randomState.getOrCreateNoise(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_DETAIL),
            randomState.getOrCreateNoise(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLAND_LANDFORM),
            com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get(),
            com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_TOP.get(),
            com.barrenskies.BarrenSkiesConfig.ISLAND_LAYERS.get(),
            com.barrenskies.BarrenSkiesConfig.ISLAND_THRESHOLD.get(),
            com.barrenskies.BarrenSkiesConfig.ISLAND_SCALE.get(),
            com.barrenskies.BarrenSkiesConfig.LANDFORM_STRENGTH.get(),
            com.barrenskies.BarrenSkiesConfig.LANDFORM_SQUASH.get(),
            com.barrenskies.BarrenSkiesConfig.LANDFORM_NOISE.get());
        List<int[]> inside = new ArrayList<>();
        for (int i = 0; i < 40000 && inside.size() < 400; i++) {
            int px = 32000 + (i % 200) * 13;
            int pz = 32000 + (i / 200) * 17;
            var col = com.barrenskies.worldgen.sky.SkyIslandDensity.columnOf(partsField, px, pz);
            for (int y = islandCeiling; y >= islandFloor; y -= 3) {
                if (com.barrenskies.worldgen.sky.SkyIslandDensity.density(partsField, col, px, y, pz) > 0.4D) {
                    inside.add(new int[] { px, y, pz });
                    break;
                }
            }
        }
        // What the caves actually did to the islands. Two numbers, because there are two ways this goes
        // wrong and they look nothing alike: rock is how much island is left, and enclosed air is how much
        // of it is cave. Caves that work take a slice off the first and put it into the second; caves that
        // do not work take all of the first and leave none of the second.
        //
        // Measured on islands the field has already found rather than over a block of chunks, because a
        // block of chunks small enough to generate quickly is easily small enough to contain no island at
        // all -- which reads as every island deleted, and did, for two runs.
        long rock = 0L;
        long enclosed = 0L;
        int patches = 0;
        for (int p = 0; p < inside.size() && patches < 12; p += 31) {
            int[] centre = inside.get(p);
            patches++;
            for (int dx = -8; dx <= 8; dx += 2) {
                for (int dz = -8; dz <= 8; dz += 2) {
                    int top = Integer.MIN_VALUE;
                    int bottom = Integer.MAX_VALUE;
                    for (int y = islandCeiling; y >= islandFloor; y--) {
                        pos.set(centre[0] + dx, y, centre[2] + dz);
                        if (!level.getBlockState(pos).isAir()) {
                            rock++;
                            top = Math.max(top, y);
                            bottom = Math.min(bottom, y);
                        }
                    }
                    for (int y = bottom; y <= top; y++) {
                        pos.set(centre[0] + dx, y, centre[2] + dz);
                        if (level.getBlockState(pos).isAir()) {
                            enclosed++;
                        }
                    }
                }
            }
        }
        BarrenSkies.LOG.info("[bench] islands: caves {}, lifted {} blocks. Over {} island patches, {} rock "
            + "and {} air between the highest and lowest rock in a column. Hollowness {}%.",
            com.barrenskies.BarrenSkiesConfig.ISLAND_CAVES.get(),
            com.barrenskies.worldgen.sky.IslandCaves.offsetFor(),
            patches, rock, enclosed,
            String.format("%.1f", enclosed * 100.0 / Math.max(1L, rock + enclosed)));

        // The same reading on columns that certainly have an island above them. The region the timings come
        // from is 256 blocks across and can miss islands altogether, and none out of four thousand there is
        // a measurement of nothing. These columns were chosen by the island field, so every one of them has
        // island over it, and every one is a column where a shipwreck would have been lifted into the sky.
        int islandSeen = 0;
        int islandStillAbove = 0;
        int groundAnswerHigh = level.getMinBuildHeight();
        long worldAnswerTotal = 0L;
        long groundAnswerTotal = 0L;
        for (int[] at : inside) {
            var c = level.getChunk(at[0] >> 4, at[2] >> 4, ChunkStatus.FULL, true);
            int world = c.getHeight(oceanFloor, at[0] & 15, at[2] & 15) + 1;
            int ground = com.barrenskies.worldgen.GroundHeight.of(c, oceanFloor, at[0], at[2]);
            if (world <= islandFloor) {
                continue;
            }
            islandSeen++;
            islandStillAbove += ground > islandFloor ? 1 : 0;
            groundAnswerHigh = Math.max(groundAnswerHigh, ground);
            worldAnswerTotal += world;
            groundAnswerTotal += ground;
        }
        BarrenSkies.LOG.info("[bench] on {} of {} island columns the world reports a height above the island "
            + "floor, mean Y {}. Replaced by the ground reading that mean is Y {}, the highest Y {}, and {} "
            + "are still above the floor. Anything above the floor is a shipwreck in the sky.",
            islandSeen, inside.size(),
            islandSeen == 0 ? 0 : worldAnswerTotal / islandSeen,
            islandSeen == 0 ? 0 : groundAnswerTotal / islandSeen,
            groundAnswerHigh, islandStillAbove);

        // Where the sky stops being blocked. The claim to test is that an island shades the ground for a
        // while below it and then, at some height, stops -- so this walks a column that certainly has
        // island over it from the top of the band down to the sea floor, reading the sky light the server
        // actually stored. Sampled beside the island as well, far enough out to be open sky, because a
        // number on its own says nothing: what matters is the difference between the two.
        var skyLayer = net.minecraft.world.level.LightLayer.SKY;
        int probed = 0;
        for (int[] at : inside) {
            if (probed >= 4) {
                break;
            }
            // Well inside an island rather than near a rim, or the shade is only ever a few blocks wide.
            if (!com.barrenskies.worldgen.sky.SkyIslandDensity.hasIsland(
                    randomState.getOrCreateNoise(com.barrenskies.worldgen.sky.SkyIslandDensity.ISLANDS),
                    at[0], at[2], com.barrenskies.BarrenSkiesConfig.ISLAND_LAYERS.get(),
                    com.barrenskies.BarrenSkiesConfig.ISLAND_THRESHOLD.get() + 0.12D,
                    com.barrenskies.BarrenSkiesConfig.ISLAND_SCALE.get())) {
                continue;
            }
            probed++;
            StringBuilder under = new StringBuilder();
            StringBuilder beside = new StringBuilder();
            for (int y = islandCeiling; y >= 0; y -= 16) {
                pos.set(at[0], y, at[2]);
                under.append(y).append(":").append(level.getBrightness(skyLayer, pos)).append(" ");
                pos.set(at[0] + 512, y, at[2] + 512);
                beside.append(y).append(":").append(level.getBrightness(skyLayer, pos)).append(" ");
            }
            BarrenSkies.LOG.info("[bench] sky light under island at {},{}: {}", at[0], at[2], under.toString().trim());
            BarrenSkies.LOG.info("[bench] sky light 512 blocks away:      {}", beside.toString().trim());
        }

        // Sky light across the ground under an island, block by block rather than one column every
        // sixteen. The column probe above cannot see the thing that was actually wrong: light that is
        // right in some blocks and nought in others, scattered. A histogram over a patch shows it at once,
        // where a single column can pass while the ground beside it is dark.
        for (int patch = 0; patch < Math.min(3, inside.size()); patch++) {
            int[] centre = inside.get(patch == 0 ? 0 : patch * 7 % inside.size());
            int[] histogram = new int[16];
            int columns = 0;
            for (int dx = -16; dx < 16; dx++) {
                for (int dz = -16; dz < 16; dz++) {
                    int x = centre[0] + dx;
                    int z = centre[2] + dz;
                    var c = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                    int standOn = com.barrenskies.worldgen.GroundHeight.of(
                        c, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    pos.set(x, standOn, z);
                    histogram[level.getBrightness(skyLayer, pos)]++;
                    columns++;
                }
            }
            StringBuilder spread = new StringBuilder();
            for (int level0 = 0; level0 < 16; level0++) {
                if (histogram[level0] > 0) {
                    spread.append(level0).append(":").append(histogram[level0]).append(" ");
                }
            }
            BarrenSkies.LOG.info("[bench] sky light on the ground under an island at {},{}, {} columns "
                + "one block above the surface: {}", centre[0], centre[2], columns, spread.toString().trim());
        }

        // Ore, above and below, counted per ten thousand solid blocks so the two are comparable. The
        // island band is smaller than the underground and holds far less rock, so raw counts say nothing;
        // what the setting claims is that a block of island stone is as likely to be ore as a block of
        // underground stone, and that is a ratio.
        int oreBands = 4;
        java.util.Set<Long> oreChunks = new java.util.LinkedHashSet<>();
        for (int[] at : inside) {
            oreChunks.add(net.minecraft.world.level.ChunkPos.asLong(at[0] >> 4, at[2] >> 4));
        }
        long islandSolid = 0L;
        long groundSolid = 0L;
        long[] bandSolid = new long[oreBands];
        java.util.TreeMap<String, long[]> oreCounts = new java.util.TreeMap<>();
        int oreChunksRead = 0;
        for (long packed : oreChunks) {
            if (oreChunksRead++ >= 32) {
                break;
            }
            var cp = new net.minecraft.world.level.ChunkPos(packed);
            var c = level.getChunk(cp.x, cp.z, ChunkStatus.FULL, true);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = level.getMinBuildHeight(); y <= islandCeiling; y++) {
                        pos.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z);
                        var state = c.getBlockState(pos);
                        if (state.isAir() || !state.getFluidState().isEmpty()) {
                            continue;
                        }
                        boolean high = y >= islandFloor;
                        int band = high
                            ? Math.min(oreBands - 1,
                                (y - islandFloor) * oreBands / Math.max(1, islandCeiling - islandFloor))
                            : -1;
                        if (high) {
                            islandSolid++;
                            bandSolid[band]++;
                        } else {
                            groundSolid++;
                        }
                        if (!state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) {
                            continue;
                        }
                        String ore = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock()).getPath()
                            .replace("deepslate_", "").replace("_ore", "");
                        long[] tally = oreCounts.computeIfAbsent(ore, k -> new long[2 + oreBands]);
                        tally[high ? 0 : 1]++;
                        if (high) {
                            tally[2 + band]++;
                        }
                    }
                }
            }
        }
        long islandOre = 0L;
        long groundOre = 0L;
        for (long[] tally : oreCounts.values()) {
            islandOre += tally[0];
            groundOre += tally[1];
        }
        double islandRate = islandSolid == 0 ? 0.0 : islandOre * 10000.0 / islandSolid;
        double groundRate = groundSolid == 0 ? 0.0 : groundOre * 10000.0 / groundSolid;
        BarrenSkies.LOG.info("[bench] ore over {} island chunks, rarity setting {}. Solid blocks: {} in the "
            + "island band, {} below it. Band solid, lowest island to highest: {}.",
            oreChunksRead, com.barrenskies.BarrenSkiesConfig.ISLAND_ORE_RARITY.get(),
            islandSolid, groundSolid, java.util.Arrays.toString(bandSolid));
        BarrenSkies.LOG.info("[bench] ore in total: island {} per 10k stone against ground {}, which is {}% "
            + "of the ground. The setting asks for {}%.",
            String.format("%.2f", islandRate), String.format("%.2f", groundRate),
            String.format("%.0f", groundRate == 0.0 ? 0.0 : islandRate * 100.0 / groundRate),
            com.barrenskies.BarrenSkiesConfig.ISLAND_ORE_RARITY.get());
        for (var entry : oreCounts.entrySet()) {
            long[] tally = entry.getValue();
            StringBuilder spread = new StringBuilder();
            for (int band = 0; band < oreBands; band++) {
                spread.append(band == 0 ? "" : " ")
                    .append(String.format("%.0f%%",
                        tally[0] == 0 ? 0.0 : tally[2 + band] * 100.0 / tally[0]));
            }
            BarrenSkies.LOG.info("[bench] ore {}: island {} per 10k stone, ground {} per 10k stone. "
                + "Across the band low to high: {}",
                entry.getKey(),
                String.format("%.2f", islandSolid == 0 ? 0.0 : tally[0] * 10000.0 / islandSolid),
                String.format("%.2f", groundSolid == 0 ? 0.0 : tally[1] * 10000.0 / groundSolid),
                spread);
        }

        // Noise has to be wired in before any of this can be read. A density function comes out of a codec
        // holding noise parameters and no noise; the game fills the noise in when it builds the random
        // state, and these are our own copies, which it never saw. Read unwired they all return zero, which
        // is not obviously wrong -- it looks like a constant, and a constant is exactly what a broken cave
        // mask looks like too.
        net.minecraft.world.level.levelgen.DensityFunction.Visitor wire =
            new net.minecraft.world.level.levelgen.DensityFunction.Visitor() {
                @Override
                public net.minecraft.world.level.levelgen.DensityFunction apply(
                    net.minecraft.world.level.levelgen.DensityFunction function) {
                    return function;
                }

                @Override
                public net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder visitNoise(
                    net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder holder) {
                    return holder.noiseData().unwrapKey()
                        .map(key -> new net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder(
                            holder.noiseData(), randomState.getOrCreateNoise(key)))
                        .orElse(holder);
                }
            };

        // What the blocks actually came out as, on the columns the field found. Always, caves or not, so
        // the two runs can be subtracted: the columns are chosen from the island field alone and the caves
        // do not move them.
        long worldSolid = 0L;
        int walked = 0;
        for (int[] at : inside) {
            if (walked >= 150) {
                break;
            }
            walked++;
            for (int y = islandFloor; y <= islandCeiling; y++) {
                pos.set(at[0], y, at[2]);
                if (!level.getBlockState(pos).isAir()) {
                    worldSolid++;
                }
            }
        }
        BarrenSkies.LOG.info("[bench] on {} island columns the world wrote {} solid blocks, caves {}.",
            walked, worldSolid, com.barrenskies.BarrenSkiesConfig.ISLAND_CAVES.get());

        if (com.barrenskies.worldgen.sky.IslandCaves.PARTS.isEmpty()) {
            BarrenSkies.LOG.info("[bench] no cave parts recorded; caves were not built.");
        } else {

            for (var part : com.barrenskies.worldgen.sky.IslandCaves.PARTS.entrySet()) {
                var wired = part.getValue().mapAll(wire);
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                double sum = 0.0D;
                int negative = 0;
                for (int[] at : inside) {
                    double v = wired.compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                            at[0], at[1], at[2]));
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                    sum += v;
                    if (v < 0.0D) {
                        negative++;
                    }
                }
                BarrenSkies.LOG.info("[bench] cave part {}: min {}, mean {}, max {}, negative in {} of {}",
                    part.getKey(), String.format("%.3f", lo), String.format("%.3f", sum / Math.max(1, inside.size())),
                    String.format("%.3f", hi), negative, inside.size());
            }
        }
        // How much the mask would carve, over whole island columns rather than at the surface. The mask
        // and the rock are read the way build combines them, so this is what the caves ask for before the
        // chunk interpolates anything -- which is the number to compare the blocks against.
        var maskFn = com.barrenskies.worldgen.sky.IslandCaves.PARTS.get("mask");
        if (maskFn != null) {
            var wiredMask = maskFn.mapAll(wire);
            long solidBefore = 0L;
            long solidAfter = 0L;
            long maskNegative = 0L;
            long sampled = 0L;
            for (int[] at : inside) {
                for (int y = islandFloor; y <= islandCeiling; y++) {
                    var ctx = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                        at[0], y, at[2]);
                    double r = com.barrenskies.worldgen.sky.SkyIslandDensity.density(
                        partsField, at[0], y, at[2]);
                    double m = wiredMask.compute(ctx);
                    sampled++;
                    if (m < 0.0D) {
                        maskNegative++;
                    }
                    if (r <= 0.0D) {
                        continue;
                    }
                    solidBefore++;
                    if (Math.min(r, m) > 0.0D) {
                        solidAfter++;
                    }
                }
            }
            long predictedNoCaves = 0L;
            long predictedCaves = 0L;
            for (int i = 0; i < walked; i++) {
                int[] at = inside.get(i);
                for (int y = islandFloor; y <= islandCeiling; y++) {
                    var ctx = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                        at[0], y, at[2]);
                    double r = com.barrenskies.worldgen.sky.SkyIslandDensity.density(
                        partsField, at[0], y, at[2]);
                    if (r <= 0.0D) {
                        continue;
                    }
                    predictedNoCaves++;
                    if (Math.min(r, wiredMask.compute(ctx)) > 0.0D) {
                        predictedCaves++;
                    }
                }
            }
            BarrenSkies.LOG.info("[bench] on {} island columns: the density says {} solid without caves and "
                + "{} with; the world wrote {}. The caves asked for {}% and the blocks show {}%.",
                walked, predictedNoCaves, predictedCaves, worldSolid,
                String.format("%.1f", (predictedNoCaves - predictedCaves) * 100.0 / Math.max(1L, predictedNoCaves)),
                String.format("%.1f", (predictedNoCaves - worldSolid) * 100.0 / Math.max(1L, predictedNoCaves)));

            BarrenSkies.LOG.info("[bench] over {} column samples: mask negative in {}, rock solid in {}, "
                + "still solid after the carve in {}. The caves ask for {}% of the island.",
                sampled, maskNegative, solidBefore, solidAfter,
                String.format("%.1f", (solidBefore - solidAfter) * 100.0 / Math.max(1L, solidBefore)));
        }


        // Where an island's rock actually stops, against where islandFloor says it stops.
        //
        // The underside spline does not end at its last control point; past it the curve carries on down
        // the tip slope, which is what makes a point rather than a plug. What was supposed to stop it is
        // the fade, which the density clamps at two layer reaches -- but a clamp is not a stop. Below the
        // clamp the fade contributes a constant, so a column whose underside passes two reaches has a
        // density that never goes negative again and its rock runs down until the band gate cuts it off a
        // few blocks above the sea. That is a pillar.
        //
        // Reported as the margin rather than as a count, because a count of zero over any sample this can
        // afford says nothing: the columns at risk are the deepest few of an island's interior and there
        // may be one per island or none in a hundred. What the margin says is whether the shape forbids
        // this or merely has not happened yet, and those are different answers.
        {
            int reach = com.barrenskies.worldgen.sky.SkyIslandDensity.layerReach();
            int bandFloor = com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get() - reach * 2;
            int layers = com.barrenskies.BarrenSkiesConfig.ISLAND_LAYERS.get();
            double[] worstBottom = new double[layers];
            java.util.Arrays.fill(worstBottom, Double.NEGATIVE_INFINITY);
            double atGate = Double.NEGATIVE_INFINITY;
            double atFloor = Double.NEGATIVE_INFINITY;
            int[] worstAt = null;
            long columns = 0L;
            long belowFloor = 0L;
            long pillars = 0L;
            for (int i = 0; i < 700; i++) {
                for (int j = 0; j < 700; j++) {
                    int px = 32000 + i * 7;
                    int pz = 32000 + j * 7;
                    var col = com.barrenskies.worldgen.sky.SkyIslandDensity.columnOf(partsField, px, pz);
                    boolean any = false;
                    for (int k = 0; k < layers; k++) {
                        if (!Double.isNaN(col.top()[k])) {
                            any = true;
                            worstBottom[k] = Math.max(worstBottom[k], col.bottom()[k]);
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    columns++;
                    double gate = com.barrenskies.worldgen.sky.SkyIslandDensity.density(
                        partsField, col, px, bandFloor, pz);
                    double floorAt = com.barrenskies.worldgen.sky.SkyIslandDensity.density(
                        partsField, col, px, islandFloor - 1, pz);
                    if (gate > atGate) {
                        atGate = gate;
                        worstAt = new int[] { px, pz };
                    }
                    atFloor = Math.max(atFloor, floorAt);
                    if (floorAt > 0.0D) {
                        belowFloor++;
                    }
                    if (gate > 0.0D) {
                        pillars++;
                    }
                }
            }
            StringBuilder perLayer = new StringBuilder();
            for (int k = 0; k < layers; k++) {
                perLayer.append(k == 0 ? "" : ", ").append(String.format("%.3f", worstBottom[k]));
            }
            BarrenSkies.LOG.info(
                "[bench] {} island columns of 490000. Deepest underside per layer: {} -- against 2.000, "
                    + "past which the fade stops falling and the rock never ends. Density at the band gate "
                    + "{} is at most {} (worst column {}), at islandFloor {} at most {}. Solid below the "
                    + "floor in {} columns, still solid at the gate in {}.",
                columns, perLayer, bandFloor, String.format("%.4f", atGate),
                worstAt == null ? "none" : worstAt[0] + "," + worstAt[1],
                islandFloor, String.format("%.4f", atFloor), belowFloor, pillars);
        }
        BarrenSkies.LOG.info("[bench] sampled {} points the scalar field calls solid island.", inside.size());

        var chunk = level.getChunk(2000, 2000, ChunkStatus.FULL, true);
        // A real wreck, found and then generated, and where its planks actually landed. This is the
        // question the last fix answered wrongly: the generator was told to report the sea floor and did,
        // and ships still flew, because a shipwreck works its own height out again from the finished world
        // when its pieces are laid down. Planks, because nothing else out at sea is made of them.
        for (String name : new String[] { "minecraft:shipwreck", "minecraft:ocean_ruin_cold" }) {
            var wanted = event.getServer().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .getHolder(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.parse(name)))
                .orElse(null);
            if (wanted == null) {
                continue;
            }
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, net.minecraft.core.HolderSet.direct(wanted),
                new net.minecraft.core.BlockPos(32000, 64, 32000), 40, false);
            if (found == null) {
                BarrenSkies.LOG.info("[bench] no {} within 40 chunks of the probe area.", name);
                continue;
            }
            net.minecraft.core.BlockPos at = found.getFirst();
            int sky = 0;
            int sea = 0;
            int lowestPlank = Integer.MAX_VALUE;
            int highestPlank = Integer.MIN_VALUE;
            for (int dx = -32; dx <= 32; dx++) {
                for (int dz = -32; dz <= 32; dz++) {
                    for (int y = 0; y < islandCeiling; y++) {
                        pos.set(at.getX() + dx, y, at.getZ() + dz);
                        if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.PLANKS)) {
                            continue;
                        }
                        sky += y > islandFloor ? 1 : 0;
                        sea += y > islandFloor ? 0 : 1;
                        lowestPlank = Math.min(lowestPlank, y);
                        highestPlank = Math.max(highestPlank, y);
                    }
                }
            }
            BarrenSkies.LOG.info("[bench] {} at {}: {} plank blocks below the island floor and {} above it, "
                + "spread from Y {} to Y {}. Anything above the floor is a wreck in the sky.",
                name, at, sea, sky,
                lowestPlank == Integer.MAX_VALUE ? 0 : lowestPlank,
                highestPlank == Integer.MIN_VALUE ? 0 : highestPlank);
        }

        BarrenSkies.LOG.info("[bench] world is Y {} to {}, {} sections a chunk, dimension {}.",
            level.getMinBuildHeight(), level.getMaxBuildHeight(), chunk.getSections().length,
            level.dimension().location());
    }

    private static void sleep() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
