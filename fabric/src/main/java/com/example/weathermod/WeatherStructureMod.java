package com.example.weathermod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class WeatherStructureMod implements ModInitializer {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private enum WeatherType { CLEAR, RAIN, THUNDER }

    private final Map<String, Integer> weatherTimers = new HashMap<>();
    private final Random random = new Random();

    // 5–15 minutes in ticks (20 ticks/sec × 60 sec/min)
    private static final int MIN_TICKS = 5  * 60 * 20;   // 6,000
    private static final int MAX_TICKS = 15 * 60 * 20;   // 18,000

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] Fabric — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
    }

    private void onWorldTick(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) return;

        String key = world.getRegistryKey().getValue().toString();

        if (!weatherTimers.containsKey(key)) {
            int initial = randomInterval();
            weatherTimers.put(key, initial);
            LOGGER.info("[WeatherStructureMod] First weather change in {} ticks (~{} sec).", initial, initial / 20);
            return;
        }

        int ticksLeft = weatherTimers.get(key) - 1;
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
        switch (WeatherType.values()[random.nextInt(3)]) {
            case CLEAR   -> { world.setWeather(6000, 0,    false, false); LOGGER.info("[WeatherStructureMod] Weather → CLEAR."); }
            case RAIN    -> { world.setWeather(0,    6000, true,  false); LOGGER.info("[WeatherStructureMod] Weather → RAIN."); }
            case THUNDER -> { world.setWeather(0,    6000, true,  true);  LOGGER.info("[WeatherStructureMod] Weather → THUNDER."); }
        }
    }

    private int randomInterval() {
        return MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS + 1);
    }
}
