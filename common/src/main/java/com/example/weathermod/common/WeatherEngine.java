package com.example.weathermod.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Platform-independent weather-cycling engine.
 * <p>
 * Every platform (Fabric, NeoForge, Paper) owns one instance and drives it the
 * same way. The engine decides <em>when</em> and <em>to what</em>; the platform
 * decides <em>how</em>, via a {@link WeatherApplier}.
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
 * <h2>Driving the engine — two phases per server tick</h2>
 * The timed override is <em>global</em> but cycling timers are <em>per world</em>,
 * so the two are advanced by separate calls:
 * <pre>{@code
 * // once per server tick, before any world is ticked:
 * if (engine.tickTimedOverride(applyClearEverywhere)) {
 *     log("timed weather expired");
 * }
 * // then once per world:
 * for (World w : worlds) {
 *     WeatherType changed = engine.tickWorld(w.key(), w.biomeSource(), w.applier());
 * }
 * }</pre>
 * Calling {@link #tickTimedOverride} exactly once per server tick is what keeps
 * a {@code /timedweather 60} command lasting 60 seconds no matter how many
 * worlds are loaded. The older single-call {@link #tick} entry point folded both
 * phases together and therefore counted the override down once <em>per world</em>
 * — it is kept only for source compatibility and is deprecated.
 *
 * <h2>Threading</h2>
 * This class is <b>not</b> synchronized, and deliberately so: every supported
 * platform drives it from the server main thread only (Fabric/NeoForge tick +
 * command callbacks, Paper's main-thread {@code BukkitRunnable} +
 * {@code onCommand}), and taking a lock around a callback that reaches back into
 * the game would invite lock-order problems. Callers that cannot guarantee the
 * main thread must hop onto it first — the Paper plugin does exactly that before
 * calling {@link #setTimedWeather}.
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
     * Longest timed override the engine will hold — 24 hours, matching the
     * documented {@code /timedweather} limit. Longer requests are clamped so a
     * caller that forgets to validate (or overflows its own arithmetic) cannot
     * wedge cycling for weeks.
     */
    public static final int MAX_TIMED_TICKS = 24 * 60 * 60 * 20;   // 1,728,000

    /**
     * Per-world countdown timers, keyed by a stable world identifier. Each value
     * is a one-element {@code int[]} rather than a boxed {@code Integer} so the
     * per-tick decrement mutates in place with no allocation or auto-boxing.
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

    /**
     * Supplies a world's climate category on demand.
     * <p>
     * {@link #tickWorld(String, BiomeCategorySource, WeatherApplier)} only asks
     * for the category on the tick a new weather is actually rolled — roughly
     * once every 30–60 minutes rather than 20 times a second. That lets
     * platforms resolve the spawn biome lazily instead of caching a value that
     * goes stale the moment an operator runs {@code /setworldspawn}.
     */
    @FunctionalInterface
    public interface BiomeCategorySource {
        /** @return the world's current climate category, never {@code null} */
        BiomeCategory get();
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

    // ── Phase 1: the global timed override ───────────────────────────────

    /**
     * Advances an active timed override by exactly one tick. Call this
     * <b>once per server tick</b>, before ticking individual worlds, and call it
     * unconditionally — when no override is active it is a single {@code int}
     * comparison.
     * <p>
     * On the tick the override runs out this applies {@link WeatherType#CLEAR}
     * through {@code applier} and re-arms the cycling timer of <em>every</em>
     * world the engine knows about, so cycling resumes everywhere rather than
     * only in whichever world happened to drive the countdown.
     *
     * @param applier callback used to apply the reverting CLEAR; it should cover
     *                all worlds the mod manages (must not be {@code null})
     * @return {@code true} if the override expired on this tick, else {@code false}
     */
    public boolean tickTimedOverride(WeatherApplier applier) {
        Objects.requireNonNull(applier, "applier");

        lastTickWasTimedExpiry = false;
        if (timedWeatherTicks <= 0) {
            return false;
        }
        if (--timedWeatherTicks > 0) {
            return false;
        }
        // Override just expired — revert to clear and resume normal cycling.
        timedWeatherType = null;
        applier.apply(WeatherType.CLEAR, WEATHER_DURATION);
        armAllTimers();
        lastTickWasTimedExpiry = true;
        return true;
    }

    // ── Phase 2: per-world cycling ───────────────────────────────────────

    /**
     * Advances one world's cycling timer by a tick, rolling new weather when the
     * countdown reaches zero. Does nothing while a timed override is active, so
     * cycling timers stay frozen for the whole override rather than drifting.
     *
     * @param worldKey     stable identifier for the world (registry key, etc.)
     * @param biomeSource  supplies the world's climate category; only consulted
     *                     on the tick a new weather is rolled
     * @param applier      callback used to apply any resulting weather change
     * @return the {@link WeatherType} applied this tick, or {@code null} if
     *         nothing changed
     */
    public WeatherType tickWorld(String worldKey, BiomeCategorySource biomeSource, WeatherApplier applier) {
        Objects.requireNonNull(biomeSource, "biomeSource");
        return tickWorld(worldKey, biomeSource, null, applier);
    }

    /**
     * Convenience overload for callers that already hold a category (and for
     * tests). Prefer {@link #tickWorld(String, BiomeCategorySource, WeatherApplier)}
     * on the hot path: it avoids resolving a biome on the 99.999% of ticks that
     * do not change the weather.
     */
    public WeatherType tickWorld(String worldKey, BiomeCategory biomeCategory, WeatherApplier applier) {
        Objects.requireNonNull(biomeCategory, "biomeCategory");
        return tickWorld(worldKey, null, biomeCategory, applier);
    }

    /**
     * Shared body of both {@code tickWorld} overloads. Exactly one of
     * {@code biomeSource} / {@code fixedCategory} is non-null; taking both keeps
     * the eager overload from allocating a wrapper lambda on every tick.
     */
    private WeatherType tickWorld(String worldKey, BiomeCategorySource biomeSource,
                                  BiomeCategory fixedCategory, WeatherApplier applier) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(applier, "applier");

        if (timedWeatherTicks > 0) {
            return null; // paused by the global override
        }

        int[] timer = weatherTimers.get(worldKey);
        if (timer == null) {
            weatherTimers.put(worldKey, new int[]{nextInterval()});
            return null; // first tick for this world — just initialise
        }
        if (--timer[0] > 0) {
            return null;
        }

        // Re-arm first. Everything below reaches into platform code that can
        // throw; doing it in this order means a failure costs one missed weather
        // change instead of leaving the timer at zero, where it would retry (and
        // decrement past zero) on every subsequent tick.
        timer[0] = nextInterval();

        BiomeCategory category = fixedCategory != null ? fixedCategory : biomeSource.get();
        if (category == null) {
            category = BiomeCategory.TEMPERATE; // a source that can't resolve must not stall cycling
        }
        WeatherType chosen = category.weightedRandom(rng());
        applier.apply(chosen, WEATHER_DURATION);
        return chosen;
    }

    /**
     * Combined single-call tick — <b>deprecated</b>.
     * <p>
     * This advances the global timed override <em>and</em> the given world's
     * cycling timer in one call, which means a server ticking <i>n</i> worlds
     * counts the override down <i>n</i> times per server tick. Use
     * {@link #tickTimedOverride} once per server tick plus {@link #tickWorld}
     * per world instead.
     *
     * @deprecated replaced by the two-phase {@link #tickTimedOverride} /
     *             {@link #tickWorld} API; retained for source compatibility.
     */
    @Deprecated(since = "1.8.0")
    public WeatherType tick(String worldKey, BiomeCategory biomeCategory, WeatherApplier applier) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(biomeCategory, "biomeCategory");
        Objects.requireNonNull(applier, "applier");

        if (timedWeatherTicks > 0) {
            return tickTimedOverride(applier) ? WeatherType.CLEAR : null;
        }
        lastTickWasTimedExpiry = false;
        return tickWorld(worldKey, biomeCategory, applier);
    }

    /** Re-arms every tracked world's countdown with a fresh interval. */
    private void armAllTimers() {
        for (int[] timer : weatherTimers.values()) {
            timer[0] = nextInterval();   // reuse the array — no allocation
        }
    }

    /**
     * Whether the most recent {@link #tickTimedOverride} call ended a timed
     * override. Prefer that method's return value; this exists for platforms
     * that inspect engine state after the fact.
     */
    public boolean wasLastTickTimedExpiry() {
        return lastTickWasTimedExpiry;
    }

    // ── Timed override control ───────────────────────────────────────────

    /**
     * Activates a timed weather override, applying it immediately and pausing
     * normal cycling until it expires.
     *
     * @param type    the weather to force (must not be {@code null})
     * @param ticks   how long to hold it, in ticks; clamped to
     *                {@code [1, }{@link #MAX_TIMED_TICKS}{@code ]} so the
     *                override always expires on some later tick
     * @param applier callback used to apply the weather (must not be {@code null})
     * @return the clamped duration actually armed, in ticks
     */
    public int setTimedWeather(WeatherType type, int ticks, WeatherApplier applier) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(applier, "applier");

        int held = Math.min(MAX_TIMED_TICKS, Math.max(1, ticks));
        // Arm before applying: if the platform callback throws, the engine is
        // still in a consistent state that will revert to CLEAR on expiry
        // rather than leaving cycling paused forever.
        timedWeatherTicks = held;
        timedWeatherType  = type;
        applier.apply(type, held);
        return held;
    }

    /** Cancels any active timed override without applying weather, resuming cycling. */
    public void clearTimedWeather() {
        timedWeatherTicks      = 0;
        timedWeatherType       = null;
        lastTickWasTimedExpiry = false;
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

    // ── World lifecycle / introspection ──────────────────────────────────

    /**
     * Ticks until the given world's next automatic weather change.
     *
     * @return the remaining ticks, or {@code -1} if the world has not been
     *         ticked yet. While a timed override is active this reflects the
     *         <em>paused</em> cycling timer, which resumes after the override.
     */
    public int getTicksUntilNextChange(String worldKey) {
        int[] timer = worldKey == null ? null : weatherTimers.get(worldKey);
        return timer != null ? timer[0] : -1;
    }

    /**
     * Drops a world's cycling state so its timer is re-initialised on the next
     * {@link #tickWorld}. Call this when a world unloads — otherwise the engine
     * keeps a timer for every world the server has ever loaded. No-op if
     * untracked or {@code null}.
     */
    public void forget(String worldKey) {
        if (worldKey != null) {
            weatherTimers.remove(worldKey);
        }
    }

    /** How many worlds currently have cycling state. */
    public int trackedWorldCount() {
        return weatherTimers.size();
    }

    /** An unmodifiable view of the tracked world keys. */
    public Set<String> trackedWorldKeys() {
        return Collections.unmodifiableSet(weatherTimers.keySet());
    }

    /** Clears all per-world timers and any active timed override. */
    public void reset() {
        weatherTimers.clear();
        clearTimedWeather();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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
     * Formats a tick count as a compact duration string, e.g. {@code "2h 5m"},
     * {@code "12m 30s"}, {@code "45s"}. Only the two most significant units are
     * shown. Negative inputs are treated as zero ({@code "0s"}).
     */
    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        int hours   = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
        }
        return seconds + "s";
    }
}
