package com.example.weathermod.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the platform-independent weather engine.
 * <p>
 * The original v1.4.0 suite is kept verbatim at the top level (behaviour
 * parity), followed by expanded coverage for the rewritten engine.
 */
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
        WeatherType last = null;
        for (int i = 0; i < 3; i++) {
            last = engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        }

        assertFalse(engine.isTimedWeatherActive());
        assertEquals(List.of(WeatherType.CLEAR), appliedWeather);
        assertEquals(WeatherType.CLEAR, last, "Expiring tick should return CLEAR");
        assertTrue(engine.wasLastTickTimedExpiry(),
            "Engine should report timed-expiry on the expiring tick");
    }

    @Test
    void wasLastTickTimedExpiry_falseForNormalInit() {
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        assertFalse(engine.wasLastTickTimedExpiry());
    }

    @Test
    void wasLastTickTimedExpiry_resetsOnNextTick() {
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        engine.setTimedWeather(WeatherType.RAIN, 1, recorder);

        // The 1-tick timer expires on the very next tick
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        assertTrue(engine.wasLastTickTimedExpiry());

        // Next tick — no longer the expiry tick
        engine.tick("world", BiomeCategory.TEMPERATE, recorder);
        assertFalse(engine.wasLastTickTimedExpiry());
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

    // ═════════════════════════════════════════════════════════════════════
    // Expanded coverage for the rewritten engine (v1.7.0)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WeatherType")
    class WeatherTypeTests {

        @Test
        void commandNames_areLowercaseTokens() {
            assertEquals("clear",   WeatherType.CLEAR.getCommandName());
            assertEquals("rain",    WeatherType.RAIN.getCommandName());
            assertEquals("thunder", WeatherType.THUNDER.getCommandName());
        }

        @Test
        void ordering_isStableContract() {
            // Platforms persist/compare ordinals; lock the order in.
            assertEquals(0, WeatherType.CLEAR.ordinal());
            assertEquals(1, WeatherType.RAIN.ordinal());
            assertEquals(2, WeatherType.THUNDER.ordinal());
        }

        @Test
        void fromName_nullReturnsNull() {
            assertNull(WeatherType.fromName(null));
        }

        @Test
        void fromName_resolvesEveryConstant() {
            for (WeatherType t : WeatherType.cachedValues()) {
                assertEquals(t, WeatherType.fromName(t.name()));
                assertEquals(t, WeatherType.fromName(t.getCommandName()));
            }
        }
    }

    @Nested
    @DisplayName("BiomeCategory weights")
    class BiomeWeightTests {

        @Test
        void everyCategoryWeightsSumToOne() {
            for (BiomeCategory c : BiomeCategory.values()) {
                double sum = c.clearWeight() + c.rainWeight() + c.thunderWeight();
                assertEquals(1.0, sum, 1e-9, c + " weights should sum to 1.0");
            }
        }

        @Test
        void weightsMatchDocumentedTable() {
            assertWeights(BiomeCategory.DRY,       0.60,    0.25,    0.15);
            assertWeights(BiomeCategory.TEMPERATE, 1.0 / 3, 1.0 / 3, 1.0 / 3);
            assertWeights(BiomeCategory.WET,       0.20,    0.50,    0.30);
            assertWeights(BiomeCategory.COLD,      0.30,    0.40,    0.30);
        }

        private void assertWeights(BiomeCategory c, double clear, double rain, double thunder) {
            assertEquals(clear,   c.clearWeight(),   1e-9, c + " clear");
            assertEquals(rain,    c.rainWeight(),    1e-9, c + " rain");
            assertEquals(thunder, c.thunderWeight(), 1e-9, c + " thunder");
        }

        @Test
        void allWeightsArePositive() {
            for (BiomeCategory c : BiomeCategory.values()) {
                assertTrue(c.clearWeight()   > 0, c + " clear");
                assertTrue(c.rainWeight()    > 0, c + " rain");
                assertTrue(c.thunderWeight() > 0, c + " thunder");
            }
        }
    }

    @Nested
    @DisplayName("BiomeCategory.weightedRandom (deterministic)")
    class WeightedRandomBoundaryTests {

        @Test
        void zeroAlwaysClear() {
            for (BiomeCategory c : BiomeCategory.values()) {
                assertEquals(WeatherType.CLEAR, c.weightedRandom(fixed(0.0)), c.toString());
            }
        }

        @Test
        void boundariesAreHalfOpen_dry() {
            // DRY: clear=[0,0.60) rain=[0.60,0.85) thunder=[0.85,1)
            assertEquals(WeatherType.CLEAR,   BiomeCategory.DRY.weightedRandom(fixed(0.59)));
            assertEquals(WeatherType.RAIN,    BiomeCategory.DRY.weightedRandom(fixed(0.60)));
            assertEquals(WeatherType.RAIN,    BiomeCategory.DRY.weightedRandom(fixed(0.84)));
            assertEquals(WeatherType.THUNDER, BiomeCategory.DRY.weightedRandom(fixed(0.85)));
            assertEquals(WeatherType.THUNDER, BiomeCategory.DRY.weightedRandom(fixed(0.999)));
        }

        @Test
        void boundariesAreHalfOpen_wet() {
            // WET: clear=[0,0.20) rain=[0.20,0.70) thunder=[0.70,1)
            assertEquals(WeatherType.CLEAR,   BiomeCategory.WET.weightedRandom(fixed(0.19)));
            assertEquals(WeatherType.RAIN,    BiomeCategory.WET.weightedRandom(fixed(0.20)));
            assertEquals(WeatherType.RAIN,    BiomeCategory.WET.weightedRandom(fixed(0.69)));
            assertEquals(WeatherType.THUNDER, BiomeCategory.WET.weightedRandom(fixed(0.70)));
        }

        @Test
        void seededDistributionRoughlyMatchesWeights() {
            RandomGenerator rng = new Random(42L);
            int n = 200_000;
            int clear = 0, rain = 0, thunder = 0;
            for (int i = 0; i < n; i++) {
                switch (BiomeCategory.COLD.weightedRandom(rng)) {
                    case CLEAR   -> clear++;
                    case RAIN    -> rain++;
                    case THUNDER -> thunder++;
                }
            }
            // COLD = 0.30 / 0.40 / 0.30 — allow a 3% absolute tolerance.
            assertEquals(0.30, clear   / (double) n, 0.03, "clear share");
            assertEquals(0.40, rain    / (double) n, 0.03, "rain share");
            assertEquals(0.30, thunder / (double) n, 0.03, "thunder share");
        }

        @Test
        void nullRngRejected() {
            assertThrows(NullPointerException.class, () -> BiomeCategory.DRY.weightedRandom(null));
        }
    }

    @Nested
    @DisplayName("BiomeCategory.fromBiomeId")
    class FromBiomeIdTests {

        @Test
        void nullDefaultsToTemperate() {
            assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId(null));
        }

        @Test
        void emptyStringDefaultsToTemperate() {
            assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId(""));
        }

        @Test
        void allMappedDryBiomes() {
            for (String b : new String[]{
                "desert", "badlands", "eroded_badlands", "wooded_badlands",
                "savanna", "savanna_plateau", "windswept_savanna"}) {
                assertEquals(BiomeCategory.DRY, BiomeCategory.fromBiomeId("minecraft:" + b), b);
            }
        }

        @Test
        void unnamespacedIdIsNotMatched() {
            // The map keys are namespaced; a bare id must fall through to TEMPERATE.
            assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId("desert"));
        }
    }

    @Nested
    @DisplayName("Engine input validation")
    class ValidationTests {

        @Test
        void tickRejectsNullArguments() {
            assertThrows(NullPointerException.class,
                () -> engine.tick(null, BiomeCategory.TEMPERATE, recorder));
            assertThrows(NullPointerException.class,
                () -> engine.tick("w", null, recorder));
            assertThrows(NullPointerException.class,
                () -> engine.tick("w", BiomeCategory.TEMPERATE, null));
        }

        @Test
        void setTimedWeatherRejectsNullArguments() {
            assertThrows(NullPointerException.class,
                () -> engine.setTimedWeather(null, 100, recorder));
            assertThrows(NullPointerException.class,
                () -> engine.setTimedWeather(WeatherType.RAIN, 100, null));
        }

        @Test
        void setTimedWeatherClampsNonPositiveToOne() {
            engine.setTimedWeather(WeatherType.RAIN, 0, recorder);
            assertTrue(engine.isTimedWeatherActive(), "0 ticks should clamp to 1 and arm the timer");
            assertEquals(1, engine.getTimedWeatherTicksRemaining());
            assertEquals(WeatherType.RAIN, appliedWeather.get(0));
            assertEquals(1, appliedDurations.get(0), "weather applied with the clamped duration");

            // Expires on the very next tick.
            WeatherType result = engine.tick("w", BiomeCategory.TEMPERATE, recorder);
            assertEquals(WeatherType.CLEAR, result);
            assertFalse(engine.isTimedWeatherActive());
        }

        @Test
        void setTimedWeatherNegativeIsClamped() {
            engine.setTimedWeather(WeatherType.THUNDER, -500, recorder);
            assertEquals(1, engine.getTimedWeatherTicksRemaining());
        }
    }

    @Nested
    @DisplayName("Timed override behaviour")
    class TimedOverrideTests {

        @Test
        void setTimedWeatherAppliesWithGivenDuration() {
            engine.setTimedWeather(WeatherType.RAIN, 1234, recorder);
            assertEquals(1, appliedDurations.size());
            assertEquals(1234, appliedDurations.get(0),
                "timed weather should be applied with its own duration, not WEATHER_DURATION");
        }

        @Test
        void expiryAppliesClearWithWeatherDuration() {
            engine.setTimedWeather(WeatherType.RAIN, 2, recorder);
            appliedWeather.clear();
            appliedDurations.clear();

            engine.tick("w", BiomeCategory.TEMPERATE, recorder); // 2 -> 1
            engine.tick("w", BiomeCategory.TEMPERATE, recorder); // 1 -> 0, expire

            assertEquals(List.of(WeatherType.CLEAR), appliedWeather);
            assertEquals(WeatherEngine.WEATHER_DURATION, appliedDurations.get(0));
        }

        @Test
        void cyclingTimerIsPausedThenRearmedAfterExpiry() {
            engine.tick("w", BiomeCategory.TEMPERATE, recorder); // init cycling timer
            int beforeOverride = engine.getTicksUntilNextChange("w");

            engine.setTimedWeather(WeatherType.RAIN, 3, recorder);
            // Cycling timer must not advance while the override is active.
            engine.tick("w", BiomeCategory.TEMPERATE, recorder);
            engine.tick("w", BiomeCategory.TEMPERATE, recorder);
            assertEquals(beforeOverride, engine.getTicksUntilNextChange("w"),
                "cycling timer should be frozen during a timed override");

            engine.tick("w", BiomeCategory.TEMPERATE, recorder); // expiry re-arms timer
            int afterExpiry = engine.getTicksUntilNextChange("w");
            assertTrue(afterExpiry >= WeatherEngine.MIN_TICKS && afterExpiry <= WeatherEngine.MAX_TICKS,
                "timer should be re-armed to a fresh interval, got " + afterExpiry);
        }

        @Test
        void timedCountdownIsGlobalAcrossWorlds() {
            // Documents the contract: the timed countdown decrements on every
            // tick() call regardless of world, so platforms must drive it once
            // per server tick (single overworld on Fabric/NeoForge; primary
            // world only on Paper).
            engine.setTimedWeather(WeatherType.RAIN, 5, recorder);
            engine.tick("world_a", BiomeCategory.TEMPERATE, recorder);
            engine.tick("world_b", BiomeCategory.TEMPERATE, recorder);
            assertEquals(3, engine.getTimedWeatherTicksRemaining(),
                "two tick() calls should decrement the global timed counter twice");
        }
    }

    @Nested
    @DisplayName("Multi-world cycling")
    class MultiWorldTests {

        @Test
        void worldsTrackIndependentTimers() {
            engine.tick("a", BiomeCategory.TEMPERATE, recorder);
            engine.tick("b", BiomeCategory.TEMPERATE, recorder);
            // Advance only 'a'.
            engine.tick("a", BiomeCategory.TEMPERATE, recorder);
            int aTicks = engine.getTicksUntilNextChange("a");
            int bTicks = engine.getTicksUntilNextChange("b");
            assertTrue(aTicks >= 0 && bTicks >= 0);
            // 'b' was initialised but never advanced; 'a' was advanced once.
            // They are tracked separately (both within bounds).
            assertTrue(bTicks <= WeatherEngine.MAX_TICKS && bTicks >= WeatherEngine.MIN_TICKS);
        }

        @Test
        void forgetResetsAWorld() {
            engine.tick("a", BiomeCategory.TEMPERATE, recorder);
            assertNotEquals(-1, engine.getTicksUntilNextChange("a"));
            engine.forget("a");
            assertEquals(-1, engine.getTicksUntilNextChange("a"));
            // Forgetting an unknown world is a harmless no-op.
            assertDoesNotThrow(() -> engine.forget("never-seen"));
        }

        @Test
        void resetClearsAllState() {
            engine.tick("a", BiomeCategory.TEMPERATE, recorder);
            engine.tick("b", BiomeCategory.TEMPERATE, recorder);
            engine.setTimedWeather(WeatherType.THUNDER, 100, recorder);

            engine.reset();

            assertFalse(engine.isTimedWeatherActive());
            assertNull(engine.getTimedWeatherType());
            assertEquals(0, engine.getTimedWeatherTicksRemaining());
            assertEquals(-1, engine.getTicksUntilNextChange("a"));
            assertEquals(-1, engine.getTicksUntilNextChange("b"));
            assertFalse(engine.wasLastTickTimedExpiry());

            // Engine is reusable after reset.
            assertNull(engine.tick("a", BiomeCategory.TEMPERATE, recorder));
            assertNotEquals(-1, engine.getTicksUntilNextChange("a"));
        }
    }

    @Nested
    @DisplayName("Seeded engine (deterministic RNG injection)")
    class SeededEngineTests {

        @Test
        void initialIntervalMatchesSeededSequence() {
            long seed = 987654321L;
            WeatherEngine seeded = new WeatherEngine(new Random(seed));
            seeded.tick("w", BiomeCategory.TEMPERATE, recorder);

            int expected = WeatherEngine.MIN_TICKS + new Random(seed).nextInt(WeatherEngine.INTERVAL_RANGE);
            assertEquals(expected, seeded.getTicksUntilNextChange("w"),
                "seeded engine should roll the predicted first interval");
        }

        @Test
        void cyclingChangesExactlyWhenTimerHitsZero() {
            WeatherEngine seeded = new WeatherEngine(new Random(2024L));
            seeded.tick("w", BiomeCategory.TEMPERATE, recorder);
            int interval = seeded.getTicksUntilNextChange("w");

            // No change for the first (interval - 1) ticks...
            for (int i = 0; i < interval - 1; i++) {
                assertNull(seeded.tick("w", BiomeCategory.TEMPERATE, recorder),
                    "no change expected before the timer elapses (i=" + i + ")");
            }
            // ...then a change on the tick the timer reaches zero.
            WeatherType changed = seeded.tick("w", BiomeCategory.TEMPERATE, recorder);
            assertNotNull(changed, "weather should change exactly when the timer elapses");
            assertEquals(1, appliedWeather.size());
            assertEquals(WeatherEngine.WEATHER_DURATION, appliedDurations.get(0));
        }

        @Test
        void nullRngRejected() {
            assertThrows(NullPointerException.class, () -> new WeatherEngine(null));
        }
    }

    @Nested
    @DisplayName("Constants and formatting")
    class ConstantsTests {

        @Test
        void intervalConstantsAreConsistent() {
            assertEquals(30 * 60 * 20, WeatherEngine.MIN_TICKS);
            assertEquals(60 * 60 * 20, WeatherEngine.MAX_TICKS);
            assertEquals(WeatherEngine.MAX_TICKS - WeatherEngine.MIN_TICKS + 1, WeatherEngine.INTERVAL_RANGE);
            assertTrue(WeatherEngine.WEATHER_DURATION > WeatherEngine.MAX_TICKS,
                "applied duration must outlast a full cycle so vanilla never reverts early");
        }

        @Test
        void formatTicks_negativeClampsToZero() {
            assertEquals("0s", WeatherEngine.formatTicks(-1));
            assertEquals("0s", WeatherEngine.formatTicks(-100_000));
        }

        @Test
        void formatTicks_exactlyOneMinute() {
            assertEquals("1m", WeatherEngine.formatTicks(60 * 20));
        }

        @Test
        void formatTicks_subSecond() {
            // 10 ticks = 0.5s -> truncates to 0s
            assertEquals("0s", WeatherEngine.formatTicks(10));
        }

        @Test
        void formatTicks_largeValue() {
            // 90 minutes exactly
            assertEquals("90m", WeatherEngine.formatTicks(90 * 60 * 20));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** A {@link RandomGenerator} whose {@code nextDouble()} always returns a fixed value. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0L; }
            @Override public double nextDouble() { return value; }
        };
    }
}
