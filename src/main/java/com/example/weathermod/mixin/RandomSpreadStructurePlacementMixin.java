package com.example.weathermod.mixin;

import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Increases structure spawn frequency by ~15% by reducing the spacing and
 * separation values of RandomSpreadStructurePlacement.
 *
 * Spacing  = average chunk-grid cell size between structure placement attempts.
 * Separation = minimum chunk distance between two placements within that grid.
 *
 * Reducing both by the DENSITY_FACTOR below makes structures generate closer
 * together, raising effective spawn density by roughly 15%.
 *
 * Examples with factor 0.87:
 *   Village  spacing 32 → 27  |  separation 8 → 6
 *   Mansion  spacing 80 → 69  |  separation 20 → 17
 *   Outpost  spacing 24 → 20  |  separation 4 → 3
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {

    /** Shrink spacing/separation by 13 % → ~15 % more structures. */
    private static final float DENSITY_FACTOR = 0.87f;

    private static final int MIN_SPACING    = 2;
    private static final int MIN_SEPARATION = 1;

    @Mutable @Shadow private int spacing;
    @Mutable @Shadow private int separation;

    /**
     * Runs after every constructor path so all structure types are affected.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void weathermod_boostStructureFrequency(CallbackInfo ci) {
        int newSpacing    = Math.max(MIN_SPACING,    (int)(this.spacing    * DENSITY_FACTOR));
        int newSeparation = Math.max(MIN_SEPARATION, (int)(this.separation * DENSITY_FACTOR));

        // Safety: separation must always be < spacing
        if (newSeparation >= newSpacing) {
            newSeparation = newSpacing - 1;
        }

        this.spacing    = newSpacing;
        this.separation = newSeparation;
    }
}
