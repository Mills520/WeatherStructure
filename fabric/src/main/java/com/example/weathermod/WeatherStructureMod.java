package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class WeatherStructureMod implements ModInitializer {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final WeatherEngine engine = new WeatherEngine();

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] v1.4.0 — Fabric — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
        registerCommands();
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /timedweather <type> <seconds>
            dispatcher.register(
                CommandManager.literal("timedweather")
                    .requires(source -> source.hasPermission(2))
                    .then(CommandManager.literal("status")
                        .executes(ctx -> executeTimedWeatherStatus(ctx.getSource()))
                    )
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
            );

            // /weatherforecast
            dispatcher.register(
                CommandManager.literal("weatherforecast")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> executeWeatherForecast(ctx.getSource()))
            );
        });
    }

    private int executeTimedWeather(ServerCommandSource source, String type, int seconds) {
        WeatherType weatherType = WeatherType.fromName(type);
        if (weatherType == null) {
            source.sendError(Text.literal("Invalid weather type! Use: clear, rain, or thunder."));
            return 0;
        }

        ServerWorld world = source.getServer().getOverworld();
        int ticks = seconds * 20;

        engine.setTimedWeather(weatherType, ticks, (wt, duration) -> {
            switch (wt) {
                case CLEAR   -> world.setWeather(duration, 0,        false, false);
                case RAIN    -> world.setWeather(0,        duration, true,  false);
                case THUNDER -> world.setWeather(0,        duration, true,  true);
            }
        });

        source.sendFeedback(() -> Text.literal(
            "[WSM] Weather set to " + weatherType.name() + " for " + seconds + "s. Will revert to CLEAR after."
        ), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, seconds);
        return 1;
    }

    private int executeTimedWeatherStatus(ServerCommandSource source) {
        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            source.sendFeedback(() -> Text.literal(
                "[WSM] Timed weather: " + engine.getTimedWeatherType()
                    + " — " + WeatherEngine.formatTicks(remaining)
                    + " remaining (" + remaining + " ticks)"
            ), false);
        } else {
            source.sendFeedback(() -> Text.literal(
                "[WSM] No timed weather active. Normal cycling is running."
            ), false);
        }
        return 1;
    }

    private int executeWeatherForecast(ServerCommandSource source) {
        ServerWorld world = source.getServer().getOverworld();
        String key = world.getRegistryKey().getValue().toString();

        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            source.sendFeedback(() -> Text.literal(
                "[WSM] Timed weather active: " + engine.getTimedWeatherType()
                    + "\n  Remaining: " + WeatherEngine.formatTicks(remaining)
                    + " (" + remaining + " ticks)"
                    + "\n  Normal cycling resumes after timer expires."
            ), false);
        } else {
            int ticksLeft = engine.getTicksUntilNextChange(key);
            BiomeCategory category = getSpawnBiomeCategory(world);
            String forecast = ticksLeft > 0
                ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
                : "imminent";

            source.sendFeedback(() -> Text.literal(
                "[WSM] Next weather change in ~" + forecast
                    + "\n  Spawn biome influence: " + category.name()
            ), false);
        }
        return 1;
    }

    // ── Tick handler ─────────────────────────────────────────────────────

    private void onWorldTick(ServerWorld world) {
        // Reference equality works because RegistryKey uses an interner (#12)
        if (world.getRegistryKey() != World.OVERWORLD) return;

        String key = world.getRegistryKey().getValue().toString();
        BiomeCategory biomeCategory = getSpawnBiomeCategory(world);

        WeatherType changed = engine.tick(key, biomeCategory, (type, duration) -> {
            switch (type) {
                case CLEAR   -> world.setWeather(duration, 0,        false, false);
                case RAIN    -> world.setWeather(0,        duration, true,  false);
                case THUNDER -> world.setWeather(0,        duration, true,  true);
            }
        });

        if (changed != null) {
            if (engine.isTimedWeatherActive()) {
                LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
            } else {
                LOGGER.info("[WeatherStructureMod] Weather → {}.", changed);
            }
        }
    }

    private BiomeCategory getSpawnBiomeCategory(ServerWorld world) {
        BlockPos spawn = world.getSpawnPoint().getPos();
        RegistryEntry<Biome> biome = world.getBiome(spawn);
        String biomeId = biome.getKey()
            .map(k -> k.getValue().toString())
            .orElse("");
        return BiomeCategory.fromBiomeId(biomeId);
    }
}
