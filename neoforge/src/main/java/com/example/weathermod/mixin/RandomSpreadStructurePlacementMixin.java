package com.example.weathermod.mixin;

import com.example.weathermod.common.StructureDensity;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge version — uses Mojang mappings.
 * Package: net.minecraft.world.level.levelgen.structure.placement
 *
 * <p>Shrinks {@code RandomSpreadStructurePlacement} spacing/separation at
 * construction so structures generate closer together. Placements are laid out
 * one per {@code spacing × spacing} chunk cell, so scaling spacing by
 * 0.87 raises structure density by roughly {@code 1 / 0.87²}
 * ≈ 1.3× rather than the 1.15× the old comment claimed.
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {

    @Mutable @Shadow private int spacing;
    @Mutable @Shadow private int separation;

    /**
     * Guards against a second application. {@code method = "<init>"} matches
     * <em>every</em> constructor of the target, so on a version where one
     * constructor delegates to another both TAIL injections fire and the boost
     * would compound to {@code 0.87²}.
     */
    @Unique
    private boolean wsm_densityBoosted;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wsm_boostStructures(CallbackInfo ci) {
        if (this.wsm_densityBoosted) return;
        this.wsm_densityBoosted = true;

        // The math (factor, floors, the separation < spacing invariant) lives in
        // common/ as plain ints so it is shared with the other loader and the
        // Paper plugin, and unit-tested. Both results are computed from the
        // original pair before either field is written.
        int oldSpacing    = this.spacing;
        int oldSeparation = this.separation;

        this.spacing    = StructureDensity.boostedSpacing(oldSpacing);
        this.separation = StructureDensity.boostedSeparation(oldSpacing, oldSeparation);
    }
}
