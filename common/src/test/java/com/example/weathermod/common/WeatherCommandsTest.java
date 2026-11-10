package com.example.weathermod.common;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the loader-independent command helpers. */
class WeatherCommandsTest {

    // ── limits and units ─────────────────────────────────────────────────

    @Test
    void maxTimedSeconds_is24Hours_andMatchesTheEngineClamp() {
        assertEquals(86_400, WeatherCommands.MAX_TIMED_SECONDS);
        assertEquals(WeatherEngine.MAX_TIMED_TICKS,
            WeatherCommands.secondsToTicks(WeatherCommands.MAX_TIMED_SECONDS));
    }

    @Test
    void isValidSeconds_acceptsOnlyOneToMax() {
        assertFalse(WeatherCommands.isValidSeconds(Integer.MIN_VALUE));
        assertFalse(WeatherCommands.isValidSeconds(-1));
        assertFalse(WeatherCommands.isValidSeconds(0));
        assertTrue(WeatherCommands.isValidSeconds(1));
        assertTrue(WeatherCommands.isValidSeconds(WeatherCommands.MAX_TIMED_SECONDS));
        assertFalse(WeatherCommands.isValidSeconds(WeatherCommands.MAX_TIMED_SECONDS + 1));
        assertFalse(WeatherCommands.isValidSeconds(Integer.MAX_VALUE));
    }

    @Test
    void secondsToTicks_multipliesBy20() {
        assertEquals(20, WeatherCommands.secondsToTicks(1));
        assertEquals(1_200, WeatherCommands.secondsToTicks(60));
        assertEquals(0, WeatherCommands.secondsToTicks(0));
        assertEquals(-20, WeatherCommands.secondsToTicks(-1));
    }

    @Test
    void secondsToTicks_saturatesInsteadOfWrapping() {
        // 107,374,183 * 20 overflows int; a wrapped result would be negative and
        // the engine would clamp it to a 1-tick override.
        assertEquals(Integer.MAX_VALUE, WeatherCommands.secondsToTicks(107_374_183));
        assertEquals(Integer.MAX_VALUE, WeatherCommands.secondsToTicks(Integer.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, WeatherCommands.secondsToTicks(Integer.MIN_VALUE));
    }

    // ── raw argument parsing (Paper) ─────────────────────────────────────

    @Test
    void parseSeconds_readsPlainIntegers() {
        assertEquals(OptionalInt.of(60), WeatherCommands.parseSeconds("60"));
        assertEquals(OptionalInt.of(0), WeatherCommands.parseSeconds("0"));
        assertEquals(OptionalInt.of(-5), WeatherCommands.parseSeconds("-5"));
    }

    @Test
    void parseSeconds_neverThrowsOnBadInput() {
        String[] bad = {
            null, "", " ", "abc", "60s", "1.5", "1e3", " 60", "60 ",
            "99999999999",              // past int range
            "--1", "0x10", "§c60", "NaN",
        };
        for (String raw : bad) {
            assertTrue(WeatherCommands.parseSeconds(raw).isEmpty(), "should not parse: '" + raw + "'");
        }
    }

    @Test
    void parseSeconds_outOfRangeValuesParseButFailTheRangeCheck() {
        OptionalInt huge = WeatherCommands.parseSeconds("2000000000");
        assertTrue(huge.isPresent());
        assertFalse(WeatherCommands.isValidSeconds(huge.getAsInt()));
    }

    // ── vanilla /weather command line (Fabric, NeoForge) ─────────────────

    @Test
    void vanillaWeatherCommand_usesTicksWithAnExplicitUnit() {
        // The regression this guards: the duration is a TimeArgument whose bare
        // unit is ticks, and an earlier build passed ticks / 20 with no suffix.
        assertEquals("weather rain 1200t", WeatherCommands.vanillaWeatherCommand(WeatherType.RAIN, 1200));
        assertEquals("weather clear 999999t",
            WeatherCommands.vanillaWeatherCommand(WeatherType.CLEAR, WeatherEngine.WEATHER_DURATION));
        assertEquals("weather thunder 1728000t",
            WeatherCommands.vanillaWeatherCommand(WeatherType.THUNDER, WeatherEngine.MAX_TIMED_TICKS));
    }

    @Test
    void vanillaWeatherCommand_coversEveryWeatherType() {
        for (WeatherType type : WeatherType.cachedValues()) {
            assertEquals("weather " + type.getCommandName() + " 20t",
                WeatherCommands.vanillaWeatherCommand(type, 20));
        }
    }

    @Test
    void vanillaWeatherCommand_floorsTheDurationAtOneTick() {
        assertEquals("weather rain 1t", WeatherCommands.vanillaWeatherCommand(WeatherType.RAIN, 0));
        assertEquals("weather rain 1t", WeatherCommands.vanillaWeatherCommand(WeatherType.RAIN, -40));
    }

    // ── echo sanitising ──────────────────────────────────────────────────

    @Test
    void sanitizeForEcho_passesOrdinaryTextThrough() {
        assertEquals("abc", WeatherCommands.sanitizeForEcho("abc"));
        assertEquals("12x", WeatherCommands.sanitizeForEcho("12x"));
        assertEquals("", WeatherCommands.sanitizeForEcho(""));
        assertEquals("", WeatherCommands.sanitizeForEcho(null));
    }

    @Test
    void sanitizeForEcho_stripsFormattingAndControlCharacters() {
        assertEquals("cred", WeatherCommands.sanitizeForEcho("§cred"));
        assertEquals("ab", WeatherCommands.sanitizeForEcho("a\nb"));
        assertEquals("ab", WeatherCommands.sanitizeForEcho("a\u0000\u001b\u007fb"));
        assertEquals("", WeatherCommands.sanitizeForEcho("§§§"));
    }

    @Test
    void sanitizeForEcho_truncatesLongTokens() {
        String exact = "x".repeat(WeatherCommands.MAX_ECHO_LENGTH);
        assertEquals(exact, WeatherCommands.sanitizeForEcho(exact));

        String cleaned = WeatherCommands.sanitizeForEcho(exact + "y");
        assertEquals(exact + "...", cleaned);

        String huge = WeatherCommands.sanitizeForEcho("z".repeat(100_000));
        assertEquals(WeatherCommands.MAX_ECHO_LENGTH + 3, huge.length());
    }
}
