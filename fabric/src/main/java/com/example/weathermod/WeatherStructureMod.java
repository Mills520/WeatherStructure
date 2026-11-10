package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherCommands;
import com.example.weathermod.common.WeatherEngine;
import com.example.weathermod.common.WeatherMessages;
import com.example.weathermod.common.WeatherType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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

/**
 * Fabric edition for MC 26.1.x — mojang official mappings.
 * Yarn was retired after MC 1.21.11, so this build uses mojang names directly
 * (matching the NeoForge source style).
 */
public class WeatherStructureMod implements ModInitializer {

    public static final String MOD_ID = "weatherstructuremod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 24 hours — matches {@link WeatherEngine#MAX_TIMED_TICKS}. */
    private static final int MAX_SECONDS = WeatherCommands.MAX_TIMED_SECONDS;

    private final WeatherEngine engine = new WeatherEngine();

    // ── Per-level tick state ─────────────────────────────────────────────
    // The overworld is the only level this mod drives, and the level instance is
    // stable for the lifetime of a server. Caching the registry-key string, the
    // applier and the biome source against that instance keeps the tick handler
    // allocation-free: previously every one of the 20 ticks per second built a
    // fresh key String and two capturing lambdas.
    private ServerLevel                        trackedLevel;
    private String                             trackedKey;
    private WeatherEngine.WeatherApplier       trackedApplier;
    private WeatherEngine.BiomeCategorySource  trackedBiomeSource;

    @Override
    public void onInitialize() {
        LOGGER.info("[WeatherStructureMod] v1.8.0 — Fabric (MC 26.x) — Dynamic Weather & Structure Boost active.");
        ServerTickEvents.END_LEVEL_TICK.register(this::onWorldTick);
        // A client keeps this mod instance alive across world loads, so engine
        // state has to be dropped when the integrated server stops — otherwise a
        // timed override (or a half-elapsed cycle timer) leaks into the next world.
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        registerCommands();
    }

    private void onServerStopped(MinecraftServer server) {
        engine.reset();
        trackedLevel       = null;
        trackedKey         = null;
        trackedApplier     = null;
        trackedBiomeSource = null;
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
                            for (WeatherType t : WeatherType.cachedValues()) {
                                builder.suggest(t.getCommandName());
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                            .executes(ctx -> {
                                String type = StringArgumentType.getString(ctx, "type");
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
            source.sendFailure(Component.literal(WeatherMessages.INVALID_WEATHER_TYPE));
            return 0;
        }

        ServerLevel level = source.getServer().overworld();
        int held = engine.setTimedWeather(weatherType, WeatherCommands.secondsToTicks(seconds),
            (wt, duration) -> applyWeatherType(level, wt, duration)
        );

        int heldSeconds = held / WeatherCommands.TICKS_PER_SECOND;
        String message = WeatherMessages.timedWeatherSet(weatherType, heldSeconds);
        source.sendSuccess(() -> Component.literal(message), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, heldSeconds);
        return 1;
    }

    private int executeTimedWeatherStatus(CommandSourceStack source) {
        // Built now, not inside the supplier: the reply must describe the engine
        // as it was when the command ran.
        String message = WeatherMessages.timedWeatherStatus(engine);
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private int executeWeatherForecast(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        String key = level.dimension().identifier().toString();
        // The biome is only resolved when no timed override is running.
        String message = WeatherMessages.forecast(engine, key, () -> getSpawnBiomeCategory(level));
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    // ── Tick handler ─────────────────────────────────────────────────────

    private void onWorldTick(ServerLevel level) {
        if (!Level.OVERWORLD.equals(level.dimension())) return;

        if (level != trackedLevel) {
            trackedLevel       = level;
            trackedKey         = level.dimension().identifier().toString();
            trackedApplier     = (type, duration) -> applyWeatherType(level, type, duration);
            trackedBiomeSource = () -> getSpawnBiomeCategory(level);
        }

        // The overworld is the only level driven here, so this handler runs
        // exactly once per server tick — which is what the global timed-override
        // countdown requires. If this mod ever drives more dimensions, move the
        // tickTimedOverride call to ServerTickEvents.END_SERVER_TICK.
        if (engine.tickTimedOverride(trackedApplier)) {
            LOGGER.info("[WeatherStructureMod] Timed weather expired → CLEAR.");
            return;
        }

        WeatherType changed = engine.tickWorld(trackedKey, trackedBiomeSource, trackedApplier);
        if (changed != null) {
            LOGGER.info("[WeatherStructureMod] Weather → {}.", changed);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    // Set weather by dispatching the vanilla `/weather` command. We tried
    // `ServerLevel.setWeatherParameters(int,int,boolean,boolean)` and the
    // `ServerLevelData` setters first; both got removed or renamed in MC
    // 26.x and the new internal signature is unclear without source access.
    // The `/weather` command is a stable public surface that's existed
    // since pre-1.16, so dispatching it works regardless of how Mojang
    // refactors the internals. `withSuppressedOutput()` silences the
    // command's "Set the weather to ..." chat to ops.
    //
    // The command line itself comes from WeatherCommands.vanillaWeatherCommand,
    // which emits the duration in ticks with an explicit `t` suffix (the
    // argument is a `TimeArgument` whose default unit is ticks; passing
    // `duration / 20` once applied every weather 400x too short). It lives in
    // common/ so that unit is covered by a test.
    //
    // Note the source is anchored at the server overworld, which is also the
    // only level `onWorldTick` drives; extending this mod to other dimensions
    // means giving the source an explicit level.
    private void applyWeatherType(ServerLevel level, WeatherType type, int durationTicks) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput(),
            WeatherCommands.vanillaWeatherCommand(type, durationTicks)
        );
    }

    /**
     * Resolves the spawn biome's climate category. Called only when the engine
     * actually rolls new weather (once every 30–60 min), not every tick, so an
     * operator moving spawn with {@code /setworldspawn} is picked up instead of
     * being masked by a cache that never expires.
     */
    private BiomeCategory getSpawnBiomeCategory(ServerLevel level) {
        BlockPos spawn = level.getRespawnData().pos();
        Holder<Biome> biome = level.getBiome(spawn);
        String biomeId = biome.unwrapKey()
            .<String>map(k -> k.identifier().toString())
            .orElse("");
        return BiomeCategory.fromBiomeId(biomeId);
    }
}
