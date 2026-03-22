package com.example.weathermod.common;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Platform-independent weather cycling engine.
 * <p>
 * Each platform (Fabric, Forge, NeoForge, Paper) delegates to a single shared
 * instance of this class, supplying platform-specific callbacks to actually
 * apply weather changes.
 * <p>
 * <b>Thread-safety note (#13):</b> Both {@link #tick} and {@link #setTimedWeather}
 * are called exclusively from the server's main thread on every supported platform:
 * <ul>
 *   <li>Fabric/Forge/NeoForge — commands and tick events run on the server thread.</li>
 *   <li>Paper — {@code BukkitRunnable} and {@code onCommand} run on the main thread.</li>
 * </ul>
 * No synchronization is required.
 */
public class WeatherEngine {

    // 30–60 minutes in ticks (20 ticks/sec × 60 sec/min)
    public static final int MIN_TICKS      = 30 * 60 * 20;   // 36,000
    public static final int MAX_TICKS      = 60 * 60 * 20;   // 72,000
    public static final int INTERVAL_RANGE = MAX_TICKS - MIN_TICKS + 1;
    public static final int WEATHER_DURATION = 999_999;

    /**
     * Per-world countdown timers. Uses {@code int[1]} instead of boxed
     * {@code Integer} to avoid auto-boxing on every tick (#9).
     */
    private final Map<String, int[]> weatherTimers = new HashMap<>();

    private int         timedWeatherTicks = 0;
    private WeatherType timedWeatherType  = null;

    /** Callback that platforms implement to actually change the world's weather. */
    @FunctionalInterface
    public interface WeatherApplier {
        void apply(WeatherType type, int durationTicks);
    }

    /**
     * Called once per world per server tick.
     *
     * @param worldKey      a stable identifier for the world (registry key, etc.)
     * @param biomeCategory the climate category of the world's spawn biome
     * @param applier       callback to apply weather on the platform
     * @return the {@link WeatherType} that was applied, or {@code null} if no change
     */
    public WeatherType tick(String worldKey, BiomeCategory biomeCategory, WeatherApplier applier) {
        // ── Timed weather countdown ──────────────────────────────────────
        if (timedWeatherTicks > 0) {
            timedWeatherTicks--;
            if (timedWeatherTicks <= 0) {
                applier.apply(WeatherType.CLEAR, WEATHER_DURATION);
                weatherTimers.put(worldKey, new int[]{randomInterval()});
                timedWeatherType = null;
                return WeatherType.CLEAR;
            }
            return null;
        }

        // ── Normal cycling ───────────────────────────────────────────────
        int[] timer = weatherTimers.get(worldKey);
        if (timer == null) {
            weatherTimers.put(worldKey, new int[]{randomInterval()});
            return null; // first tick — just initialise
        }

        timer[0]--;
        if (timer[0] <= 0) {
            WeatherType chosen = biomeCategory.weightedRandom();
            applier.apply(chosen, WEATHER_DURATION);
            timer[0] = randomInterval();
            return chosen;
        }
        return null;
    }

    /** Activates a timed weather override, pausing normal cycling. */
    public void setTimedWeather(WeatherType type, int ticks, WeatherApplier applier) {
        applier.apply(type, ticks);
        timedWeatherTicks = ticks;
        timedWeatherType  = type;
    }

    public boolean isTimedWeatherActive() {
        return timedWeatherTicks > 0;
    }

    public int getTimedWeatherTicksRemaining() {
        return timedWeatherTicks;
    }

    public WeatherType getTimedWeatherType() {
        return timedWeatherType;
    }

    /**
     * Returns the number of ticks until the next weather change for the given world,
     * or {@code -1} if the world has not been initialised yet.
     */
    public int getTicksUntilNextChange(String worldKey) {
        int[] timer = weatherTimers.get(worldKey);
        return timer != null ? timer[0] : -1;
    }

    /** Generates a random interval between {@link #MIN_TICKS} and {@link #MAX_TICKS}. */
    public static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }

    /** Formats a tick count as a human-readable duration string (e.g. "12m 30s"). */
    public static String formatTicks(int ticks) {
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0 && seconds > 0) return minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }
}
