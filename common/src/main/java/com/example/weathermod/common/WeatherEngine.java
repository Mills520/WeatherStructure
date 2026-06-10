package com.example.weathermod.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Platform-independent weather-cycling engine.
 * <p>
 * Every platform (Fabric, NeoForge, Paper) owns one instance and drives it the
 * same way: call {@link #tick} once per world per server tick, supplying a
 * {@link WeatherApplier} that knows how to change that platform's weather. The
 * engine decides <em>when</em> and <em>to what</em>; the platform decides
 * <em>how</em>.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li><b>Normal cycling</b> — each world counts down an independent timer
 *       ({@link #MIN_TICKS}..{@link #MAX_TICKS}); when it hits zero a new
 *       weather is rolled from the world's {@link BiomeCategory} and the timer
 *       re-arms.</li>
 *   <li><b>Timed override</b> — {@link #setTimedWeather} forces a weather for a
 *       fixed number of ticks. While active it <em>pauses</em> normal cycling
 *       (globally, across all worlds); on expiry it reverts to
 *       {@link WeatherType#CLEAR} and resumes cycling.</li>
 * </ul>
 *
 * <h2>Threading (#13)</h2>
 * This class is <b>not</b> synchronized. {@link #tick} and
 * {@link #setTimedWeather} are only ever called from the server main thread on
 * every supported platform (Fabric/NeoForge tick + command callbacks, Paper's
 * main-thread {@code BukkitRunnable} + {@code onCommand}), so no locking is
 * needed. Do not call into a shared instance from another thread.
 */
public class WeatherEngine {

    // 30–60 minutes expressed in ticks (20 ticks/sec).
    /** Shortest gap between automatic weather changes — 30 minutes. */
    public static final int MIN_TICKS       = 30 * 60 * 20;   // 36,000
    /** Longest gap between automatic weather changes — 60 minutes. */
    public static final int MAX_TICKS       = 60 * 60 * 20;   // 72,000
    /** Size of the (inclusive) {@code [MIN_TICKS, MAX_TICKS]} interval range. */
    public static final int INTERVAL_RANGE  = MAX_TICKS - MIN_TICKS + 1;
    /**
     * Duration handed to the platform when applying a cycled weather. It is set
     * deliberately huge (~13.8 h) so vanilla never reverts the chosen weather
     * before the next cycle does.
     */
    public static final int WEATHER_DURATION = 999_999;

    /**
     * Per-world countdown timers, keyed by a stable world identifier. Each value
     * is a one-element {@code int[]} rather than a boxed {@code Integer} so the
     * per-tick decrement mutates in place with no allocation or auto-boxing (#9).
     */
    private final Map<String, int[]> weatherTimers = new HashMap<>();

    /** Random source for interval rolls and weather selection. */
    private final RandomGenerator rng;

    // ── Timed-override state (global across all worlds) ──────────────────
    private int         timedWeatherTicks      = 0;
    private WeatherType timedWeatherType       = null;
    private boolean     lastTickWasTimedExpiry = false;

    /** Callback a platform implements to actually change a world's weather. */
    @FunctionalInterface
    public interface WeatherApplier {
        /**
         * @param type          the weather to apply
         * @param durationTicks how long it should last, in ticks
         */
        void apply(WeatherType type, int durationTicks);
    }

    /** Creates an engine backed by the shared {@link ThreadLocalRandom}. */
    public WeatherEngine() {
        this.rng = null; // resolved per-call via rng()
    }

    /**
     * Creates an engine backed by a specific random source. Intended for
     * deterministic tests; production code should use {@link #WeatherEngine()}.
     *
     * @param rng the random source (must not be {@code null})
     */
    public WeatherEngine(RandomGenerator rng) {
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    /** The active random source — the injected one, or the thread-local default. */
    private RandomGenerator rng() {
        return rng != null ? rng : ThreadLocalRandom.current();
    }

    /**
     * Advances the engine by one tick for one world.
     *
     * @param worldKey      stable identifier for the world (registry key, etc.)
     * @param biomeCategory climate category of the world's spawn biome
     * @param applier       callback used to apply any resulting weather change
     * @return the {@link WeatherType} applied this tick, or {@code null} if
     *         nothing changed
     */
    public WeatherType tick(String worldKey, BiomeCategory biomeCategory, WeatherApplier applier) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(biomeCategory, "biomeCategory");
        Objects.requireNonNull(applier, "applier");

        lastTickWasTimedExpiry = false;

        if (timedWeatherTicks > 0) {
            return tickTimedOverride(worldKey, applier);
        }
        return tickNormalCycle(worldKey, biomeCategory, applier);
    }

    /**
     * Counts down an active timed override. Returns {@link WeatherType#CLEAR}
     * (and flags an expiry) on the tick it runs out, otherwise {@code null}.
     */
    private WeatherType tickTimedOverride(String worldKey, WeatherApplier applier) {
        if (--timedWeatherTicks > 0) {
            return null;
        }
        // Override just expired — revert to clear and resume normal cycling.
        applier.apply(WeatherType.CLEAR, WEATHER_DURATION);
        armTimer(worldKey);
        timedWeatherType       = null;
        lastTickWasTimedExpiry = true;
        return WeatherType.CLEAR;
    }

    /**
     * Drives normal per-world cycling: initialises the timer on first sight of a
     * world, then rolls new weather whenever the countdown reaches zero.
     */
    private WeatherType tickNormalCycle(String worldKey, BiomeCategory biomeCategory, WeatherApplier applier) {
        int[] timer = weatherTimers.get(worldKey);
        if (timer == null) {
            weatherTimers.put(worldKey, new int[]{nextInterval()});
            return null; // first tick for this world — just initialise
        }
        if (--timer[0] > 0) {
            return null;
        }
        WeatherType chosen = biomeCategory.weightedRandom(rng());
        applier.apply(chosen, WEATHER_DURATION);
        timer[0] = nextInterval();
        return chosen;
    }

    /** Re-arms (or creates) the given world's countdown with a fresh interval. */
    private void armTimer(String worldKey) {
        int[] timer = weatherTimers.get(worldKey);
        if (timer != null) {
            timer[0] = nextInterval();   // reuse the array — no allocation (#9)
        } else {
            weatherTimers.put(worldKey, new int[]{nextInterval()});
        }
    }

    /**
     * Whether the most recent {@link #tick} returned because a timed override
     * just expired (as opposed to a normal cycle change). Platforms use this to
     * log the right message.
     */
    public boolean wasLastTickTimedExpiry() {
        return lastTickWasTimedExpiry;
    }

    /**
     * Activates a timed weather override, applying it immediately and pausing
     * normal cycling until it expires.
     *
     * @param type    the weather to force (must not be {@code null})
     * @param ticks   how long to hold it, in ticks; values below 1 are clamped
     *                to 1 so the override always expires on a later tick
     * @param applier callback used to apply the weather (must not be {@code null})
     */
    public void setTimedWeather(WeatherType type, int ticks, WeatherApplier applier) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(applier, "applier");
        int held = Math.max(1, ticks);
        applier.apply(type, held);
        timedWeatherTicks = held;
        timedWeatherType  = type;
    }

    /** Whether a timed override is currently counting down. */
    public boolean isTimedWeatherActive() {
        return timedWeatherTicks > 0;
    }

    /** Ticks left on the active timed override, or {@code 0} if none is active. */
    public int getTimedWeatherTicksRemaining() {
        return timedWeatherTicks;
    }

    /** The active timed override's weather, or {@code null} if none is active. */
    public WeatherType getTimedWeatherType() {
        return timedWeatherType;
    }

    /**
     * Ticks until the given world's next automatic weather change.
     *
     * @return the remaining ticks, or {@code -1} if the world has not been
     *         ticked yet. While a timed override is active this reflects the
     *         <em>paused</em> cycling timer, which resumes after the override.
     */
    public int getTicksUntilNextChange(String worldKey) {
        int[] timer = weatherTimers.get(worldKey);
        return timer != null ? timer[0] : -1;
    }

    /**
     * Drops a world's cycling state so its timer is re-initialised on the next
     * {@link #tick}. Useful when a world is unloaded. No-op if untracked.
     */
    public void forget(String worldKey) {
        weatherTimers.remove(worldKey);
    }

    /** Clears all per-world timers and any active timed override. */
    public void reset() {
        weatherTimers.clear();
        timedWeatherTicks      = 0;
        timedWeatherType       = null;
        lastTickWasTimedExpiry = false;
    }

    /** A fresh random interval in {@code [MIN_TICKS, MAX_TICKS]} from this engine's RNG. */
    private int nextInterval() {
        return MIN_TICKS + rng().nextInt(INTERVAL_RANGE);
    }

    /**
     * A random interval in {@code [MIN_TICKS, MAX_TICKS]} using the shared
     * {@link ThreadLocalRandom}. Retained as a static utility; instances roll
     * intervals from their own (optionally seeded) RNG.
     */
    public static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }

    /**
     * Formats a tick count as a compact duration string, e.g. {@code "12m 30s"},
     * {@code "5m"}, {@code "45s"}. Negative inputs are treated as zero
     * ({@code "0s"}).
     */
    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0 && seconds > 0) return minutes + "m " + seconds + "s";
        if (minutes > 0)                return minutes + "m";
        return seconds + "s";
    }
}
