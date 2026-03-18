package com.example.weathermod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class WeatherStructureMod implements ModInitializer {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private enum WeatherType { CLEAR, RAIN, THUNDER }

    // Cache values() array — avoids a new allocation on every weather change
    private static final WeatherType[] WEATHER_TYPES = WeatherType.values();

    private final Map<String, Integer> weatherTimers = new HashMap<>();

    // 30–60 minutes in ticks (20 ticks/sec × 60 sec/min)
    private static final int MIN_TICKS      = 30 * 60 * 20;  // 36,000
    private static final int MAX_TICKS      = 60 * 60 * 20;  // 72,000
    private static final int INTERVAL_RANGE = MAX_TICKS - MIN_TICKS + 1;

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] v1.2.0 — Fabric — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
    }

    private void onWorldTick(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) return;

        String key = world.getRegistryKey().getValue().toString();

        // First tick for this world: initialise the countdown and return
        Integer timer = weatherTimers.get(key);
        if (timer == null) {
            int initial = randomInterval();
            weatherTimers.put(key, initial);
            LOGGER.info("[WeatherStructureMod] First weather change in {} ticks (~{} sec).", initial, initial / 20);
            return;
        }

        int ticksLeft = timer - 1;
        if (ticksLeft <= 0) {
            applyRandomWeather(world);
            int next = randomInterval();
            weatherTimers.put(key, next);
            LOGGER.info("[WeatherStructureMod] Next weather change in {} ticks (~{} sec).", next, next / 20);
        } else {
            weatherTimers.put(key, ticksLeft);
        }
    }

    private void applyRandomWeather(ServerWorld world) {
        // ThreadLocalRandom — faster than shared Random, no lock contention
        WeatherType chosen = WEATHER_TYPES[ThreadLocalRandom.current().nextInt(WEATHER_TYPES.length)];
        // Use large duration so vanilla MC never overrides before our next cycle
        switch (chosen) {
            case CLEAR   -> { world.setWeather(999_999, 0,       false, false); LOGGER.info("[WeatherStructureMod] Weather → CLEAR."); }
            case RAIN    -> { world.setWeather(0,       999_999, true,  false); LOGGER.info("[WeatherStructureMod] Weather → RAIN."); }
            case THUNDER -> { world.setWeather(0,       999_999, true,  true);  LOGGER.info("[WeatherStructureMod] Weather → THUNDER."); }
        }
    }

    private static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }
}
