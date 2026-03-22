package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.ServerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

@Mod("weatherstructuremod")
public class WeatherStructureModNeo {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final WeatherEngine engine = new WeatherEngine();

    public WeatherStructureModNeo(IEventBus modBus) {
        LOGGER.info("[WeatherStructureMod] v1.4.0 — NeoForge — Dynamic Weather & Structure Boost active.");
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    // ── Commands ──────────────────────────────────────────────────────────

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("timedweather")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                    .executes(ctx -> executeTimedWeatherStatus(ctx.getSource()))
                )
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

        event.getDispatcher().register(
            Commands.literal("weatherforecast")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> executeWeatherForecast(ctx.getSource()))
        );
    }

    private int executeTimedWeather(CommandSourceStack source, String type, int seconds) {
        WeatherType weatherType = WeatherType.fromName(type);
        if (weatherType == null) {
            source.sendFailure(Component.literal("Invalid weather type! Use: clear, rain, or thunder."));
            return 0;
        }

        ServerLevel level = source.getServer().overworld();
        int ticks = seconds * 20;

        engine.setTimedWeather(weatherType, ticks, (wt, duration) ->
            applyWeatherType((ServerLevelData) level.getLevelData(), wt, duration)
        );

        source.sendSuccess(() -> Component.literal(
            "[WSM] Weather set to " + weatherType.name() + " for " + seconds + "s. Will revert to CLEAR after."
        ), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, seconds);
        return 1;
    }

    private int executeTimedWeatherStatus(CommandSourceStack source) {
        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            source.sendSuccess(() -> Component.literal(
                "[WSM] Timed weather: " + engine.getTimedWeatherType()
                    + " — " + WeatherEngine.formatTicks(remaining)
                    + " remaining (" + remaining + " ticks)"
            ), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                "[WSM] No timed weather active. Normal cycling is running."
            ), false);
        }
        return 1;
    }

    private int executeWeatherForecast(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        String key = level.dimension().location().toString();

        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            source.sendSuccess(() -> Component.literal(
                "[WSM] Timed weather active: " + engine.getTimedWeatherType()
                    + "\n  Remaining: " + WeatherEngine.formatTicks(remaining)
                    + " (" + remaining + " ticks)"
                    + "\n  Normal cycling resumes after timer expires."
            ), false);
        } else {
            int ticksLeft = engine.getTicksUntilNextChange(key);
            BiomeCategory category = getSpawnBiomeCategory(level);
            String forecast = ticksLeft > 0
                ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
                : "imminent";

            source.sendSuccess(() -> Component.literal(
                "[WSM] Next weather change in ~" + forecast
                    + "\n  Spawn biome influence: " + category.name()
            ), false);
        }
        return 1;
    }

    // ── Tick handler ─────────────────────────────────────────────────────

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        String key = level.dimension().location().toString();
        BiomeCategory biomeCategory = getSpawnBiomeCategory(level);

        WeatherType changed = engine.tick(key, biomeCategory, (type, duration) ->
            applyWeatherType((ServerLevelData) level.getLevelData(), type, duration)
        );

        if (changed != null) {
            if (engine.isTimedWeatherActive()) {
                LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
            } else {
                LOGGER.info("[WeatherStructureMod] Weather → {}.", changed);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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

    private BiomeCategory getSpawnBiomeCategory(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        Holder<Biome> biome = level.getBiome(spawn);
        String biomeId = biome.unwrapKey()
            .map(k -> k.location().toString())
            .orElse("");
        return BiomeCategory.fromBiomeId(biomeId);
    }
}
