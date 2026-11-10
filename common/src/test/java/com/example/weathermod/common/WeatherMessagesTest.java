package com.example.weathermod.common;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact wording of the shared command replies. The strings are
 * asserted literally on purpose: they were moved out of three platform classes
 * and must read the same as before.
 */
class WeatherMessagesTest {

    private static final WeatherEngine.WeatherApplier NO_OP = (type, duration) -> {};

    @Test
    void timedWeatherSet_usesTheCommandToken() {
        assertEquals("[WSM] Weather set to rain for 60s. Will revert to CLEAR after.",
            WeatherMessages.timedWeatherSet(WeatherType.RAIN, 60));
        assertEquals("[WSM] Weather set to thunder for 86400s. Will revert to CLEAR after.",
            WeatherMessages.timedWeatherSet(WeatherType.THUNDER, 86_400));
    }

    @Test
    void status_withNoOverride() {
        assertEquals("[WSM] No timed weather active. Normal cycling is running.",
            WeatherMessages.timedWeatherStatus(new WeatherEngine()));
    }

    @Test
    void status_withAnOverride_tracksTheCountdown() {
        WeatherEngine engine = new WeatherEngine();
        engine.setTimedWeather(WeatherType.RAIN, 1200, NO_OP);
        assertEquals("[WSM] Timed weather: RAIN — 1m remaining (1200 ticks)",
            WeatherMessages.timedWeatherStatus(engine));

        engine.tickTimedOverride(NO_OP);
        assertEquals("[WSM] Timed weather: RAIN — 59s remaining (1199 ticks)",
            WeatherMessages.timedWeatherStatus(engine));
    }

    @Test
    void status_afterExpiry_reportsNoOverride() {
        WeatherEngine engine = new WeatherEngine();
        engine.setTimedWeather(WeatherType.THUNDER, 1, NO_OP);
        engine.tickTimedOverride(NO_OP);
        assertEquals(WeatherMessages.NO_TIMED_WEATHER, WeatherMessages.timedWeatherStatus(engine));
    }

    @Test
    void nextChange_formatsPositiveCountdowns() {
        assertEquals("12m 30s (15000 ticks)", WeatherMessages.nextChange(15_000));
        assertEquals("0s (1 ticks)", WeatherMessages.nextChange(1));
        assertEquals("1h (72000 ticks)", WeatherMessages.nextChange(WeatherEngine.MAX_TICKS));
    }

    @Test
    void nextChange_isImminentWhenNotPositive() {
        assertEquals("imminent", WeatherMessages.nextChange(0));
        assertEquals("imminent", WeatherMessages.nextChange(-1)); // world not ticked yet
    }

    @Test
    void forecast_duringAnOverride_doesNotConsultTheBiome() {
        WeatherEngine engine = new WeatherEngine();
        engine.setTimedWeather(WeatherType.CLEAR, 2400, NO_OP);
        AtomicInteger lookups = new AtomicInteger();

        String message = WeatherMessages.forecast(engine, "minecraft:overworld", () -> {
            lookups.incrementAndGet();
            return BiomeCategory.DRY;
        });

        assertEquals("[WSM] Timed weather active: CLEAR"
            + "\n  Remaining: 2m (2400 ticks)"
            + "\n  Normal cycling resumes after timer expires.", message);
        assertEquals(0, lookups.get());
    }

    @Test
    void forecast_duringNormalCycling_reportsCountdownAndBiome() {
        WeatherEngine engine = new WeatherEngine(new Random(7));
        engine.tickWorld("minecraft:overworld", BiomeCategory.WET, NO_OP); // arms the timer
        int ticksLeft = engine.getTicksUntilNextChange("minecraft:overworld");

        assertEquals("[WSM] Next weather change in ~" + WeatherEngine.formatTicks(ticksLeft)
                + " (" + ticksLeft + " ticks)"
                + "\n  Spawn biome influence: WET",
            WeatherMessages.forecast(engine, "minecraft:overworld", () -> BiomeCategory.WET));
    }

    @Test
    void forecast_forAnUntickedWorld_isImminent() {
        assertEquals("[WSM] Next weather change in ~imminent\n  Spawn biome influence: COLD",
            WeatherMessages.forecast(new WeatherEngine(), "minecraft:overworld", () -> BiomeCategory.COLD));
    }

    @Test
    void forecast_withAnUnresolvableBiome_fallsBackToTemperate() {
        assertEquals("[WSM] Next weather change in ~imminent\n  Spawn biome influence: TEMPERATE",
            WeatherMessages.forecast(new WeatherEngine(), "minecraft:overworld", () -> null));
    }

    @Test
    void invalidWeatherType_wording() {
        assertEquals("Invalid weather type! Use: clear, rain, or thunder.",
            WeatherMessages.INVALID_WEATHER_TYPE);
    }
}
