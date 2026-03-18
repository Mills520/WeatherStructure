package com.example.weathermod;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Paper / Spigot / Bukkit plugin — Weather & Structure Mod v1.1.0
 *
 * Feature 1 — Dynamic Weather Cycling:
 *   Uses the Bukkit scheduler to run a per-world countdown each tick.
 *   Every 30–60 min it randomly switches the Overworld between CLEAR, RAIN, THUNDER.
 *
 * Feature 2 — Structure Spawn Boost (+15%):
 *   Uses reflection to reduce spacing/separation on RandomSpreadStructurePlacement
 *   objects registered in the server's chunk generator. Runs once at startup.
 */
public class WeatherStructurePlugin extends JavaPlugin {

    private static final int   MIN_TICKS      = 30 * 60 * 20;  // 36,000
    private static final int   MAX_TICKS      = 60 * 60 * 20;  // 72,000
    private static final int   INTERVAL_RANGE = MAX_TICKS - MIN_TICKS + 1;
    private static final float DENSITY_FACTOR = 0.87f;

    // Cache of overworld-environment worlds — refreshed on each weather tick
    // Avoids calling Bukkit.getWorlds() + filtering every single tick
    private List<World> overworldCache = List.of();
    private int         cacheAgeTimer  = 0;
    private static final int CACHE_TTL = 20 * 30; // refresh every 30 seconds

    private final Map<String, Integer> weatherTimers = new HashMap<>();

    @Override
    public void onEnable() {
        Logger log = getLogger();
        log.info("[WSM] Weather & Structure Mod v1.1.0 (Paper) enabling...");

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
            String key = world.getName();

            Integer timer = weatherTimers.get(key);
            if (timer == null) {
                weatherTimers.put(key, randomInterval());
                continue;
            }

            int ticksLeft = timer - 1;
            if (ticksLeft <= 0) {
                applyRandomWeather(world);
                weatherTimers.put(key, randomInterval());
            } else {
                weatherTimers.put(key, ticksLeft);
            }
        }
    }

    private void applyRandomWeather(World world) {
        // Uses only standard Bukkit API — compatible with both Spigot and Paper
        switch (ThreadLocalRandom.current().nextInt(3)) {
            // Use large duration so vanilla MC never overrides before our next cycle
            case 0 -> {  // CLEAR
                world.setStorm(false);
                world.setThundering(false);
                world.setWeatherDuration(999_999);
                getLogger().info("[WSM] '" + world.getName() + "' → CLEAR.");
            }
            case 1 -> {  // RAIN
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(999_999);
                getLogger().info("[WSM] '" + world.getName() + "' → RAIN.");
            }
            case 2 -> {  // THUNDER
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(999_999);
                world.setThunderDuration(999_999);
                getLogger().info("[WSM] '" + world.getName() + "' → THUNDER.");
            }
        }
    }

    private static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }

    // ── Structure density boost ───────────────────────────────────────────

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

            // Walk each overworld's chunk generator looking for RandomSpreadStructurePlacement instances
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
                int s = (int) spacingField.get(obj);
                int sep = (int) separationField.get(obj);

                int newSpacing    = Math.max(2, (int)(s   * DENSITY_FACTOR));
                int newSeparation = Math.max(1, (int)(sep * DENSITY_FACTOR));
                if (newSeparation >= newSpacing) newSeparation = newSpacing - 1;

                spacingField.set(obj,    newSpacing);
                separationField.set(obj, newSeparation);
                return 1;
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
                    // Expected for inaccessible fields — only log at FINE level
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
