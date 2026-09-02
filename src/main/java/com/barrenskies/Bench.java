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
            long began = System.nanoTime();
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    long one = System.nanoTime();
                    level.getChunk(originX + x, originZ + z, ChunkStatus.FULL, true);
                    if (pass == 0) {
                        perChunk.add(System.nanoTime() - one);
                    }
                }
            }
            long elapsed = System.nanoTime() - began;
            long after = used();
            System.gc();
            sleep();
            long collected = used();
            retained[pass] = (collected - before) >> 20;
            if (pass == 0) {
                took = elapsed;
                heapBefore = before;
                heapAfter = after;
                heapCollected = collected;
            }
        }

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
        int floor = com.barrenskies.BarrenSkiesConfig.SKY_ISLAND_BOTTOM.get()
            - com.barrenskies.worldgen.sky.SkyIslandDensity.layerReach();
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

        var chunk = level.getChunk(2000, 2000, ChunkStatus.FULL, true);
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
