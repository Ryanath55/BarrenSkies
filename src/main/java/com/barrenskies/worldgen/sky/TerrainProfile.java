package com.barrenskies.worldgen.sky;

/**
 * How rugged an island's terrain is. Scales are in blocks and are applied to separate noise layers, so
 * a mountain island can be given ridges without also making a plains island lumpy.
 *
 * @param macroRelief broad elevation change across the island
 * @param ridgeRelief sharp ridged detail, what makes mountains read as mountains
 * @param basinCarve depth of scooped basins, which is where lakes end up sitting
 * @param roughness fine surface break-up
 * @param edgeExponent how sharply the island tapers to its rim; higher means more cliff-like
 * @param overhang how far the 3D field may push rock past the smooth envelope; higher means more
 *                 overhangs, arches and detached shards
 * @param terrainInfluence how strongly the world noise router shapes this island, so a rugged island
 *                         borrows more of the terrain mod character than a flat one
 * @param shelfHeight height of the stepped ledges cut into a cliff face, which is what stops an edge
 *                    reading as one sheer wall
 * @param minimumThickness never let the raft get thinner than this, so nothing punches through
 */
public record TerrainProfile(
    double macroRelief, double ridgeRelief, double basinCarve, double roughness, double edgeExponent, double overhang, double terrainInfluence, double shelfHeight, int minimumThickness
) {
    /** Gentle, broad swells. Plains, deserts, savanna. */
    public static final TerrainProfile FLAT = new TerrainProfile(7.0D, 1.0D, 3.0D, 1.0D, 1.6D, 0.70D, 0.55D, 2.5D, 12);
    /** Moderate hills and dips. Forests, taiga, jungle. */
    public static final TerrainProfile ROLLING = new TerrainProfile(14.0D, 4.0D, 5.0D, 1.8D, 1.8D, 1.05D, 0.85D, 4.0D, 14);
    /** Strong ridges and steep faces. Mountains, peaks, windswept. */
    public static final TerrainProfile RUGGED = new TerrainProfile(22.0D, 26.0D, 4.0D, 2.5D, 2.4D, 1.65D, 1.25D, 7.0D, 16);
    /** Layered shelves and sharp cut edges. Badlands. */
    public static final TerrainProfile ERODED = new TerrainProfile(18.0D, 12.0D, 6.0D, 2.0D, 2.6D, 1.45D, 1.1D, 8.5D, 14);
    /** Low and pitted, so water pools. Swamps, marshes. */
    public static final TerrainProfile BASIN = new TerrainProfile(6.0D, 1.0D, 11.0D, 1.4D, 1.4D, 0.80D, 0.6D, 2.0D, 12);
}
