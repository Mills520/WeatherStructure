package com.example.weathermod.common;

/**
 * The three weather states the mod cycles between.
 * <p>
 * Ordering ({@code CLEAR, RAIN, THUNDER}) is part of the public contract:
 * platforms persist/compare ordinals and tests assert on it, so new states
 * must be appended, never inserted.
 */
public enum WeatherType {

    /** No precipitation. */
    CLEAR("clear"),
    /** Rain or snow (depending on biome temperature). */
    RAIN("rain"),
    /** Thunderstorm — rain plus lightning. */
    THUNDER("thunder");

    /**
     * Cached, immutable snapshot of {@link #values()}. {@code Enum.values()}
     * allocates a fresh array on every call; callers on the hot tick path
     * (and tight test loops) reuse this instead. Never mutate the returned
     * array — it is shared.
     */
    private static final WeatherType[] VALUES = values();

    /** Lower-case token used by the vanilla {@code /weather <name>} command. */
    private final String commandName;

    WeatherType(String commandName) {
        this.commandName = commandName;
    }

    /**
     * The lower-case command token for this weather (e.g. {@code "thunder"}),
     * suitable for building a {@code /weather <name> <seconds>} command or for
     * display.
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * Returns the shared, cached values array (no per-call allocation).
     * <p>
     * The same instance is returned every time; do not modify it.
     */
    public static WeatherType[] cachedValues() {
        return VALUES;
    }

    /**
     * Case-insensitive lookup by enum name (e.g. {@code "rain"}, {@code "RAIN"}).
     *
     * @param name the name to look up; may be {@code null}
     * @return the matching {@link WeatherType}, or {@code null} if {@code name}
     *         is {@code null} or matches no constant
     */
    public static WeatherType fromName(String name) {
        if (name == null) {
            return null;
        }
        for (WeatherType t : VALUES) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }
}
