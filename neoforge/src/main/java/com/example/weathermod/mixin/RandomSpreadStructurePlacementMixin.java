package com.example.weathermod.mixin;

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

    private static final float DENSITY_FACTOR = 0.87f;
    private static final int MIN_SPACING    = 2;
    private static final int MIN_SEPARATION = 1;

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

        // The clamps only ever reduce. Written the other way round
        // (max(MIN_SPACING, scaled)) they raised spacing from 1 to 2 and
        // separation from 0 to 1 on placements already at or below the floor,
        // making those structures rarer — the opposite of the intent.
        int newSpacing    = Math.min(this.spacing,    Math.max(MIN_SPACING,    (int) (this.spacing    * DENSITY_FACTOR)));
        int newSeparation = Math.min(this.separation, Math.max(MIN_SEPARATION, (int) (this.separation * DENSITY_FACTOR)));
        // Vanilla requires separation < spacing.
        if (newSeparation >= newSpacing) newSeparation = Math.max(0, newSpacing - 1);

        this.spacing    = newSpacing;
        this.separation = newSeparation;
    }
}
