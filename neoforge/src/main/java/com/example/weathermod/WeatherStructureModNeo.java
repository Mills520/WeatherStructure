package com.example.weathermod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Mod("weatherstructuremod")
public class WeatherStructureModNeo {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private enum WeatherType { CLEAR, RAIN, THUNDER }

    // Cache values() array — avoids a new allocation on every weather change
    private static final WeatherType[] WEATHER_TYPES = WeatherType.values();

    private final Map<ResourceKey<Level>, Integer> weatherTimers = new HashMap<>();

    // 30–60 minutes in ticks (20 ticks/sec × 60 sec/min)
    private static final int MIN_TICKS      = 30 * 60 * 20;  // 36,000
    private static final int MAX_TICKS      = 60 * 60 * 20;  // 72,000
    private static final int INTERVAL_RANGE = MAX_TICKS - MIN_TICKS + 1;

    public WeatherStructureModNeo(IEventBus modBus) {
        LOGGER.info("[WeatherStructureMod] v1.1.0 — NeoForge — Dynamic Weather & Structure Boost active.");
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        ResourceKey<Level> key = level.dimension();

        Integer timer = weatherTimers.get(key);
        if (timer == null) {
            int initial = randomInterval();
            weatherTimers.put(key, initial);
            LOGGER.info("[WeatherStructureMod] First weather change in {} ticks (~{} sec).", initial, initial / 20);
            return;
        }

        int ticksLeft = timer - 1;
        if (ticksLeft <= 0) {
            applyRandomWeather(level);
            int next = randomInterval();
            weatherTimers.put(key, next);
            LOGGER.info("[WeatherStructureMod] Next weather change in {} ticks (~{} sec).", next, next / 20);
        } else {
            weatherTimers.put(key, ticksLeft);
        }
    }

    private void applyRandomWeather(ServerLevel level) {
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        WeatherType chosen = WEATHER_TYPES[ThreadLocalRandom.current().nextInt(WEATHER_TYPES.length)];
        switch (chosen) {
            // Use large duration so vanilla MC never overrides before our next cycle
            case CLEAR -> {
                data.setRaining(false);
                data.setThundering(false);
                data.setClearWeatherTime(999_999);
                data.setRainTime(0);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → CLEAR.");
            }
            case RAIN -> {
                data.setRaining(true);
                data.setThundering(false);
                data.setClearWeatherTime(0);
                data.setRainTime(999_999);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → RAIN.");
            }
            case THUNDER -> {
                data.setRaining(true);
                data.setThundering(true);
                data.setClearWeatherTime(0);
                data.setRainTime(999_999);
                data.setThunderTime(999_999);
                LOGGER.info("[WeatherStructureMod] Weather → THUNDER.");
            }
        }
    }

    private static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }
}
