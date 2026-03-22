package com.example.weathermod.common;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Groups vanilla biomes into climate categories that influence weather probabilities.
 * <p>
 * When the weather engine picks the next random weather, it uses the weighted
 * probabilities of the category matching the biome at the world's spawn point.
 */
public enum BiomeCategory {

    DRY      (0.60, 0.25, 0.15),
    TEMPERATE(1.0 / 3, 1.0 / 3, 1.0 / 3),
    WET      (0.20, 0.50, 0.30),
    COLD     (0.30, 0.40, 0.30);

    private final double clearWeight;
    private final double rainThreshold; // clearWeight + rainWeight

    BiomeCategory(double clear, double rain, double thunder) {
        this.clearWeight   = clear;
        this.rainThreshold = clear + rain;
    }

    /** Picks a random weather type using this category's weighted probabilities. */
    public WeatherType weightedRandom() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < clearWeight)   return WeatherType.CLEAR;
        if (r < rainThreshold) return WeatherType.RAIN;
        return WeatherType.THUNDER;
    }

    // ── Biome → Category mapping ─────────────────────────────────────────

    private static final Map<String, BiomeCategory> BIOME_MAP = buildBiomeMap();

    private static Map<String, BiomeCategory> buildBiomeMap() {
        Map<String, BiomeCategory> m = new HashMap<>();

        // Dry biomes — predominantly clear skies
        for (String b : new String[]{
            "desert", "badlands", "eroded_badlands", "wooded_badlands",
            "savanna", "savanna_plateau", "windswept_savanna"
        }) {
            m.put("minecraft:" + b, DRY);
        }

        // Wet biomes — rain-heavy
        for (String b : new String[]{
            "jungle", "sparse_jungle", "bamboo_jungle",
            "swamp", "mangrove_swamp", "mushroom_fields", "lush_caves"
        }) {
            m.put("minecraft:" + b, WET);
        }

        // Cold biomes — moderate rain/snow, less thunder
        for (String b : new String[]{
            "snowy_plains", "ice_spikes", "snowy_taiga",
            "frozen_river", "frozen_ocean", "snowy_beach",
            "grove", "snowy_slopes", "frozen_peaks", "jagged_peaks",
            "deep_frozen_ocean", "deep_cold_ocean", "cold_ocean"
        }) {
            m.put("minecraft:" + b, COLD);
        }

        return Map.copyOf(m);
    }

    /**
     * Looks up the category for a namespaced biome ID (e.g. {@code "minecraft:desert"}).
     * Returns {@link #TEMPERATE} for any biome not explicitly mapped.
     */
    public static BiomeCategory fromBiomeId(String biomeId) {
        return BIOME_MAP.getOrDefault(biomeId, TEMPERATE);
    }
}
