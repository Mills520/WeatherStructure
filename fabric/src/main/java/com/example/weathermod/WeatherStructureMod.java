package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fabric edition for MC 26.1.x — mojang official mappings.
 * Yarn was retired after MC 1.21.11, so this build uses mojang names directly
 * (matching the Forge / NeoForge source style).
 */
public class WeatherStructureMod implements ModInitializer {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final WeatherEngine engine = new WeatherEngine();
    private final Map<String, BiomeCategory> spawnBiomeCache = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] v1.7.0 — Fabric (MC 26.x) — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_LEVEL_TICK.register(this::onWorldTick);
        registerCommands();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("timedweather")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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

            dispatcher.register(
                Commands.literal("weatherforecast")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> executeWeatherForecast(ctx.getSource()))
            );
        });
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
            applyWeatherType(level, wt, duration)
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
        String key = level.dimension().identifier().toString();

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

    private void onWorldTick(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;

        String key = level.dimension().identifier().toString();
        BiomeCategory biomeCategory = spawnBiomeCache.computeIfAbsent(
            key, k -> getSpawnBiomeCategory(level));

        WeatherType changed = engine.tick(key, biomeCategory, (type, duration) ->
            applyWeatherType(level, type, duration)
        );

        if (changed != null) {
            if (engine.wasLastTickTimedExpiry()) {
                LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
            } else {
                LOGGER.info("[WeatherStructureMod] Weather → {}.", changed);
            }
        }
    }

    // Set weather by dispatching the vanilla `/weather` command. We tried
    // `ServerLevel.setWeatherParameters(int,int,boolean,boolean)` and the
    // `ServerLevelData` setters first; both got removed or renamed in MC
    // 26.x and the new internal signature is unclear without source access.
    // The `/weather` command is a stable public surface that's existed
    // since pre-1.16, so dispatching it works regardless of how Mojang
    // refactors the internals. `withSuppressedOutput()` silences the
    // command's "Set the weather to ..." chat to ops.
    private void applyWeatherType(ServerLevel level, WeatherType type, int duration) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        int seconds = Math.max(1, duration / 20);
        String cmd = switch (type) {
            case CLEAR   -> "weather clear "   + seconds;
            case RAIN    -> "weather rain "    + seconds;
            case THUNDER -> "weather thunder " + seconds;
        };
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput(),
            cmd
        );
    }

    private BiomeCategory getSpawnBiomeCategory(ServerLevel level) {
        BlockPos spawn = level.getRespawnData().pos();
        Holder<Biome> biome = level.getBiome(spawn);
        String biomeId = biome.unwrapKey()
            .<String>map(k -> k.identifier().toString())
            .orElse("");
        return BiomeCategory.fromBiomeId(biomeId);
    }
}
