package com.example.weathermod.common;

import java.util.HashMap;
import java.util.Locale;
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
    private final double rainWeight;
    private final double thunderWeight;
    private final double rainThreshold; // clearWeight + rainWeight

    BiomeCategory(double clear, double rain, double thunder) {
        this.clearWeight   = clear;
        this.rainWeight    = rain;
        this.thunderWeight = thunder;
        this.rainThreshold = clear + rain;
    }

    /** Picks a random weather type using this category's weighted probabilities. */
    public WeatherType weightedRandom() {
        return selectWeather(ThreadLocalRandom.current().nextDouble());
    }

    /** Selects weather for a roll in [0.0, 1.0). Package-private for deterministic tests. */
    WeatherType selectWeather(double roll) {
        if (Double.isNaN(roll) || roll < 0.0 || roll >= 1.0) {
            throw new IllegalArgumentException("roll must be in [0.0, 1.0)");
        }
        if (roll < clearWeight)   return WeatherType.CLEAR;
        if (roll < rainThreshold) return WeatherType.RAIN;
        return WeatherType.THUNDER;
    }

    public int clearChancePercent() {
        return toPercent(clearWeight);
    }

    public int rainChancePercent() {
        return toPercent(rainWeight);
    }

    public int thunderChancePercent() {
        return toPercent(thunderWeight);
    }

    /** Returns a compact forecast-friendly probability summary. */
    public String describeProbabilities() {
        return "Clear " + clearChancePercent() + "%"
            + ", Rain " + rainChancePercent() + "%"
            + ", Thunder " + thunderChancePercent() + "%";
    }

    private static int toPercent(double weight) {
        return (int) Math.round(weight * 100.0);
    }

    // Biome -> Category mapping

    private static final Map<String, BiomeCategory> BIOME_MAP = buildBiomeMap();

    private static Map<String, BiomeCategory> buildBiomeMap() {
        Map<String, BiomeCategory> m = new HashMap<>();

        // Dry biomes - predominantly clear skies
        for (String b : new String[]{
            "desert", "badlands", "eroded_badlands", "wooded_badlands",
            "savanna", "savanna_plateau", "windswept_savanna"
        }) {
            m.put("minecraft:" + b, DRY);
        }

        // Wet biomes - rain-heavy
        for (String b : new String[]{
            "jungle", "sparse_jungle", "bamboo_jungle",
            "swamp", "mangrove_swamp", "mushroom_fields", "lush_caves"
        }) {
            m.put("minecraft:" + b, WET);
        }

        // Cold biomes - moderate rain/snow, less thunder
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
     * Looks up the category for a biome ID. Namespaced IDs are preferred, but
     * bare vanilla IDs are accepted. Unknown or blank IDs default to TEMPERATE.
     */
    public static BiomeCategory fromBiomeId(String biomeId) {
        if (biomeId == null) return TEMPERATE;

        String normalized = biomeId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return TEMPERATE;
        if (!normalized.contains(":")) normalized = "minecraft:" + normalized;

        return BIOME_MAP.getOrDefault(normalized, TEMPERATE);
    }
}
