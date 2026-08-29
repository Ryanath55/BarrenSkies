package com.barrenskies.worldgen.sky;

import com.barrenskies.BarrenSkies;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;

/**
 * Raises the height limits inside a set of surface rules so they also work at sky island altitude.
 *
 * <p>Surface rules are written against absolute heights. Terralith gates its yellowstone gravel on a
 * gradient that is false above Y 115, and it is far from alone; a rule written for ground level simply
 * takes its other branch at Y 400, which is how a biome ends up as bare calcite. Skylands over the Sea
 * never meets this because it puts its islands at Y 144 to 240, inside the range those rules expect.
 *
 * <p>Since our islands sit higher, the rules are lifted to meet them instead. The tree is round tripped
 * through its own codec, every absolute height in a vertical gradient or height check is shifted, and the
 * result is applied only above the island floor. Below that the original rules are used untouched, so the
 * ground keeps its own surfaces.
 *
 * <p>Only absolute anchors are moved. One expressed relative to the world floor or ceiling is already
 * measured from somewhere, and shifting it would move it twice.
 */
public final class LiftedSurfaceRules {
    private LiftedSurfaceRules() {
    }

    /**
     * @param original the world's own surface rules
     * @param floor lowest height an island reaches
     * @param groundReference the height the original rules are written around, normally sea level
     */
    public static SurfaceRules.RuleSource liftAbove(SurfaceRules.RuleSource original, int floor, int groundReference) {
        int offset = floor - groundReference;
        if (offset <= 0) {
            return original;
        }

        SurfaceRules.RuleSource lifted;
        try {
            JsonElement encoded = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
            shiftAnchors(encoded, offset);
            lifted = SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        } catch (RuntimeException e) {
            // A rule set we cannot round trip is not worth failing world generation over. The islands just
            // keep the unlifted rules, which is how they behaved before.
            BarrenSkies.LOG.warn("Could not lift surface rules to island altitude, using them unchanged.", e);
            return original;
        }

        BarrenSkies.LOG.info("Lifted surface rule heights by {} blocks for the sky islands.", offset);
        // Above the floor the lifted rules get first refusal; anything they decline, and everything below,
        // falls through to the originals.
        return SurfaceRules.sequence(
            SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(floor), 0), lifted), original
        );
    }

    /** Walks the encoded tree, moving every absolute height it finds. */
    private static void shiftAnchors(JsonElement element, int offset) {
        if (element instanceof JsonArray array) {
            array.forEach(child -> shiftAnchors(child, offset));
            return;
        }
        if (!(element instanceof JsonObject object)) {
            return;
        }

        String type = object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : "";
        switch (type) {
            case "minecraft:vertical_gradient" -> {
                shiftAnchor(object, "true_at_and_below", offset);
                shiftAnchor(object, "false_at_and_above", offset);
            }
            case "minecraft:y_above" -> shiftAnchor(object, "anchor", offset);
            default -> {
            }
        }

        object.entrySet().forEach(entry -> shiftAnchors(entry.getValue(), offset));
    }

    private static void shiftAnchor(JsonObject owner, String field, int offset) {
        if (!(owner.get(field) instanceof JsonObject anchor) || !anchor.has("absolute")) {
            return;
        }
        anchor.addProperty("absolute", anchor.get("absolute").getAsInt() + offset);
    }
}
