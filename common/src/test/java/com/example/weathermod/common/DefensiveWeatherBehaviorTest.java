package com.example.weathermod.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefensiveWeatherBehaviorTest {

    private final WeatherEngine.WeatherApplier noopApplier = (type, duration) -> { };

    @Test
    void weatherType_fromName_handlesNullBlankAndWhitespace() {
        assertNull(WeatherType.fromName(null));
        assertNull(WeatherType.fromName(""));
        assertNull(WeatherType.fromName("   "));
        assertEquals(WeatherType.RAIN, WeatherType.fromName("  rain  "));
        assertEquals(WeatherType.THUNDER, WeatherType.fromName("\tThunder\n"));
    }

    @Test
    void biomeCategory_fromBiomeId_normalizesInput() {
        assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId(null));
        assertEquals(BiomeCategory.TEMPERATE, BiomeCategory.fromBiomeId(""));
        assertEquals(BiomeCategory.DRY, BiomeCategory.fromBiomeId("desert"));
        assertEquals(BiomeCategory.DRY, BiomeCategory.fromBiomeId(" MINECRAFT:SAVANNA "));
        assertEquals(BiomeCategory.WET, BiomeCategory.fromBiomeId("minecraft:MANGROVE_SWAMP"));
    }

    @Test
    void biomeCategory_selectWeather_usesExpectedThresholds() {
        assertEquals(WeatherType.CLEAR, BiomeCategory.DRY.selectWeather(0.00));
        assertEquals(WeatherType.CLEAR, BiomeCategory.DRY.selectWeather(0.59));
        assertEquals(WeatherType.RAIN, BiomeCategory.DRY.selectWeather(0.60));
        assertEquals(WeatherType.RAIN, BiomeCategory.DRY.selectWeather(0.84));
        assertEquals(WeatherType.THUNDER, BiomeCategory.DRY.selectWeather(0.85));
        assertEquals(WeatherType.THUNDER, BiomeCategory.DRY.selectWeather(0.99));
    }

    @Test
    void biomeCategory_selectWeather_rejectsInvalidRolls() {
        assertThrows(IllegalArgumentException.class, () -> BiomeCategory.TEMPERATE.selectWeather(-0.01));
        assertThrows(IllegalArgumentException.class, () -> BiomeCategory.TEMPERATE.selectWeather(1.00));
        assertThrows(IllegalArgumentException.class, () -> BiomeCategory.TEMPERATE.selectWeather(Double.NaN));
    }

    @Test
    void weatherEngine_rejectsInvalidTimedWeatherInputs() {
        WeatherEngine engine = new WeatherEngine();

        assertThrows(NullPointerException.class, () -> engine.setTimedWeather(null, 20, noopApplier));
        assertThrows(NullPointerException.class, () -> engine.setTimedWeather(WeatherType.CLEAR, 20, null));
        assertThrows(IllegalArgumentException.class, () -> engine.setTimedWeather(WeatherType.CLEAR, 0, noopApplier));
        assertThrows(IllegalArgumentException.class, () -> engine.setTimedWeather(WeatherType.CLEAR, -20, noopApplier));
    }

    @Test
    void weatherEngine_rejectsInvalidTickInputs() {
        WeatherEngine engine = new WeatherEngine();

        assertThrows(NullPointerException.class, () -> engine.tick(null, BiomeCategory.TEMPERATE, noopApplier));
        assertThrows(NullPointerException.class, () -> engine.tick("world", null, noopApplier));
        assertThrows(NullPointerException.class, () -> engine.tick("world", BiomeCategory.TEMPERATE, null));
    }

    @Test
    void weatherEngine_formatTicks_clampsNegativeValues() {
        assertEquals("0s", WeatherEngine.formatTicks(-20));
    }
}
