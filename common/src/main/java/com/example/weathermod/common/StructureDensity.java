package com.example.weathermod.common;

/**
 * The structure-density boost as plain integer math.
 * <p>
 * Fabric and NeoForge apply this from a mixin on
 * {@code RandomSpreadStructurePlacement}; Paper applies it by reflection. All
 * three used to carry their own copy of the same two expressions, which is the
 * kind of duplication that drifts — this is now the only place the numbers live,
 * and the only place they can be unit-tested.
 * <p>
 * Placements are laid out one per {@code spacing × spacing} chunk cell, so
 * scaling spacing by {@link #DENSITY_FACTOR} raises structure density by roughly
 * {@code 1 / 0.87²} ≈ 1.3×.
 *
 * <h2>Guarantees</h2>
 * For any input vanilla accepts ({@code 0 <= separation < spacing}):
 * <ul>
 *   <li>neither value is ever <em>raised</em> — a placement already at or below
 *       a floor is left alone rather than rounded up;</li>
 *   <li>the result still satisfies {@code 0 <= separation < spacing}, which
 *       vanilla needs ({@code random.nextInt(spacing - separation)} throws on a
 *       non-positive bound).</li>
 * </ul>
 */
public final class StructureDensity {

    /** Multiplier applied to both spacing and separation. */
    public static final float DENSITY_FACTOR = 0.87f;
    /** Scaling never takes spacing below this. */
    public static final int MIN_SPACING = 2;
    /** Scaling never takes separation below this. */
    public static final int MIN_SEPARATION = 1;

    private StructureDensity() {}

    /**
     * The boosted spacing for a placement.
     *
     * @param spacing the placement's current spacing, in chunks
     * @return the new spacing; never greater than {@code spacing}
     */
    public static int boostedSpacing(int spacing) {
        // min(...) on the outside: the floor may only stop a reduction, never
        // cause an increase. Written as a bare max(MIN_SPACING, scaled) it raised
        // spacing 1 -> 2.
        return Math.min(spacing, Math.max(MIN_SPACING, (int) (spacing * DENSITY_FACTOR)));
    }

    /**
     * The boosted separation for a placement. Takes the placement's
     * <em>original</em> spacing, not the boosted one, so callers can compute both
     * results from the same pair of inputs in either order.
     *
     * @param spacing    the placement's current (un-boosted) spacing, in chunks
     * @param separation the placement's current separation, in chunks
     * @return the new separation; never greater than {@code separation} for
     *         valid input, and always below {@link #boostedSpacing(int)
     *         boostedSpacing(spacing)} when that is positive
     */
    public static int boostedSeparation(int spacing, int separation) {
        int newSpacing    = boostedSpacing(spacing);
        int newSeparation = Math.min(separation, Math.max(MIN_SEPARATION, (int) (separation * DENSITY_FACTOR)));
        // Vanilla requires separation < spacing.
        if (newSeparation >= newSpacing) {
            newSeparation = Math.max(0, newSpacing - 1);
        }
        return newSeparation;
    }
}
