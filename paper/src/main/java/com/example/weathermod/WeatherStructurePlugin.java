package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Paper / Spigot / Bukkit plugin — Weather & Structure Mod v1.4.0
 *
 * Feature 1 — Dynamic Weather Cycling:
 *   Biome-aware random weather every 30–60 min, weighted by spawn biome.
 *
 * Feature 2 — Structure Spawn Boost (+15%):
 *   Uses targeted registry access with reflection fallback for structure
 *   placement modification. Runs once at startup.
 *
 * Feature 3 — Timed Weather Command (/timedweather):
 *   Allows operators to set weather for a specified duration.
 *
 * Feature 4 — Weather Forecast (/weatherforecast):
 *   Shows next weather change time and spawn biome influence.
 */
public class WeatherStructurePlugin extends JavaPlugin {

    private static final float DENSITY_FACTOR = 0.87f;

    // Cache of overworld-environment worlds — refreshed periodically
    private List<World> overworldCache = List.of();
    private int         cacheAgeTimer  = 0;
    private static final int CACHE_TTL = 20 * 30; // refresh every 30 seconds

    private final WeatherEngine engine = new WeatherEngine();

    @Override
    public void onEnable() {
        Logger log = getLogger();
        log.info("[WSM] Weather & Structure Mod v1.4.0 (Paper) enabling...");

        boostStructureDensity();

        new BukkitRunnable() {
            @Override public void run() { tickWeather(); }
        }.runTaskTimer(this, 1L, 1L);

        log.info("[WSM] Enabled — Dynamic Weather & Structure Boost active.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[WSM] Weather & Structure Mod disabled.");
    }

    // ── Commands ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("weatherforecast")) {
            return handleWeatherForecast(sender);
        }

        if (!cmdName.equals("timedweather")) return false;

        if (!sender.hasPermission("weatherstructuremod.timedweather")) {
            sender.sendMessage("[WSM] You don't have permission to use this command.");
            return true;
        }

        // /timedweather status
        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            return handleTimedWeatherStatus(sender);
        }

        if (args.length < 2) {
            sender.sendMessage("[WSM] Usage: /timedweather <clear|rain|thunder|status> <seconds>");
            return true;
        }

        String type = args[0].toUpperCase(Locale.ROOT);
        WeatherType weatherType = WeatherType.fromName(type);
        if (weatherType == null) {
            sender.sendMessage("[WSM] Invalid weather type! Use: clear, rain, thunder, or status.");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
            if (seconds < 1 || seconds > 86400) {
                sender.sendMessage("[WSM] Seconds must be between 1 and 86400.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("[WSM] Invalid number: " + args[1]);
            return true;
        }

        int ticks = seconds * 20;

        engine.setTimedWeather(weatherType, ticks, (wt, duration) -> {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.NORMAL) continue;
                applyWeatherType(world, wt, duration);
            }
        });

        sender.sendMessage("[WSM] Weather set to " + type + " for " + seconds + "s. Will revert to CLEAR after.");
        getLogger().info("[WSM] Timed weather: " + type + " for " + seconds + "s.");
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
        } else {
            // Use first overworld for forecast
            World firstWorld = overworldCache.isEmpty() ? null : overworldCache.get(0);
            if (firstWorld == null) {
                sender.sendMessage("[WSM] No overworld loaded.");
                return true;
            }
            // Use getKey() for stable identifier (#10)
            String key = firstWorld.getKey().toString();
            int ticksLeft = engine.getTicksUntilNextChange(key);
            BiomeCategory category = getSpawnBiomeCategory(firstWorld);
            String forecast = ticksLeft > 0
                ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
                : "imminent";

            sender.sendMessage("[WSM] Next weather change in ~" + forecast
                + "\n  Spawn biome influence: " + category.name());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("weatherforecast")) return List.of();

        if (!cmdName.equals("timedweather")) return List.of();

        if (args.length == 1) {
            return List.of("clear", "rain", "thunder", "status").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status")) {
            return List.of("30", "60", "120", "300", "600");
        }
        return List.of();
    }

    private void applyWeatherType(World world, WeatherType type, int durationTicks) {
        switch (type) {
            case CLEAR -> {
                world.setStorm(false);
                world.setThundering(false);
                world.setWeatherDuration(durationTicks);
            }
            case RAIN -> {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(durationTicks);
            }
            case THUNDER -> {
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(durationTicks);
                world.setThunderDuration(durationTicks);
            }
        }
    }

    // ── Weather cycling ───────────────────────────────────────────────────

    private void tickWeather() {
        // Refresh world cache periodically rather than every tick
        if (cacheAgeTimer <= 0) {
            overworldCache = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .toList();
            cacheAgeTimer = CACHE_TTL;
        } else {
            cacheAgeTimer--;
        }

        for (World world : overworldCache) {
            // Use getKey() for stable world identifier (#10)
            String key = world.getKey().toString();
            BiomeCategory biomeCategory = getSpawnBiomeCategory(world);

            WeatherType changed = engine.tick(key, biomeCategory, (type, duration) ->
                applyWeatherType(world, type, duration)
            );

            if (changed != null) {
                if (engine.isTimedWeatherActive()) {
                    getLogger().info("[WSM] Timed weather expired → CLEAR.");
                } else {
                    getLogger().info("[WSM] '" + world.getName() + "' → " + changed + ".");
                }
            }
        }
    }

    private BiomeCategory getSpawnBiomeCategory(World world) {
        Location spawn = world.getSpawnLocation();
        org.bukkit.block.Biome biome = world.getBiome(
            spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()
        );
        String biomeId = biome.getKey().toString();
        return BiomeCategory.fromBiomeId(biomeId);
    }

    // ── Structure density boost ───────────────────────────────────────────

    /**
     * Boosts structure density by reducing spacing/separation on all
     * RandomSpreadStructurePlacement instances.
     * <p>
     * First tries targeted registry access (#11) for a cleaner approach,
     * then falls back to the recursive reflective sweep if needed.
     */
    private void boostStructureDensity() {
        Logger log = getLogger();
        int boosted = 0;

        try {
            Class<?> randomSpreadClass = Class.forName(
                "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement"
            );

            Field spacingField    = getDeclaredFieldDeep(randomSpreadClass, "spacing");
            Field separationField = getDeclaredFieldDeep(randomSpreadClass, "separation");

            if (spacingField == null || separationField == null) {
                log.warning("[WSM] Could not find spacing/separation fields — structure boost skipped.");
                return;
            }

            spacingField.setAccessible(true);
            separationField.setAccessible(true);

            // Try targeted registry approach first (#11)
            boosted = tryTargetedRegistryBoost(randomSpreadClass, spacingField, separationField, log);

            // Fall back to reflective sweep if targeted approach found nothing
            if (boosted == 0) {
                boosted = fallbackReflectiveSweep(randomSpreadClass, spacingField, separationField, log);
            }

            if (boosted > 0) {
                log.info("[WSM] Structure boost applied to " + boosted + " placement(s) (+15% density).");
            } else {
                log.info("[WSM] Structure boost: no placements found — world may use a custom generator.");
            }

        } catch (ClassNotFoundException e) {
            log.warning("[WSM] NMS class not found — are you running a non-Paper server? Structure boost skipped.");
        } catch (Exception e) {
            log.warning("[WSM] Structure boost failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warning("[WSM] Weather cycling is unaffected.");
        }
    }

    /**
     * Targeted approach: access the StructureSet registry directly via
     * MinecraftServer → registryAccess, iterate structure sets, and modify
     * placements. Avoids deep recursive traversal (#11).
     */
    private int tryTargetedRegistryBoost(
        Class<?> randomSpreadClass, Field spacingField, Field separationField, Logger log
    ) {
        int count = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.NORMAL) continue;

                Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
                Object server = serverLevel.getClass().getMethod("getServer").invoke(serverLevel);

                // MinecraftServer.registryAccess() -> RegistryAccess
                Object registryAccess = server.getClass().getMethod("registryAccess").invoke(server);

                // Find the STRUCTURE_SETS registry key
                Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries");
                Field structureSetsField = registriesClass.getDeclaredField("STRUCTURE_SETS");
                structureSetsField.setAccessible(true);
                Object structureSetsKey = structureSetsField.get(null);

                // registryAccess.registryOrThrow(key) -> Registry<StructureSet>
                Object registry = registryAccess.getClass()
                    .getMethod("registryOrThrow", Class.forName("net.minecraft.resources.ResourceKey"))
                    .invoke(registryAccess, structureSetsKey);

                // Iterate registry entries
                for (Object entry : (Iterable<?>) registry) {
                    // StructureSet.placement() -> StructurePlacement
                    Object placement = entry.getClass().getMethod("placement").invoke(entry);
                    if (randomSpreadClass.isInstance(placement)) {
                        count += applyDensityBoost(placement, spacingField, separationField);
                    }
                }
            }
        } catch (Exception e) {
            log.fine("[WSM] Targeted registry approach unavailable: " + e.getMessage());
        }
        return count;
    }

    /**
     * Fallback: recursive reflective sweep of the chunk generator object graph.
     */
    private int fallbackReflectiveSweep(
        Class<?> randomSpreadClass, Field spacingField, Field separationField, Logger log
    ) {
        int boosted = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.NORMAL) continue;

                Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
                Object chunkSource = serverLevel.getClass().getMethod("getChunkSource").invoke(serverLevel);
                Object generator   = chunkSource.getClass().getMethod("getGenerator").invoke(chunkSource);

                boosted += reflectiveSweep(
                    generator, randomSpreadClass, spacingField, separationField,
                    0, new java.util.IdentityHashMap<>()
                );
            }
        } catch (Exception e) {
            log.warning("[WSM] Reflective sweep failed: " + e.getMessage());
        }
        return boosted;
    }

    private int applyDensityBoost(Object obj, Field spacingField, Field separationField) {
        try {
            int s   = (int) spacingField.get(obj);
            int sep = (int) separationField.get(obj);

            int newSpacing    = Math.max(2, (int)(s   * DENSITY_FACTOR));
            int newSeparation = Math.max(1, (int)(sep * DENSITY_FACTOR));
            if (newSeparation >= newSpacing) newSeparation = newSpacing - 1;

            spacingField.set(obj,    newSpacing);
            separationField.set(obj, newSeparation);
            return 1;
        } catch (Exception e) {
            getLogger().fine("[WSM] Could not boost placement: " + e.getMessage());
            return 0;
        }
    }

    private int reflectiveSweep(
        Object obj,
        Class<?> targetClass,
        Field spacingField,
        Field separationField,
        int depth,
        java.util.IdentityHashMap<Object, Boolean> visited
    ) {
        if (obj == null || depth > 8 || visited.containsKey(obj)) return 0;
        visited.put(obj, Boolean.TRUE);

        int count = 0;
        try {
            if (targetClass.isInstance(obj)) {
                return applyDensityBoost(obj, spacingField, separationField);
            }

            Class<?> cls = obj.getClass();
            if (cls.isPrimitive() || cls == String.class) return 0;
            String pkg = cls.getPackageName();
            if (!pkg.startsWith("net.minecraft") && !pkg.startsWith("com.mojang")
                    && !pkg.startsWith("java.util") && !pkg.startsWith("java.lang.ref")) return 0;

            if (obj instanceof Iterable<?> iter) {
                for (Object item : iter) {
                    count += reflectiveSweep(item, targetClass, spacingField, separationField, depth + 1, visited);
                }
            }

            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    count += reflectiveSweep(val, targetClass, spacingField, separationField, depth + 1, visited);
                } catch (Exception e) {
                    getLogger().fine("[WSM] Skipping field: " + f.getName() + " — " + e.getMessage());
                }
            }

        } catch (Exception e) {
            getLogger().fine("[WSM] Sweep error at depth " + depth + ": " + e.getMessage());
        }

        return count;
    }

    private Field getDeclaredFieldDeep(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cur = cur.getSuperclass(); }
        }
        return null;
    }
}
