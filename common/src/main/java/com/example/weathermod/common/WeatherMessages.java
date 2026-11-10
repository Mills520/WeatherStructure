package com.example.weathermod.common;

/**
 * The text of every command reply the platforms share.
 * <p>
 * Fabric and NeoForge built these strings in two identical copies and Paper in a
 * third; they are assembled here once so the wording cannot drift between
 * loaders and can be asserted on in tests. Methods that take a
 * {@link WeatherEngine} only read it — they must be called on the same thread
 * that drives the engine.
 */
public final class WeatherMessages {

    /** Reply to an unknown weather type on Fabric / NeoForge. */
    public static final String INVALID_WEATHER_TYPE =
        "Invalid weather type! Use: clear, rain, or thunder.";

    /** {@code /timedweather status} when no override is running. */
    public static final String NO_TIMED_WEATHER =
        "[WSM] No timed weather active. Normal cycling is running.";

    private WeatherMessages() {}

    /** Confirmation for a successful {@code /timedweather <type> <seconds>}. */
    public static String timedWeatherSet(WeatherType type, int heldSeconds) {
        return "[WSM] Weather set to " + type.getCommandName() + " for " + heldSeconds
            + "s. Will revert to CLEAR after.";
    }

    /** Reply for {@code /timedweather status}. */
    public static String timedWeatherStatus(WeatherEngine engine) {
        if (!engine.isTimedWeatherActive()) {
            return NO_TIMED_WEATHER;
        }
        int remaining = engine.getTimedWeatherTicksRemaining();
        return "[WSM] Timed weather: " + engine.getTimedWeatherType()
            + " — " + WeatherEngine.formatTicks(remaining)
            + " remaining (" + remaining + " ticks)";
    }

    /**
     * The {@code /weatherforecast} reply while a timed override is running.
     * Only meaningful when {@link WeatherEngine#isTimedWeatherActive()}.
     */
    public static String timedOverrideForecast(WeatherEngine engine) {
        int remaining = engine.getTimedWeatherTicksRemaining();
        return "[WSM] Timed weather active: " + engine.getTimedWeatherType()
            + "\n  Remaining: " + WeatherEngine.formatTicks(remaining)
            + " (" + remaining + " ticks)"
            + "\n  Normal cycling resumes after timer expires.";
    }

    /**
     * Describes a cycling countdown as returned by
     * {@link WeatherEngine#getTicksUntilNextChange}: {@code "12m 30s (15000 ticks)"},
     * or {@code "imminent"} when it is not positive.
     */
    public static String nextChange(int ticksLeft) {
        return ticksLeft > 0
            ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
            : "imminent";
    }

    /**
     * The complete {@code /weatherforecast} reply for a platform that manages a
     * single world (Fabric, NeoForge).
     *
     * @param worldKey    the engine key of the managed world
     * @param biomeSource supplies the world's climate category; consulted only
     *                    when no timed override is running
     */
    public static String forecast(WeatherEngine engine, String worldKey,
                                  WeatherEngine.BiomeCategorySource biomeSource) {
        if (engine.isTimedWeatherActive()) {
            return timedOverrideForecast(engine);
        }
        int ticksLeft = engine.getTicksUntilNextChange(worldKey);
        BiomeCategory category = biomeSource.get();
        if (category == null) {
            category = BiomeCategory.TEMPERATE; // same fallback the engine uses
        }
        return "[WSM] Next weather change in ~" + nextChange(ticksLeft)
            + "\n  Spawn biome influence: " + category.name();
    }
}
