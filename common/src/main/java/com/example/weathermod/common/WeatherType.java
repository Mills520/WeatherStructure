package com.example.weathermod.common;

/**
 * Weather states supported by the mod.
 */
public enum WeatherType {
    CLEAR, RAIN, THUNDER;

    private static final WeatherType[] VALUES = values();

    /** Returns a cached copy of the values array (no allocation per call). */
    public static WeatherType[] cachedValues() {
        return VALUES;
    }

    /** Case-insensitive lookup; returns {@code null} if no match. */
    public static WeatherType fromName(String name) {
        for (WeatherType t : VALUES) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;
    }
}
