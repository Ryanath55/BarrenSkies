package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkies;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Moves every height written into a density function, so one built for the ground works in the sky.
 *
 * <p>The same trick as the surface rules, for the same reason and against the same problem. Minecraft's
 * caves are written against absolute heights and there are more of them than you would think: the noodle
 * caves gate every noise they use behind Y minus sixty to three hundred and twenty one and return zero
 * outside it, which through the rest of their arithmetic comes out at minus 0.075 -- and the router takes
 * the lesser of that and the terrain, so noodle applied unshifted at island altitude does not leave the
 * islands cave-ridden, it deletes them. Spaghetti carries a gradient from minus sixty four to three
 * hundred and twenty, and cave entrances one from minus ten to thirty, which is what puts an entrance at
 * ground level rather than a hundred blocks under it.
 *
 * <p>The tree is round tripped through its own codec and every absolute height in it is moved. Only
 * absolute ones: a height already measured from the world floor or ceiling would be moved twice.
 *
 * <p>What this cannot reach is a height inside a function referred to by name rather than written inline.
 * The encoding keeps those as references and they resolve, on the way back, to the registered originals.
 * None of the cave functions has one that matters -- the two they name between them, a thickness modulator
 * and a roughness function, are pure noise with no height in either -- but a pack that changes that would
 * quietly keep its ground level heights.
 */
public final class LiftedDensity {
    private LiftedDensity() {
    }

    /**
     * @param original a density function written for ground altitude
     * @param offset how far up to move every absolute height in it
     * @return the same function at the new altitude, or the original if it could not be round tripped
     */
    public static DensityFunction liftBy(DynamicOps<JsonElement> ops, DensityFunction original, int offset) {
        try {
            JsonElement encoded = DensityFunction.DIRECT_CODEC.encodeStart(ops, original).getOrThrow();
            shiftHeights(encoded, offset);
            return DensityFunction.DIRECT_CODEC.parse(ops, unwrapCellMarkers(encoded)).getOrThrow();
        } catch (RuntimeException e) {
            // Not worth failing world generation over. The caller decides what to do without it; leaving
            // the function unshifted would be worse than leaving it out, since unshifted is what deletes
            // the islands.
            BarrenSkies.LOG.warn("Could not lift a density function to island altitude; leaving it out.", e);
            return null;
        }
    }

    /**
     * Takes off the two caches that only work at the top of a router entry.
     *
     * <p>Interpolated and cache_all_in_cell are not caches of a value, they are caches of a noise cell, and
     * the chunk fills them by walking its cells and asking each one for its corners. That only happens for
     * the function the router hands it. Nest one inside another and the inner one is asked for a value
     * while the outer one is mid-fill, at which point it reads a slice nobody has filled and answers zero.
     *
     * <p>Zero is the worst answer it could give, because it does not look broken. The cave mask is combined
     * with the rock by taking the lesser of the two, so a mask of zero is a mask that says carve everything
     * down to exactly the solid threshold -- and the islands come out not thin, not holed, but gone, with
     * nothing in the log and every piece of the mask reading healthy when sampled on its own.
     *
     * <p>Off they come, then. The functions still compute the same thing, once per block instead of once
     * per cell corner.
     */
    private static JsonElement unwrapCellMarkers(JsonElement element) {
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, unwrapCellMarkers(array.get(i)));
            }
            return array;
        }
        if (!(element instanceof JsonObject object)) {
            return element;
        }
        for (String key : java.util.List.copyOf(object.keySet())) {
            object.add(key, unwrapCellMarkers(object.get(key)));
        }
        String type = object.has("type") && object.get("type").isJsonPrimitive()
            ? object.get("type").getAsString()
            : "";
        if (("minecraft:interpolated".equals(type) || "minecraft:cache_all_in_cell".equals(type))
            && object.has("argument")) {
            return object.get("argument");
        }
        return object;
    }

    private static void shiftHeights(JsonElement element, int offset) {
        if (element instanceof JsonArray array) {
            array.forEach(child -> shiftHeights(child, offset));
            return;
        }
        if (!(element instanceof JsonObject object)) {
            return;
        }

        String type = object.has("type") && object.get("type").isJsonPrimitive()
            ? object.get("type").getAsString()
            : "";
        switch (type) {
            case "minecraft:y_clamped_gradient" -> {
                shift(object, "from_y", offset);
                shift(object, "to_y", offset);
            }
            case "minecraft:range_choice" -> {
                // Only when the thing being ranged over is the height. A range_choice is just as often
                // used on a noise -- noodle brackets its own noise at zero to decide whether to carve at
                // all -- and moving that bound by four hundred blocks turns the function off entirely.
                if (object.has("input") && object.get("input").isJsonPrimitive()
                    && "minecraft:y".equals(object.get("input").getAsString())) {
                    shift(object, "min_inclusive", offset);
                    shift(object, "max_exclusive", offset);
                }
            }
            default -> {
            }
        }

        object.entrySet().forEach(entry -> shiftHeights(entry.getValue(), offset));
    }

    private static void shift(JsonObject owner, String field, int offset) {
        if (owner.get(field) instanceof com.google.gson.JsonPrimitive primitive && primitive.isNumber()) {
            owner.addProperty(field, primitive.getAsDouble() + offset);
        }
    }
}
