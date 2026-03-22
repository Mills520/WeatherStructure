package com.example.weathermod.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeatherEngineTest {

    private WeatherEngine engine;
    private final List<WeatherType> appliedWeather = new ArrayList<>();
    private final List<Integer> appliedDurations = new ArrayList<>();

    private final WeatherEngine.WeatherApplier recorder = (type, duration) -> {
        appliedWeather.add(type);
        appliedDurations.add(duration);
    };

    @BeforeEach
    void setUp() {
        engine = new WeatherEngine();
        appliedWeather.clear();
        appliedDurations.clear();
    }

    // ── randomInterval tests ─────────────────────────────────────────────

    @Test
    void randomInterval_withinBounds() {
        for (int i = 0; i < 1000; i++) {
            int interval = WeatherEngine.randomInterval();
            assertTrue(interval >= WeatherEngine.MIN_TICKS,
                "Interval " + interval + " below MIN_TICKS");
            assertTrue(interval <= WeatherEngine.MAX_TICKS,
                "Interval " + interval + " above MAX_TICKS");
        }
    }

    // ── formatTicks tests ────────────────────────────────────────────────

    @Test
    void formatTicks_minutesAndSeconds() {
        assertEquals("5m 30s", WeatherEngine.formatTicks(5 * 60 * 20 + 30 * 20));
    }

    @Test
    void formatTicks_minutesOnly() {
        assertEquals("2m", WeatherEngine.formatTicks(2 * 60 * 20));
    }

    @Test
    void formatTicks_secondsOnly() {
        assertEquals("45s", WeatherEngine.formatTicks(45 * 20));
    }

    @Test
    void formatTicks_zero() {
        assertEquals("0s", WeatherEngine.formatTicks(0));
    }

    // ── Tick lifecycle tests ─────────────────────────────────────────────

    @Test
    void firstTick_initialisesTimer_noWeatherChange() {
        WeatherType result = engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        assertNull(result, "First tick should not change weather");
        assertTrue(appliedWeather.isEmpty());
    }

    @Test
    void normalCycling_eventuallyChangesWeather() {
        // First tick: initialise
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);

        // Tick until weather changes (will happen within MAX_TICKS)
        WeatherType changed = null;
        for (int i = 0; i < WeatherEngine.MAX_TICKS + 1; i++) {
            changed = engine.tick("world", BiomeCategory.TEMPERATE, recorder);
            if (changed != null) break;
        }
        assertNotNull(changed, "Weather should change within MAX_TICKS");
        assertEquals(1, appliedWeather.size());
        assertEquals(WeatherEngine.WEATHER_DURATION, appliedDurations.get(0));
    }

    // ── Timed weather tests ──────────────────────────────────────────────

    @Test
    void setTimedWeather_appliesImmediately() {
        engine.setTimedWeather(WeatherType.THUNDER, 200, recorder);

        assertTrue(engine.isTimedWeatherActive());
        assertEquals(WeatherType.THUNDER, engine.getTimedWeatherType());
        assertEquals(200, engine.getTimedWeatherTicksRemaining());
        assertEquals(List.of(WeatherType.THUNDER), appliedWeather);
    }

    @Test
    void timedWeather_pausesNormalCycling() {
        // Init normal cycling
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);

        // Set short timed weather
        engine.setTimedWeather(WeatherType.RAIN, 5, recorder);
        appliedWeather.clear();

        // Tick 4 times — timed weather should count down, no normal cycling
        for (int i = 0; i < 4; i++) {
            assertNull(engine.tick("world", BiomeCategory.TEMPERATE, recorder));
        }
        assertTrue(engine.isTimedWeatherActive());
        assertTrue(appliedWeather.isEmpty());
    }

    @Test
    void timedWeather_revertsToClearOnExpiry() {
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        engine.setTimedWeather(WeatherType.RAIN, 3, recorder);
        appliedWeather.clear();

        // Tick 3 times to expire
        for (int i = 0; i < 3; i++) {
            engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        }

        assertFalse(engine.isTimedWeatherActive());
        assertEquals(List.of(WeatherType.CLEAR), appliedWeather);
    }

    @Test
    void timedWeatherStatus_noActiveTimer() {
        assertFalse(engine.isTimedWeatherActive());
        assertNull(engine.getTimedWeatherType());
        assertEquals(0, engine.getTimedWeatherTicksRemaining());
    }

    // ── getTicksUntilNextChange tests ────────────────────────────────────

    @Test
    void getTicksUntilNextChange_unknownWorld() {
        assertEquals(-1, engine.getTicksUntilNextChange("unknown"));
    }

    @Test
    void getTicksUntilNextChange_afterInit() {
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        int ticks = engine.getTicksUntilNextChange("world");
        assertTrue(ticks >= WeatherEngine.MIN_TICKS - 1 && ticks <= WeatherEngine.MAX_TICKS,
            "Initial timer should be within [MIN-1, MAX], got " + ticks);
    }

    // ── WeatherType tests ────────────────────────────────────────────────

    @Test
    void weatherType_fromName_caseInsensitive() {
        assertEquals(WeatherType.CLEAR, WeatherType.fromName("clear"));
        assertEquals(WeatherType.RAIN, WeatherType.fromName("RAIN"));
        assertEquals(WeatherType.THUNDER, WeatherType.fromName("Thunder"));
        assertNull(WeatherType.fromName("unknown"));
    }

    @Test
    void weatherType_cachedValues() {
        WeatherType[] v = WeatherType.cachedValues();
        assertEquals(3, v.length);
        assertSame(v, WeatherType.cachedValues(), "Should return same array instance");
    }

    // ── BiomeCategory tests ──────────────────────────────────────────────

    @Test
    void biomeCategory_knownBiomes() {
        assertEquals(BiomeCategory.DRY, BiomeCategory.fromBiomeId("minecraft:desert"));
        assertEquals(BiomeCategory.WET, BiomeCategory.fromBiomeId("minecraft:jungle"));
        assertEquals(BiomeCategory.COLD, BiomeCategory.fromBiomeId("minecraft:snowy_plains"));
        assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId("minecraft:plains"));
    }

    @Test
    void biomeCategory_unknownBiomeDefaultsToTemperate() {
        assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId("modded:custom_biome"));
    }

    @Test
    void biomeCategory_weightedRandom_producesAllTypes() {
        // Run enough trials that all weather types should appear for TEMPERATE (equal weights)
        Map<WeatherType, Integer> counts = new EnumMap<>(WeatherType.class);
        for (WeatherType t : WeatherType.cachedValues()) counts.put(t, 0);

        for (int i = 0; i < 10_000; i++) {
            WeatherType t = BiomeCategory.TEMPERATE.weightedRandom();
            counts.merge(t, 1, Integer::sum);
        }

        for (WeatherType t : WeatherType.cachedValues()) {
            assertTrue(counts.get(t) > 100,
                t + " should appear in weighted random, got " + counts.get(t));
        }
    }

    @Test
    void biomeCategory_dryBiome_favorsClear() {
        Map<WeatherType, Integer> counts = new EnumMap<>(WeatherType.class);
        for (WeatherType t : WeatherType.cachedValues()) counts.put(t, 0);

        for (int i = 0; i < 10_000; i++) {
            WeatherType t = BiomeCategory.DRY.weightedRandom();
            counts.merge(t, 1, Integer::sum);
        }

        assertTrue(counts.get(WeatherType.CLEAR) > counts.get(WeatherType.RAIN),
            "DRY biome should produce more CLEAR than RAIN");
        assertTrue(counts.get(WeatherType.CLEAR) > counts.get(WeatherType.THUNDER),
            "DRY biome should produce more CLEAR than THUNDER");
    }

    @Test
    void biomeCategory_wetBiome_favorsRain() {
        Map<WeatherType, Integer> counts = new EnumMap<>(WeatherType.class);
        for (WeatherType t : WeatherType.cachedValues()) counts.put(t, 0);

        for (int i = 0; i < 10_000; i++) {
            WeatherType t = BiomeCategory.WET.weightedRandom();
            counts.merge(t, 1, Integer::sum);
        }

        assertTrue(counts.get(WeatherType.RAIN) > counts.get(WeatherType.CLEAR),
            "WET biome should produce more RAIN than CLEAR");
    }
}
