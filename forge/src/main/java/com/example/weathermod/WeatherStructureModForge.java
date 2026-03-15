package com.example.weathermod;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Forge 61.x (1.21.11) — EventBus 7 style.
 *
 * EventBus 7 migration notes:
 *  - @SubscribeEvent + @Mod.EventBusSubscriber are GONE — replaced by addListener()
 *  - Register listeners directly on the event's own static .BUS field
 *  - Constructor takes FMLJavaModLoadingContext (injected by Forge 61+)
 *  - No external EventBus dep needed — events expose their own BUS field
 */
@Mod("weatherstructuremod")
public class WeatherStructureModForge {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final Map<ResourceKey<Level>, Integer> weatherTimers = new HashMap<>();
    private final Random random = new Random();

    private static final int MIN_TICKS = 5  * 60 * 20;
    private static final int MAX_TICKS = 15 * 60 * 20;

    // Forge 61.x: constructor must take FMLJavaModLoadingContext
    public WeatherStructureModForge(FMLJavaModLoadingContext context) {
        LOGGER.info("[WeatherStructureMod] Forge — Dynamic Weather & Structure Boost active.");

        // EventBus 7: register directly on the event's static BUS field — no @SubscribeEvent needed
        TickEvent.LevelTickEvent.Post.BUS.addListener(this::onLevelTick);
    }

    private void onLevelTick(TickEvent.LevelTickEvent.Post event) {
        // LevelTickEvent.Post is a record in EventBus 7 — access level via event.level()
        if (!(event.level() instanceof ServerLevel level)) return;
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
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        switch (random.nextInt(3)) {
            case 0 -> {
                data.setRaining(false);
                data.setThundering(false);
                data.setClearWeatherTime(6000);
                data.setRainTime(0);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → CLEAR.");
            }
            case 1 -> {
                data.setRaining(true);
                data.setThundering(false);
                data.setClearWeatherTime(0);
                data.setRainTime(6000);
                data.setThunderTime(0);
                LOGGER.info("[WeatherStructureMod] Weather → RAIN.");
            }
            case 2 -> {
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
