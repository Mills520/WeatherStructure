package com.example.weathermod.mixin;

import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge edition — Mojang mappings.
 * Reduces spacing/separation by 13% → ~15% more structure placement attempts.
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {

    private static final float DENSITY_FACTOR = 0.87f;

    @Mutable @Shadow private int spacing;
    @Mutable @Shadow private int separation;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wsm_boostStructures(CallbackInfo ci) {
        int newSpacing    = Math.max(2, (int)(this.spacing    * DENSITY_FACTOR));
        int newSeparation = Math.max(1, (int)(this.separation * DENSITY_FACTOR));
        if (newSeparation >= newSpacing) newSeparation = newSpacing - 1;
        this.spacing    = newSpacing;
        this.separation = newSeparation;
    }
}
