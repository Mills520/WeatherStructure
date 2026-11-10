package com.example.weathermod.common;

import java.util.OptionalInt;

/**
 * Loader-independent pieces of the {@code /timedweather} command: its limits,
 * unit conversion, raw-argument handling, and the vanilla {@code /weather}
 * command line Fabric and NeoForge dispatch.
 * <p>
 * Everything here is a pure function of its arguments, so the parts of the
 * platform layer that used to be untestable copy-paste (and that once shipped a
 * 400× duration bug) are covered by the {@code common} suite.
 */
public final class WeatherCommands {

    /** Server ticks per second. */
    public static final int TICKS_PER_SECOND = 20;

    /** Longest {@code /timedweather} duration, in seconds — 24 hours. */
    public static final int MAX_TIMED_SECONDS = WeatherEngine.MAX_TIMED_TICKS / TICKS_PER_SECOND;

    /** Longest user-supplied token echoed back in a reply. */
    public static final int MAX_ECHO_LENGTH = 32;

    private WeatherCommands() {}

    /** Whether {@code seconds} is a duration {@code /timedweather} accepts. */
    public static boolean isValidSeconds(int seconds) {
        return seconds >= 1 && seconds <= MAX_TIMED_SECONDS;
    }

    /**
     * Converts a duration in seconds to ticks without overflowing: values past
     * {@code Integer.MAX_VALUE / 20} saturate rather than wrapping negative.
     * ({@link WeatherEngine#setTimedWeather} clamps the result again.)
     */
    public static int secondsToTicks(int seconds) {
        long ticks = (long) seconds * TICKS_PER_SECOND;
        if (ticks > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (ticks < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) ticks;
    }

    /**
     * Parses a raw seconds argument, for platforms without a typed argument
     * parser (Bukkit hands commands a {@code String[]}).
     *
     * @param raw the argument text; may be {@code null}
     * @return the parsed value, or empty if {@code raw} is not an {@code int} —
     *         never throws. Range is the caller's check ({@link #isValidSeconds}).
     */
    public static OptionalInt parseSeconds(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * The vanilla command line that sets {@code type} for {@code durationTicks}.
     * <p>
     * The duration is a vanilla {@code TimeArgument}, whose <em>default unit is
     * ticks</em> — {@code /weather rain 60} means 3 seconds. The tick count is
     * therefore emitted with an explicit {@code t} suffix so the unit cannot be
     * misread, and is floored at 1 so a zero or negative duration still
     * produces a command vanilla will parse.
     *
     * @return e.g. {@code "weather rain 1200t"} (no leading slash)
     */
    public static String vanillaWeatherCommand(WeatherType type, int durationTicks) {
        return "weather " + type.getCommandName() + " " + Math.max(1, durationTicks) + "t";
    }

    /**
     * Makes a user-supplied token safe to echo into a chat reply: drops the
     * legacy section sign (which the client renders as a colour/format code) and
     * any control characters, then truncates. Without this, a crafted argument
     * could recolour or pad a reply.
     *
     * @param raw the token; may be {@code null}
     * @return the cleaned token, never {@code null}
     */
    public static String sanitizeForEcho(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int limit = Math.min(raw.length(), MAX_ECHO_LENGTH);
        StringBuilder out = new StringBuilder(limit + 3);
        for (int i = 0; i < limit; i++) {
            char c = raw.charAt(i);
            // U+00A7 is the legacy formatting prefix; C0 controls and DEL can
            // break message framing in the client.
            if (c == '§' || c < ' ' || c == '\u007f') continue;
            out.append(c);
        }
        if (raw.length() > limit) out.append("...");
        return out.toString();
    }
}
