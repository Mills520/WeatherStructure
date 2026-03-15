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
import java.util.Random;

@Mod("weatherstructuremod")
public class WeatherStructureModNeo {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private enum WeatherType { CLEAR, RAIN, THUNDER }

    private final Map<ResourceKey<Level>, Integer> weatherTimers = new HashMap<>();
    private final Random random = new Random();

    private static final int MIN_TICKS = 5  * 60 * 20;
    private static final int MAX_TICKS = 15 * 60 * 20;

    public WeatherStructureModNeo(IEventBus modBus) {
        LOGGER.info("[WeatherStructureMod] NeoForge — Dynamic Weather & Structure Boost active.");
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        ResourceKey<Level> key = level.dimension();

        if (!weatherTimers.containsKey(key)) {
            int initial = randomInterval();
            weatherTimers.put(key, initial);
            LOGGER.info("[WeatherStructureMod] First weather change in {} ticks (~{} sec).", initial, initial / 20);
            return;
        }

        int ticksLeft = weatherTimers.get(key) - 1;
        if (ticksLeft <= 0) {
            applyRandomWeather(level);
            weatherTimers.put(key, randomInterval());
        } else {
            weatherTimers.put(key, ticksLeft);
        }
    }

    private void applyRandomWeather(ServerLevel level) {
        // getLevelData() returns WritableLevelData; cast to ServerLevelData for full access
        ServerLevelData data = (ServerLevelData) level.getLevelData();

        switch (WeatherType.values()[random.nextInt(3)]) {
            case CLEAR -> {
                data.setRaining(false);
                data.setThundering(false);
                data.setClearWeatherTime(6000);
                data.setRainTime(0);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → CLEAR.");
            }
            case RAIN -> {
                data.setRaining(true);
                data.setThundering(false);
                data.setClearWeatherTime(0);
                data.setRainTime(6000);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → RAIN.");
            }
            case THUNDER -> {
                data.setRaining(true);
                data.setThundering(true);
                data.setClearWeatherTime(0);
                data.setRainTime(6000);
                data.setThunderTime(6000);
                LOGGER.info("[WeatherStructureMod] Weather → THUNDER.");
            }
        }
    }

    private int randomInterval() {
        return MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS + 1);
    }
}
