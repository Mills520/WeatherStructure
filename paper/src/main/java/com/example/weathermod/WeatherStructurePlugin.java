package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paper / Spigot / Bukkit plugin — Weather &amp; Structure Mod (MC 26.x)
 *
 * <p>Feature 1 — Dynamic Weather Cycling: biome-aware random weather every
 * 30–60 min, weighted by each world's spawn biome.
 *
 * <p>Feature 2 — Structure Spawn Boost: shrinks {@code RandomSpreadStructurePlacement}
 * spacing/separation once per JVM via targeted registry access, with a bounded
 * reflective sweep as a fallback.
 *
 * <p>Feature 3 — {@code /timedweather}: forces a weather for a duration, then
 * reverts to clear.
 *
 * <p>Feature 4 — {@code /weatherforecast}: next change time and spawn-biome
 * influence.
 */
public class WeatherStructurePlugin extends JavaPlugin implements Listener {

    private static final float DENSITY_FACTOR  = 0.87f;
    private static final int   MIN_SPACING     = 2;
    private static final int   MIN_SEPARATION  = 1;

    /** 24 hours — matches {@link WeatherEngine#MAX_TIMED_TICKS}. */
    private static final int MAX_SECONDS = WeatherEngine.MAX_TIMED_TICKS / 20;

    /** Longest user-supplied token echoed back in a reply. */
    private static final int MAX_ECHO_LENGTH = 32;

    /**
     * JVM-wide marker that the structure boost already ran.
     * <p>
     * The {@code StructureSet} registry outlives a plugin reload — {@code /reload}
     * builds a fresh plugin instance and classloader but keeps the running
     * server's registries — so an instance-scoped flag would let each reload
     * shrink the same spacing values again (0.87, then 0.87², then 0.87³…). A
     * system property is the one piece of state that survives the reload and
     * dies with the JVM, which is exactly the lifetime of the registries we
     * mutate.
     */
    private static final String BOOST_MARKER_PROPERTY = "weatherstructuremod.structureBoostApplied";

    // Bounds for the reflective fallback sweep, so a pathological object graph
    // can't turn plugin enable into a multi-second stall.
    private static final int MAX_SWEEP_DEPTH = 6;
    private static final int MAX_SWEEP_NODES = 20_000;

    /** Safety-net rebuild of the tracked-world list, in ticks (5 minutes). */
    private static final int WORLD_REFRESH_TICKS = 20 * 60 * 5;

    private final WeatherEngine engine = new WeatherEngine();

    /**
     * Everything the tick loop needs for one managed world, resolved once when
     * the world is registered rather than 20 times a second.
     * <p>
     * The old tick path called {@code world.getKey().toString()} and allocated
     * two capturing lambdas per world per tick — ~60 short-lived objects a
     * second on a three-world server, all to service a timer that fires twice an
     * hour. Holding them here makes {@link #tickWeather()} allocation-free.
     */
    private static final class TrackedWorld {
        final World                               world;
        final String                              key;
        final WeatherEngine.WeatherApplier        applier;
        final WeatherEngine.BiomeCategorySource   biomeSource;

        TrackedWorld(WeatherStructurePlugin plugin, World world) {
            this.world       = world;
            this.key         = world.getKey().toString();
            this.applier     = (type, duration) -> plugin.applyWeatherType(world, type, duration);
            this.biomeSource = () -> plugin.getSpawnBiomeCategory(world);
        }
    }

    private static final TrackedWorld[] NO_WORLDS = new TrackedWorld[0];

    /** Managed overworld-environment worlds. Replaced wholesale, never mutated in place. */
    private volatile TrackedWorld[] tracked = NO_WORLDS;

    /** Applies a weather to every managed world; used for timed overrides, which are global. */
    private WeatherEngine.WeatherApplier allWorldsApplier;

    private BukkitTask tickTask;
    private int worldRefreshCountdown = WORLD_REFRESH_TICKS;

    @Override
    public void onEnable() {
        Logger log = getLogger();
        log.info("[WSM] Weather & Structure Mod v1.8.0 (Paper, MC 26.x) enabling...");

        allWorldsApplier = (type, duration) -> {
            TrackedWorld[] worlds = tracked;
            for (TrackedWorld tw : worlds) {
                applyWeatherType(tw.world, type, duration);
            }
        };

        rebuildTrackedWorlds(null);
        getServer().getPluginManager().registerEvents(this, this);

        boostStructureDensity();

        tickTask = new BukkitRunnable() {
            @Override public void run() { tickWeather(); }
        }.runTaskTimer(this, 1L, 1L);

        log.info("[WSM] Enabled — managing " + tracked.length + " world(s).");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // Drop engine timers and the World references we were holding so a
        // /reload starts from a clean slate instead of inheriting a half-elapsed
        // cycle timer or a live override.
        engine.reset();
        tracked = NO_WORLDS;
        getLogger().info("[WSM] Weather & Structure Mod disabled.");
    }

    // ── World tracking ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        rebuildTrackedWorlds(null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        // Fired before the world actually unloads, so it is still in
        // Bukkit.getWorlds() — exclude it explicitly.
        World unloading = event.getWorld();
        engine.forget(unloading.getKey().toString());
        rebuildTrackedWorlds(unloading);
    }

    /**
     * Rebuilds the managed-world list and prunes engine timers for worlds that
     * are gone. Previously this ran on a 30-second poll that also re-resolved
     * every spawn biome; it is now event-driven, with a low-frequency safety net.
     *
     * @param excluded a world to leave out (an unloading world is still listed)
     */
    private void rebuildTrackedWorlds(World excluded) {
        List<TrackedWorld> managed = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (world == excluded) continue;
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            try {
                managed.add(new TrackedWorld(this, world));
            } catch (RuntimeException e) {
                getLogger().warning("[WSM] Skipping world '" + world.getName()
                    + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        TrackedWorld[] next = managed.toArray(NO_WORLDS);
        tracked = next;

        // Forget cycling state for worlds we no longer manage, so the engine's
        // timer map doesn't grow for the lifetime of the server.
        List<String> stale = new ArrayList<>(engine.trackedWorldKeys());
        for (String key : stale) {
            boolean stillManaged = false;
            for (TrackedWorld tw : next) {
                if (tw.key.equals(key)) { stillManaged = true; break; }
            }
            if (!stillManaged) engine.forget(key);
        }
        worldRefreshCountdown = WORLD_REFRESH_TICKS;
    }

    // ── Weather cycling ───────────────────────────────────────────────────

    private void tickWeather() {
        if (--worldRefreshCountdown <= 0) {
            // Safety net for worlds created without a WorldLoadEvent.
            rebuildTrackedWorlds(null);
        }

        // The timed override is global, so it is advanced exactly once per server
        // tick — not once per world. Driving it from the per-world loop made a
        // `/timedweather rain 60` on a three-world server expire in 20 seconds.
        if (engine.tickTimedOverride(allWorldsApplier)) {
            getLogger().info("[WSM] Timed weather expired → CLEAR.");
            return;
        }

        TrackedWorld[] worlds = tracked;
        for (TrackedWorld tw : worlds) {
            try {
                WeatherType changed = engine.tickWorld(tw.key, tw.biomeSource, tw.applier);
                if (changed != null) {
                    getLogger().info("[WSM] '" + tw.world.getName() + "' → " + changed + ".");
                }
            } catch (RuntimeException e) {
                // One misbehaving world must not stop the others (or kill the task).
                getLogger().log(Level.WARNING, "[WSM] Weather tick failed for '"
                    + tw.world.getName() + "'", e);
            }
        }
    }

    private void applyWeatherType(World world, WeatherType type, int durationTicks) {
        int duration = Math.max(1, durationTicks);
        boolean storm    = type != WeatherType.CLEAR;
        boolean thunder  = type == WeatherType.THUNDER;

        world.setStorm(storm);
        world.setThundering(thunder);
        // Both timers have to be pushed out, not just the rain one: leaving
        // thunderDuration at its vanilla value let the server start a
        // thunderstorm on top of weather the mod had just set.
        world.setWeatherDuration(duration);
        world.setThunderDuration(duration);
    }

    /**
     * Resolves a world's spawn-biome climate category.
     * <p>
     * Consulted only when the engine actually rolls new weather (once every
     * 30–60 min) instead of on a 30-second cache refresh, so moving spawn with
     * {@code /setworldspawn} takes effect and the biome lookup is no longer a
     * recurring cost.
     */
    private BiomeCategory getSpawnBiomeCategory(World world) {
        try {
            Location spawn = world.getSpawnLocation();
            org.bukkit.block.Biome biome = world.getBiome(
                spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()
            );
            return BiomeCategory.fromBiomeId(biome.getKey().toString());
        } catch (RuntimeException e) {
            getLogger().fine("[WSM] Spawn biome lookup failed for '" + world.getName()
                + "', defaulting to TEMPERATE: " + e.getMessage());
            return BiomeCategory.TEMPERATE;
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // WeatherEngine is main-thread-only by design. Bukkit does not stop a
        // plugin from dispatching a command asynchronously, and doing so here
        // would race the tick loop and corrupt the override counter — hop onto
        // the main thread instead of trusting the caller.
        if (!Bukkit.isPrimaryThread()) {
            getServer().getScheduler().runTask(this, () -> onCommand(sender, command, label, args));
            return true;
        }

        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("weatherforecast")) {
            return handleWeatherForecast(sender);
        }
        if (!cmdName.equals("timedweather")) {
            return false;
        }
        if (!sender.hasPermission("weatherstructuremod.timedweather")) {
            sender.sendMessage("[WSM] You don't have permission to use this command.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            return handleTimedWeatherStatus(sender);
        }
        if (args.length < 2) {
            sender.sendMessage("[WSM] Usage: /timedweather <clear|rain|thunder|status> <seconds>");
            return true;
        }

        WeatherType weatherType = WeatherType.fromName(args[0]);
        if (weatherType == null) {
            sender.sendMessage("[WSM] Invalid weather type! Use: clear, rain, thunder, or status.");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            // args[1] is attacker-controlled: strip section signs and control
            // characters and cap the length before echoing it back to chat.
            sender.sendMessage("[WSM] Invalid number: " + sanitizeForEcho(args[1]));
            return true;
        }
        if (seconds < 1 || seconds > MAX_SECONDS) {
            sender.sendMessage("[WSM] Seconds must be between 1 and " + MAX_SECONDS + ".");
            return true;
        }

        if (tracked.length == 0) {
            sender.sendMessage("[WSM] No overworld-environment world is loaded.");
            return true;
        }

        int held = engine.setTimedWeather(weatherType, seconds * 20, allWorldsApplier);
        int heldSeconds = held / 20;

        sender.sendMessage("[WSM] Weather set to " + weatherType.getCommandName() + " for "
            + heldSeconds + "s. Will revert to CLEAR after.");
        getLogger().info("[WSM] Timed weather: " + weatherType + " for " + heldSeconds
            + "s (by " + sender.getName() + ").");
        return true;
    }

    private boolean handleTimedWeatherStatus(CommandSender sender) {
        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            sender.sendMessage("[WSM] Timed weather: " + engine.getTimedWeatherType()
                + " — " + WeatherEngine.formatTicks(remaining)
                + " remaining (" + remaining + " ticks)");
        } else {
            sender.sendMessage("[WSM] No timed weather active. Normal cycling is running.");
        }
        return true;
    }

    private boolean handleWeatherForecast(CommandSender sender) {
        if (!sender.hasPermission("weatherstructuremod.weatherforecast")) {
            sender.sendMessage("[WSM] You don't have permission to use this command.");
            return true;
        }

        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            sender.sendMessage("[WSM] Timed weather active: " + engine.getTimedWeatherType()
                + "\n  Remaining: " + WeatherEngine.formatTicks(remaining)
                + " (" + remaining + " ticks)"
                + "\n  Normal cycling resumes after timer expires.");
            return true;
        }

        TrackedWorld[] worlds = tracked;
        if (worlds.length == 0) {
            sender.sendMessage("[WSM] No overworld-environment world is loaded.");
            return true;
        }

        StringBuilder message = new StringBuilder("[WSM] Weather forecast:");
        for (TrackedWorld tw : worlds) {
            int ticksLeft = engine.getTicksUntilNextChange(tw.key);
            String forecast = ticksLeft > 0
                ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
                : "imminent";
            message.append("\n  ").append(tw.world.getName())
                   .append(" — next change in ~").append(forecast)
                   .append(", spawn biome influence: ")
                   .append(tw.biomeSource.get().name());
        }
        sender.sendMessage(message.toString());
        return true;
    }

    private static final String[] TIMEDWEATHER_SUBCOMMANDS = { "clear", "rain", "thunder", "status" };
    private static final String[] DURATION_SUGGESTIONS      = { "30", "60", "120", "300", "600" };

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        // Don't leak the command's shape to senders who can't run it.
        if (!cmdName.equals("timedweather")
                || !sender.hasPermission("weatherstructuremod.timedweather")) {
            return List.of();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>(TIMEDWEATHER_SUBCOMMANDS.length);
            for (String option : TIMEDWEATHER_SUBCOMMANDS) {
                if (option.startsWith(prefix)) matches.add(option);
            }
            return matches;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status")) {
            return List.of(DURATION_SUGGESTIONS);
        }
        return List.of();
    }

    /**
     * Makes a user-supplied token safe to echo into a chat reply: drops the
     * legacy section sign (which the client renders as a colour/format code) and
     * any control characters, then truncates. Without this, a crafted argument
     * could recolour or pad the plugin's own messages.
     */
    private static String sanitizeForEcho(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int limit = Math.min(raw.length(), MAX_ECHO_LENGTH);
        StringBuilder out = new StringBuilder(limit + 1);
        for (int i = 0; i < limit; i++) {
            char c = raw.charAt(i);
            // U+00A7 is the legacy formatting prefix; C0 controls and DEL can
            // break message framing in the client.
            if (c == '\u00a7' || c < ' ' || c == '\u007f') continue;
            out.append(c);
        }
        if (raw.length() > limit) out.append("...");
        return out.toString();
    }

    // ── Structure density boost ───────────────────────────────────────────

    /**
     * Shrinks spacing/separation on every {@code RandomSpreadStructurePlacement}
     * so structures generate closer together.
     * <p>
     * Runs at most once per JVM. Prefers targeted registry access and falls back
     * to a bounded reflective sweep of the chunk generator only if that finds
     * nothing.
     */
    private void boostStructureDensity() {
        Logger log = getLogger();

        if (System.getProperty(BOOST_MARKER_PROPERTY) != null) {
            log.info("[WSM] Structure boost already applied in this JVM (plugin reload) — "
                + "skipping so spacing doesn't compound.");
            return;
        }

        try {
            int boosted = applyStructureBoost(log);
            if (boosted > 0) {
                log.info("[WSM] Structure boost applied to " + boosted + " placement(s).");
                // Claim the marker only once something was actually mutated. Every
                // path that reaches zero — placement fields missing, no world
                // loaded, both lookups failing — returns before touching a field,
                // so a zero pass leaves nothing to compound and a later /reload is
                // free to retry (which is what rescues a load-order miss).
                //
                // Note the converse does NOT hold for a *partial* pass: the
                // identity guard that stops double-boosting lives in
                // applyStructureBoost and dies with it, so re-running after a
                // partial success would shrink the placements that already
                // succeeded a second time. Once any placement is boosted the
                // marker has to stick.
                System.setProperty(BOOST_MARKER_PROPERTY, "1");
            } else {
                log.info("[WSM] Structure boost: no placements found — world may use a "
                    + "custom generator. Nothing was modified, so a /reload will retry.");
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.warning("[WSM] NMS class not found — are you running a non-Paper server? "
                + "Structure boost skipped.");
        } catch (Throwable t) {
            // Reflection against a moving NMS target can throw Errors
            // (NoSuchMethodError, ExceptionInInitializerError, …) as easily as
            // Exceptions. Catching only Exception let those abort onEnable, which
            // took weather cycling down with the boost.
            log.warning("[WSM] Structure boost failed: " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
            log.warning("[WSM] Weather cycling is unaffected.");
        }
    }

    private int applyStructureBoost(Logger log) throws ReflectiveOperationException {
        Class<?> randomSpreadClass = Class.forName(
            "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement"
        );

        Field spacingField    = getDeclaredFieldDeep(randomSpreadClass, "spacing");
        Field separationField = getDeclaredFieldDeep(randomSpreadClass, "separation");
        if (spacingField == null || separationField == null) {
            log.warning("[WSM] Could not find spacing/separation fields — structure boost skipped.");
            return 0;
        }
        spacingField.setAccessible(true);
        separationField.setAccessible(true);

        // One identity set for the whole run. The StructureSet registry is
        // server-global, so the same placement object is reachable from every
        // world; the old per-world loop boosted each one once per NORMAL world,
        // multiplying spacing by DENSITY_FACTOR^worlds (a Multiverse server with
        // three overworlds shrank village spacing by 34%, not 13%).
        Set<Object> alreadyBoosted = Collections.newSetFromMap(new IdentityHashMap<>());

        int boosted = tryTargetedRegistryBoost(randomSpreadClass, spacingField, separationField,
                                               alreadyBoosted, log);
        if (boosted == 0) {
            boosted = fallbackReflectiveSweep(randomSpreadClass, spacingField, separationField,
                                              alreadyBoosted, log);
        }
        return boosted;
    }

    /**
     * Targeted approach: reach the server's {@code STRUCTURE_SETS} registry and
     * boost each set's placement directly. One pass for the whole server — the
     * registry is not per world.
     */
    private int tryTargetedRegistryBoost(
        Class<?> randomSpreadClass, Field spacingField, Field separationField,
        Set<Object> alreadyBoosted, Logger log
    ) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            log.warning("[WSM] No world loaded at enable time — structure boost skipped. "
                + "(plugin.yml must keep `load: POSTWORLD`.)");
            return 0;
        }

        int count = 0;
        try {
            Object serverLevel = worlds.get(0).getClass().getMethod("getHandle").invoke(worlds.get(0));
            Object server      = serverLevel.getClass().getMethod("getServer").invoke(serverLevel);
            Object registryAccess = server.getClass().getMethod("registryAccess").invoke(server);

            Object structureSetsKey = registriesField("STRUCTURE_SETS");
            Object registry = lookupRegistry(registryAccess, structureSetsKey);
            if (registry == null) {
                log.fine("[WSM] Could not resolve the STRUCTURE_SETS registry.");
                return 0;
            }

            for (Object structureSet : (Iterable<?>) registry) {
                Object placement = structureSet.getClass().getMethod("placement").invoke(structureSet);
                if (randomSpreadClass.isInstance(placement) && alreadyBoosted.add(placement)) {
                    count += applyDensityBoost(placement, spacingField, separationField);
                }
            }
        } catch (Throwable t) {
            log.fine("[WSM] Targeted registry approach unavailable: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return count;
    }

    private Object registriesField(String name) throws ReflectiveOperationException {
        Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries");
        Field field = registriesClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Resolves a registry from a {@code RegistryAccess}. The accessor has been
     * renamed more than once ({@code registryOrThrow} → {@code lookupOrThrow}),
     * so try the known names rather than silently falling through to the
     * reflective sweep on a version that only has the newer one.
     */
    private Object lookupRegistry(Object registryAccess, Object registryKey) {
        Class<?> resourceKeyClass;
        try {
            resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (String methodName : new String[]{ "lookupOrThrow", "registryOrThrow", "registry" }) {
            try {
                Object result = registryAccess.getClass()
                    .getMethod(methodName, resourceKeyClass)
                    .invoke(registryAccess, registryKey);
                if (result instanceof java.util.Optional<?> optional) {
                    result = optional.orElse(null);
                }
                if (result instanceof Iterable<?>) {
                    return result;
                }
            } catch (Throwable ignored) {
                // Try the next candidate name.
            }
        }
        return null;
    }

    /**
     * Fallback: bounded reflective sweep of the chunk generator object graph.
     * <p>
     * Deliberately narrower than a generic object walk. It never reflects into
     * JDK classes ({@code setAccessible} on {@code java.*} throws on a modern
     * JVM and can force lazily-initialised state to materialise with side
     * effects), never touches {@link Reference} instances, and stops after
     * {@link #MAX_SWEEP_NODES} objects or {@link #MAX_SWEEP_DEPTH} levels.
     */
    private int fallbackReflectiveSweep(
        Class<?> randomSpreadClass, Field spacingField, Field separationField,
        Set<Object> alreadyBoosted, Logger log
    ) {
        int boosted = 0;
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        int[] budget = { MAX_SWEEP_NODES };

        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            try {
                Object serverLevel  = world.getClass().getMethod("getHandle").invoke(world);
                Object chunkSource  = serverLevel.getClass().getMethod("getChunkSource").invoke(serverLevel);
                Object generator    = chunkSource.getClass().getMethod("getGenerator").invoke(chunkSource);

                boosted += reflectiveSweep(generator, randomSpreadClass, spacingField,
                                           separationField, 0, visited, alreadyBoosted, budget);
            } catch (Throwable t) {
                log.warning("[WSM] Reflective sweep failed for '" + world.getName() + "': "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        if (budget[0] <= 0) {
            log.fine("[WSM] Reflective sweep hit its " + MAX_SWEEP_NODES + "-object budget.");
        }
        return boosted;
    }

    private int reflectiveSweep(
        Object obj,
        Class<?> targetClass,
        Field spacingField,
        Field separationField,
        int depth,
        Map<Object, Boolean> visited,
        Set<Object> alreadyBoosted,
        int[] budget
    ) {
        if (obj == null || depth > MAX_SWEEP_DEPTH || budget[0] <= 0) return 0;
        if (visited.put(obj, Boolean.TRUE) != null) return 0;
        budget[0]--;

        if (targetClass.isInstance(obj)) {
            return alreadyBoosted.add(obj) ? applyDensityBoost(obj, spacingField, separationField) : 0;
        }

        Class<?> cls = obj.getClass();
        // Never walk into these: Reference would let us resurrect a referent the
        // GC is retiring, and the rest are graph-wide shortcuts to the whole JVM.
        if (cls == String.class || cls == Class.class
                || obj instanceof Reference<?>
                || obj instanceof ClassLoader
                || obj instanceof Thread) {
            return 0;
        }

        int count = 0;

        // Containers are traversed through their public interfaces rather than by
        // reflecting into JDK internals.
        try {
            if (obj instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    count += reflectiveSweep(value, targetClass, spacingField, separationField,
                                             depth + 1, visited, alreadyBoosted, budget);
                }
                return count;
            }
            if (obj instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    count += reflectiveSweep(item, targetClass, spacingField, separationField,
                                             depth + 1, visited, alreadyBoosted, budget);
                }
                return count;
            }
            if (cls.isArray()) {
                if (cls.getComponentType().isPrimitive()) return 0;
                for (Object item : (Object[]) obj) {
                    count += reflectiveSweep(item, targetClass, spacingField, separationField,
                                             depth + 1, visited, alreadyBoosted, budget);
                }
                return count;
            }
        } catch (Throwable t) {
            getLogger().fine("[WSM] Could not traverse " + cls.getName() + ": " + t.getMessage());
            return count;
        }

        String pkg = cls.getPackageName();
        if (!pkg.startsWith("net.minecraft") && !pkg.startsWith("com.mojang")) return 0;

        for (Field field : cls.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || field.isSynthetic()) continue;
            Class<?> type = field.getType();
            if (type.isPrimitive() || type == String.class) continue;
            try {
                field.setAccessible(true);
                count += reflectiveSweep(field.get(obj), targetClass, spacingField, separationField,
                                         depth + 1, visited, alreadyBoosted, budget);
            } catch (Throwable t) {
                getLogger().fine("[WSM] Skipping field " + cls.getSimpleName() + "."
                    + field.getName() + " — " + t.getMessage());
            }
        }
        return count;
    }

    /**
     * Shrinks one placement's spacing/separation.
     * <p>
     * The clamps only ever <em>reduce</em> a value. Applying the floors the other
     * way round ({@code max(MIN_SPACING, scaled)}) raised spacing from 1 to 2 and
     * separation from 0 to 1 on placements that were already at or below the
     * floor — making those structures rarer, the opposite of the intent.
     *
     * @return 1 if anything changed, else 0
     */
    private int applyDensityBoost(Object placement, Field spacingField, Field separationField) {
        try {
            int spacing    = spacingField.getInt(placement);
            int separation = separationField.getInt(placement);

            int newSpacing    = Math.min(spacing,    Math.max(MIN_SPACING,    (int) (spacing    * DENSITY_FACTOR)));
            int newSeparation = Math.min(separation, Math.max(MIN_SEPARATION, (int) (separation * DENSITY_FACTOR)));
            // Vanilla requires separation < spacing.
            if (newSeparation >= newSpacing) newSeparation = Math.max(0, newSpacing - 1);

            if (newSpacing == spacing && newSeparation == separation) return 0;

            spacingField.setInt(placement,    newSpacing);
            separationField.setInt(placement, newSeparation);
            return 1;
        } catch (Throwable t) {
            getLogger().fine("[WSM] Could not boost placement: " + t.getMessage());
            return 0;
        }
    }

    private Field getDeclaredFieldDeep(Class<?> cls, String name) {
        for (Class<?> current = cls; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Walk up to the superclass.
            }
        }
        return null;
    }
}
