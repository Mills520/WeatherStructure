package com.example.weathermod.common;

/**
 * Weather states supported by the mod.
 */
public enum WeatherType {
    CLEAR, RAIN, THUNDER;

    private static final WeatherType[] VALUES = values();
    private static final WeatherType[] LOOKUP_VALUES = values();

    /** Returns a cached copy of the values array (no allocation per call). */
    public static WeatherType[] cachedValues() {
        return VALUES;
    }

    /** Case-insensitive lookup; returns {@code null} if no match. */
    public static WeatherType fromName(String name) {
        if (name == null) return null;

        String normalized = name.trim();
        if (normalized.isEmpty()) return null;

        for (WeatherType t : LOOKUP_VALUES) {
            if (t.name().equalsIgnoreCase(normalized)) return t;
        }
        return null;
    }
}
