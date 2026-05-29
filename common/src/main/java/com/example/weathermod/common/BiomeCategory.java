package com.example.weathermod.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Groups vanilla biomes into climate categories that bias weather selection.
 * <p>
 * Each category defines a probability split across {@link WeatherType#CLEAR},
 * {@link WeatherType#RAIN} and {@link WeatherType#THUNDER}. The thunder weight
 * is implicit — it is whatever probability mass remains after clear and rain —
 * so the three weights always sum to exactly {@code 1.0}.
 *
 * <table>
 *   <caption>Category weights (clear / rain / thunder)</caption>
 *   <tr><th>Category</th><th>Clear</th><th>Rain</th><th>Thunder</th></tr>
 *   <tr><td>DRY</td><td>0.60</td><td>0.25</td><td>0.15</td></tr>
 *   <tr><td>TEMPERATE</td><td>0.33</td><td>0.33</td><td>0.33</td></tr>
 *   <tr><td>WET</td><td>0.20</td><td>0.50</td><td>0.30</td></tr>
 *   <tr><td>COLD</td><td>0.30</td><td>0.40</td><td>0.30</td></tr>
 * </table>
 */
public enum BiomeCategory {

    /** Deserts, badlands, savannas — mostly clear skies. */
    DRY      (0.60,    0.25),
    /** Plains, forests and everything not otherwise classified — even split. */
    TEMPERATE(1.0 / 3, 1.0 / 3),
    /** Jungles, swamps, mushroom fields — rain-heavy. */
    WET      (0.20,    0.50),
    /** Snowy / frozen biomes — frequent precipitation, moderate thunder. */
    COLD     (0.30,    0.40);

    private final double clearWeight;
    private final double rainWeight;
    /** Pre-computed {@code clearWeight + rainWeight} cutoff for selection. */
    private final double rainThreshold;

    BiomeCategory(double clear, double rain) {
        this.clearWeight   = clear;
        this.rainWeight    = rain;
        this.rainThreshold = clear + rain;
    }

    /** Probability of {@link WeatherType#CLEAR} for this category. */
    public double clearWeight() {
        return clearWeight;
    }

    /** Probability of {@link WeatherType#RAIN} for this category. */
    public double rainWeight() {
        return rainWeight;
    }

    /**
     * Probability of {@link WeatherType#THUNDER} for this category — the
     * remaining mass after clear and rain, so all three weights sum to 1.0.
     */
    public double thunderWeight() {
        return 1.0 - rainThreshold;
    }

    /**
     * Picks a weather type using this category's weights and the shared
     * {@link ThreadLocalRandom}. Safe to call from the server main thread.
     */
    public WeatherType weightedRandom() {
        return weightedRandom(ThreadLocalRandom.current());
    }

    /**
     * Picks a weather type using this category's weights and the supplied
     * random source. Exposed so callers (and tests) can supply a seeded,
     * deterministic {@link RandomGenerator}.
     *
     * @param rng the random source (must not be {@code null})
     */
    public WeatherType weightedRandom(RandomGenerator rng) {
        Objects.requireNonNull(rng, "rng");
        double r = rng.nextDouble();
        if (r < clearWeight)   return WeatherType.CLEAR;
        if (r < rainThreshold) return WeatherType.RAIN;
        return WeatherType.THUNDER;
    }

    // ── Biome → Category mapping ─────────────────────────────────────────

    private static final String VANILLA_NAMESPACE = "minecraft:";
    private static final Map<String, BiomeCategory> BIOME_MAP = buildBiomeMap();

    private static Map<String, BiomeCategory> buildBiomeMap() {
        Map<String, BiomeCategory> m = new HashMap<>();

        // Dry biomes — predominantly clear skies.
        putAll(m, DRY,
            "desert", "badlands", "eroded_badlands", "wooded_badlands",
            "savanna", "savanna_plateau", "windswept_savanna");

        // Wet biomes — rain-heavy.
        putAll(m, WET,
            "jungle", "sparse_jungle", "bamboo_jungle",
            "swamp", "mangrove_swamp", "mushroom_fields", "lush_caves");

        // Cold biomes — moderate rain/snow, less thunder.
        putAll(m, COLD,
            "snowy_plains", "ice_spikes", "snowy_taiga",
            "frozen_river", "frozen_ocean", "snowy_beach",
            "grove", "snowy_slopes", "frozen_peaks", "jagged_peaks",
            "deep_frozen_ocean", "deep_cold_ocean", "cold_ocean");

        return Map.copyOf(m);
    }

    private static void putAll(Map<String, BiomeCategory> m, BiomeCategory category, String... biomes) {
        for (String b : biomes) {
            m.put(VANILLA_NAMESPACE + b, category);
        }
    }

    /**
     * Looks up the category for a namespaced biome ID (e.g.
     * {@code "minecraft:desert"}). Any biome that is not explicitly mapped —
     * including {@code null}, modded biomes, and most vanilla ones — resolves
     * to {@link #TEMPERATE}.
     *
     * @param biomeId the namespaced biome ID; may be {@code null}
     * @return the mapped category, never {@code null}
     */
    public static BiomeCategory fromBiomeId(String biomeId) {
        if (biomeId == null) {
            return TEMPERATE;
        }
        return BIOME_MAP.getOrDefault(biomeId, TEMPERATE);
    }
}
