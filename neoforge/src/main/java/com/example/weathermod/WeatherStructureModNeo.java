package com.example.weathermod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
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

    // Timed weather state — when active, pauses normal cycling
    private int timedWeatherTicks = 0;

    public WeatherStructureModNeo(IEventBus modBus) {
        LOGGER.info("[WeatherStructureMod] v1.3.0 — NeoForge — Dynamic Weather & Structure Boost active.");
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("timedweather")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        builder.suggest("clear");
                        builder.suggest("rain");
                        builder.suggest("thunder");
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                        .executes(ctx -> {
                            String type = StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT);
                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            return executeTimedWeather(ctx.getSource(), type, seconds);
                        })
                    )
                )
        );
    }

    private int executeTimedWeather(CommandSourceStack source, String type, int seconds) {
        WeatherType weatherType;
        try {
            weatherType = WeatherType.valueOf(type);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid weather type! Use: clear, rain, or thunder."));
            return 0;
        }

        ServerLevel level = source.getServer().overworld();
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        int ticks = seconds * 20;

        applyWeatherType(data, weatherType, ticks);
        timedWeatherTicks = ticks;

        source.sendSuccess(() -> Component.literal(
            "[WSM] Weather set to " + weatherType.name() + " for " + seconds + "s. Will revert to CLEAR after."
        ), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, seconds);
        return 1;
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        // Handle timed weather countdown
        if (timedWeatherTicks > 0) {
            timedWeatherTicks--;
            if (timedWeatherTicks <= 0) {
                ServerLevelData data = (ServerLevelData) level.getLevelData();
                applyWeatherType(data, WeatherType.CLEAR, 999_999);
                LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
                weatherTimers.put(level.dimension(), randomInterval());
            }
            return;
        }

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
        applyWeatherType(data, chosen, 999_999);
        LOGGER.info("[WeatherStructureMod] Weather → {}.", chosen);
    }

    private void applyWeatherType(ServerLevelData data, WeatherType type, int duration) {
        switch (type) {
            case CLEAR -> {
                data.setRaining(false);
                data.setThundering(false);
                data.setClearWeatherTime(duration);
                data.setRainTime(0);
                data.setThunderTime(0);
            }
            case RAIN -> {
                data.setRaining(true);
                data.setThundering(false);
                data.setClearWeatherTime(0);
                data.setRainTime(duration);
                data.setThunderTime(0);
            }
            case THUNDER -> {
                data.setRaining(true);
                data.setThundering(true);
                data.setClearWeatherTime(0);
                data.setRainTime(duration);
                data.setThunderTime(duration);
            }
        }
    }

    private static int randomInterval() {
        return MIN_TICKS + ThreadLocalRandom.current().nextInt(INTERVAL_RANGE);
    }
}
