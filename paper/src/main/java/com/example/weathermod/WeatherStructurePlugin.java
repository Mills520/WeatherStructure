package com.example.weathermod;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Paper / Bukkit plugin edition of Weather & Structure Mod.
 *
 * Feature 1 — Dynamic Weather Cycling:
 *   Uses the Bukkit scheduler to check a per-world countdown each tick.
 *   Every 5–15 min it randomly switches the Overworld to CLEAR, RAIN, or THUNDER.
 *
 * Feature 2 — Structure Spawn Boost (+15%):
 *   Uses reflection to reduce the spacing/separation fields on
 *   RandomSpreadStructurePlacement objects that are registered in the
 *   server's structure registry.  This runs once at startup and affects
 *   all newly generated chunks.  Already-generated chunks are unaffected.
 */
public class WeatherStructurePlugin extends JavaPlugin {

    private static final int MIN_TICKS = 5  * 60 * 20;   // 6,000
    private static final int MAX_TICKS = 15 * 60 * 20;   // 18,000
    private static final float DENSITY_FACTOR = 0.87f;

    private final Random random = new Random();

    // Per-world countdown (ticks remaining)
    private final java.util.Map<String, Integer> weatherTimers = new java.util.HashMap<>();

    @Override
    public void onEnable() {
        Logger log = getLogger();
        log.info("Weather & Structure Mod (Paper) enabling...");

        // ── Feature 2: boost structure density at startup ─────────────────
        boostStructureDensity();

        // ── Feature 1: start per-tick weather manager ────────────────────
        new BukkitRunnable() {
            @Override
            public void run() {
                tickWeather();
            }
        }.runTaskTimer(this, 1L, 1L);  // delay=1, period=1 tick

        log.info("Weather & Structure Mod (Paper) enabled — Dynamic Weather & Structure Boost active.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Weather & Structure Mod (Paper) disabled.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Weather cycling
    // ─────────────────────────────────────────────────────────────────────

    private void tickWeather() {
        for (World world : Bukkit.getWorlds()) {
            // Only affect the normal Overworld
            if (world.getEnvironment() != World.Environment.NORMAL) continue;

            String key = world.getName();

            if (!weatherTimers.containsKey(key)) {
                weatherTimers.put(key, randomInterval());
                continue;
            }

            int ticksLeft = weatherTimers.get(key) - 1;
            if (ticksLeft <= 0) {
                applyRandomWeather(world);
                weatherTimers.put(key, randomInterval());
            } else {
                weatherTimers.put(key, ticksLeft);
            }
        }
    }

    private void applyRandomWeather(World world) {
        int roll = random.nextInt(3);
        switch (roll) {
            case 0 -> {  // CLEAR
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(6000);
                getLogger().info("[WSM] World '" + world.getName() + "' weather → CLEAR.");
            }
            case 1 -> {  // RAIN
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(6000);
                getLogger().info("[WSM] World '" + world.getName() + "' weather → RAIN.");
            }
            case 2 -> {  // THUNDER
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(6000);
                world.setThunderDuration(6000);
                getLogger().info("[WSM] World '" + world.getName() + "' weather → THUNDER.");
            }
        }
    }

    private int randomInterval() {
        return MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS + 1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Structure density boost via reflection on NMS internals.
    // We walk all StructurePlacement objects the server has registered
    // and shrink spacing+separation on any RandomSpreadStructurePlacement.
    //
    // This is best-effort: if Mojang renames internals in a future patch
    // the mod will log a warning and skip the boost rather than crashing.
    // ─────────────────────────────────────────────────────────────────────

    private void boostStructureDensity() {
        Logger log = getLogger();
        int boosted = 0;

        try {
            // Locate the NMS class through the server's own classloader
            Class<?> randomSpreadClass = Class.forName(
                "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement"
            );

            Field spacingField    = getDeclaredFieldDeep(randomSpreadClass, "spacing");
            Field separationField = getDeclaredFieldDeep(randomSpreadClass, "separation");

            if (spacingField == null || separationField == null) {
                log.warning("[WSM] Could not locate spacing/separation fields — structure boost skipped.");
                return;
            }

            spacingField.setAccessible(true);
            separationField.setAccessible(true);

            // Reach into the server's registries via Paper's CraftServer
            Object minecraftServer = Bukkit.getServer().getClass()
                .getMethod("getServer").invoke(Bukkit.getServer());

            // registries().lookup(Registries.STRUCTURE) returns Optional<HolderLookup.RegistryLookup<Structure>>
            // Easier: iterate ChunkGenerator -> StructurePlacement via the world's chunk generator
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.NORMAL) continue;

                Object craftWorld   = world;
                Object serverLevel  = craftWorld.getClass().getMethod("getHandle").invoke(craftWorld);
                Object chunkSource  = serverLevel.getClass().getMethod("getChunkSource").invoke(serverLevel);
                Object generator    = chunkSource.getClass().getMethod("getGenerator").invoke(chunkSource);

                // getStructuresForBiome / all placements via generator
                // In 1.21.11 ChunkGenerator exposes a structureOverrides() / structureSet registry
                // We use the structureManager instead for a more stable path
                Object structureManager = serverLevel.getClass()
                    .getMethod("structureManager").invoke(serverLevel);

                // StructureManager.registryAccess() → RegistryAccess
                Object registryAccess = structureManager.getClass()
                    .getMethod("registryAccess").invoke(structureManager);

                // registryAccess.lookup(ResourceKey<Registry<StructureSet>>) — complex
                // Simpler: just grab all StructureSets from the generator's settings
                // ChunkGenerator → generatorSettings() → StructureSettings → structureSets()
                // This path varies by generator type, so we use a broad reflection sweep instead

                boosted += reflectiveSweepAndBoost(
                    generator, randomSpreadClass, spacingField, separationField
                );
            }

            if (boosted > 0) {
                log.info("[WSM] Structure boost applied to " + boosted + " placement(s) (+15% density).");
            } else {
                // Fallback: sweep all loaded classes for RandomSpreadStructurePlacement instances
                // This is a no-op in most environments; the sweep above covers vanilla generators
                log.info("[WSM] Structure boost: no placements found via generator sweep — boost may not apply.");
                log.info("[WSM] Tip: ensure the world uses a vanilla chunk generator (not a custom one).");
            }

        } catch (ClassNotFoundException e) {
            log.warning("[WSM] NMS class not found — running on non-Paper server? Structure boost skipped.");
        } catch (Exception e) {
            log.warning("[WSM] Structure boost failed with: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warning("[WSM] Weather cycling is unaffected — only structure boost is skipped.");
        }
    }

    /**
     * Recursively sweeps an object graph looking for RandomSpreadStructurePlacement
     * instances and applies the density boost to each one found.
     * Stops at depth 8 to avoid infinite loops.
     */
    private int reflectiveSweepAndBoost(
        Object root,
        Class<?> targetClass,
        Field spacingField,
        Field separationField
    ) {
        return reflectiveSweep(root, targetClass, spacingField, separationField, 0, new java.util.IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
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
                int oldSpacing    = (int) spacingField.get(obj);
                int oldSeparation = (int) separationField.get(obj);

                int newSpacing    = Math.max(2, (int)(oldSpacing    * DENSITY_FACTOR));
                int newSeparation = Math.max(1, (int)(oldSeparation * DENSITY_FACTOR));
                if (newSeparation >= newSpacing) newSeparation = newSpacing - 1;

                spacingField.set(obj,    newSpacing);
                separationField.set(obj, newSeparation);
                count++;
                return count;
            }

            // Skip primitives, strings, and standard library classes
            Class<?> cls = obj.getClass();
            if (cls.isPrimitive() || cls == String.class) return 0;
            String pkg = cls.getPackageName();
            if (!pkg.startsWith("net.minecraft") && !pkg.startsWith("com.mojang")
                    && !pkg.startsWith("java.util") && !pkg.startsWith("java.lang.ref")) return 0;

            // Recurse into Iterable collections
            if (obj instanceof Iterable<?> iter) {
                for (Object item : iter) {
                    count += reflectiveSweep(item, targetClass, spacingField, separationField, depth + 1, visited);
                }
            }

            // Recurse into declared fields
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    count += reflectiveSweep(val, targetClass, spacingField, separationField, depth + 1, visited);
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}

        return count;
    }

    /**
     * Like getDeclaredField but walks up the class hierarchy.
     */
    private Field getDeclaredFieldDeep(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cur = cur.getSuperclass(); }
        }
        return null;
    }
}
