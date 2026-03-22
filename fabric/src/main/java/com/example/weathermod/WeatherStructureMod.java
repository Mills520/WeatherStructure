package com.example.weathermod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
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

    // Timed weather state — when active, pauses normal cycling
    private int timedWeatherTicks = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] v1.3.0 — Fabric — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
        registerTimedWeatherCommand();
    }

    private void registerTimedWeatherCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                CommandManager.literal("timedweather")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("clear");
                            builder.suggest("rain");
                            builder.suggest("thunder");
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 86400))
                            .executes(ctx -> {
                                String type = StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT);
                                int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                return executeTimedWeather(ctx.getSource(), type, seconds);
                            })
                        )
                    )
            )
        );
    }

    private int executeTimedWeather(ServerCommandSource source, String type, int seconds) {
        WeatherType weatherType;
        try {
            weatherType = WeatherType.valueOf(type);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Invalid weather type! Use: clear, rain, or thunder."));
            return 0;
        }

        ServerWorld world = source.getServer().getOverworld();
        int ticks = seconds * 20;

        switch (weatherType) {
            case CLEAR   -> world.setWeather(ticks, 0,     false, false);
            case RAIN    -> world.setWeather(0,     ticks, true,  false);
            case THUNDER -> world.setWeather(0,     ticks, true,  true);
        }

        timedWeatherTicks = ticks;

        source.sendFeedback(() -> Text.literal(
            "[WSM] Weather set to " + weatherType.name() + " for " + seconds + "s. Will revert to CLEAR after."
        ), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, seconds);
        return 1;
    }

    private void onWorldTick(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) return;

        // Handle timed weather countdown
        if (timedWeatherTicks > 0) {
            timedWeatherTicks--;
            if (timedWeatherTicks <= 0) {
                // Timer expired — revert to clear
                world.setWeather(999_999, 0, false, false);
                LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
                // Reset cycling timer so it doesn't change weather immediately
                String key = world.getRegistryKey().getValue().toString();
                weatherTimers.put(key, randomInterval());
            }
            return;  // Skip normal cycling while timed weather is active
        }

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
