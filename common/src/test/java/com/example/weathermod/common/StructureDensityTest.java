package com.example.weathermod.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the structure-density math shared by the Fabric/NeoForge mixins and
 * the Paper plugin.
 */
class StructureDensityTest {

    /** Vanilla codec bound on both spacing and separation. */
    private static final int VANILLA_MAX = 4096;

    /**
     * Every {@code random_spread} structure set in vanilla 26.1.2
     * ({@code data/minecraft/worldgen/structure_set/*.json}) as
     * {@code { spacing, separation, boostedSpacing, boostedSeparation }}.
     * Pins the numbers players actually get, so a change to the factor or the
     * rounding shows up as a failing row rather than as a silent worldgen shift.
     */
    private static final int[][] VANILLA_26_1_2 = {
        { 24,  8, 20,  6 },  // ancient_cities
        {  1,  0,  1,  0 },  // buried_treasures
        { 32,  8, 27,  6 },  // desert_pyramids
        { 20, 11, 17,  9 },  // end_cities
        { 32,  8, 27,  6 },  // igloos
        { 32,  8, 27,  6 },  // jungle_temples
        {  1,  0,  1,  0 },  // mineshafts
        { 27,  4, 23,  3 },  // nether_complexes
        {  2,  1,  2,  1 },  // nether_fossils
        { 32,  5, 27,  4 },  // ocean_monuments
        { 20,  8, 17,  6 },  // ocean_ruins
        { 32,  8, 27,  6 },  // pillager_outposts
        { 40, 15, 34, 13 },  // ruined_portals
        { 24,  4, 20,  3 },  // shipwrecks
        { 32,  8, 27,  6 },  // swamp_huts
        { 34,  8, 29,  6 },  // trail_ruins
        { 34, 12, 29, 10 },  // trial_chambers
        { 34,  8, 29,  6 },  // villages
        { 80, 20, 69, 17 },  // woodland_mansions
    };

    /**
     * The expressions exactly as they stood in the two mixins and the Paper
     * plugin before they were moved here. The equivalence test below is what
     * shows the move changed no structure placement.
     */
    private static int[] legacy(int spacing, int separation) {
        final float factor = 0.87f;
        int newSpacing    = Math.min(spacing,    Math.max(2, (int) (spacing    * factor)));
        int newSeparation = Math.min(separation, Math.max(1, (int) (separation * factor)));
        if (newSeparation >= newSpacing) newSeparation = Math.max(0, newSpacing - 1);
        return new int[]{ newSpacing, newSeparation };
    }

    @Test
    void vanillaStructureSets_getTheExpectedValues() {
        for (int[] row : VANILLA_26_1_2) {
            assertAll("spacing=" + row[0] + " separation=" + row[1],
                () -> assertEquals(row[2], StructureDensity.boostedSpacing(row[0])),
                () -> assertEquals(row[3], StructureDensity.boostedSeparation(row[0], row[1])));
        }
    }

    @Test
    void matchesTheLegacyExpressions_overEveryPlausibleInput() {
        // Includes negatives and separation >= spacing: values vanilla's codec
        // rejects but a mod constructing a placement in code could still pass.
        for (int spacing = -8; spacing <= 512; spacing++) {
            for (int separation = -8; separation <= 512; separation++) {
                int[] expected = legacy(spacing, separation);
                assertEquals(expected[0], StructureDensity.boostedSpacing(spacing),
                    "spacing for (" + spacing + ", " + separation + ")");
                assertEquals(expected[1], StructureDensity.boostedSeparation(spacing, separation),
                    "separation for (" + spacing + ", " + separation + ")");
            }
        }
    }

    @Test
    void validInput_staysValidAndNeverGrows() {
        for (int spacing = 1; spacing <= VANILLA_MAX; spacing++) {
            int newSpacing = StructureDensity.boostedSpacing(spacing);
            assertTrue(newSpacing >= 1 && newSpacing <= spacing, "spacing " + spacing + " -> " + newSpacing);

            // Sampling separation keeps this at ~0.5M cases; the two ends of the
            // range are where the clamps bite, so they are always included.
            for (int separation = 0; separation < spacing;
                 separation += (separation < 64 || separation > spacing - 64) ? 1 : 37) {
                int newSeparation = StructureDensity.boostedSeparation(spacing, separation);
                assertTrue(newSeparation >= 0 && newSeparation <= separation,
                    "separation grew: (" + spacing + ", " + separation + ") -> " + newSeparation);
                assertTrue(newSeparation < newSpacing,
                    "vanilla needs separation < spacing: (" + spacing + ", " + separation
                        + ") -> (" + newSpacing + ", " + newSeparation + ")");
            }
        }
    }

    @Test
    void valuesAtOrBelowTheFloors_areLeftAlone() {
        assertEquals(1, StructureDensity.boostedSpacing(1));
        assertEquals(2, StructureDensity.boostedSpacing(2));
        assertEquals(0, StructureDensity.boostedSeparation(1, 0));
        assertEquals(0, StructureDensity.boostedSeparation(40, 0));
        assertEquals(1, StructureDensity.boostedSeparation(2, 1));
        assertEquals(1, StructureDensity.boostedSeparation(40, 1));
    }

    @Test
    void smallestSpacingThatShrinks() {
        // 3 * 0.87 = 2.61 -> 2; separation 2 -> 1 keeps it below the new spacing.
        assertEquals(2, StructureDensity.boostedSpacing(3));
        assertEquals(1, StructureDensity.boostedSeparation(3, 2));
    }

    @Test
    void separationNotBelowSpacing_isPulledUnderTheNewSpacing() {
        // Not constructible through vanilla's codec, but must not leave a
        // placement that makes nextInt(spacing - separation) throw.
        assertEquals(7, StructureDensity.boostedSeparation(10, 10)); // spacing 10 -> 8
        assertEquals(7, StructureDensity.boostedSeparation(10, 50));
    }

    @Test
    void extremeValues_doNotOverflow() {
        int spacing = StructureDensity.boostedSpacing(Integer.MAX_VALUE);
        assertTrue(spacing > 0 && spacing < Integer.MAX_VALUE, "got " + spacing);

        int separation = StructureDensity.boostedSeparation(Integer.MAX_VALUE, Integer.MAX_VALUE - 1);
        assertTrue(separation >= 0 && separation < spacing, "got " + separation);

        // Negative garbage is passed through rather than "repaired" upwards.
        assertEquals(Integer.MIN_VALUE, StructureDensity.boostedSpacing(Integer.MIN_VALUE));
        assertEquals(-5, StructureDensity.boostedSpacing(-5));
    }

    @Test
    void factorAndFloors_areTheDocumentedOnes() {
        assertEquals(0.87f, StructureDensity.DENSITY_FACTOR);
        assertEquals(2, StructureDensity.MIN_SPACING);
        assertEquals(1, StructureDensity.MIN_SEPARATION);
    }
}
