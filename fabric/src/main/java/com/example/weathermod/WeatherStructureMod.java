package com.example.weathermod;

import com.example.weathermod.common.BiomeCategory;
import com.example.weathermod.common.WeatherEngine;
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
    private static final int MAX_SECONDS = WeatherEngine.MAX_TIMED_TICKS / 20;

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
            source.sendFailure(Component.literal("Invalid weather type! Use: clear, rain, or thunder."));
            return 0;
        }

        ServerLevel level = source.getServer().overworld();
        int held = engine.setTimedWeather(weatherType, seconds * 20, (wt, duration) ->
            applyWeatherType(level, wt, duration)
        );

        int heldSeconds = held / 20;
        source.sendSuccess(() -> Component.literal(
            "[WSM] Weather set to " + weatherType.getCommandName() + " for " + heldSeconds
                + "s. Will revert to CLEAR after."
        ), true);
        LOGGER.info("[WeatherStructureMod] Timed weather: {} for {}s.", weatherType, heldSeconds);
        return 1;
    }

    private int executeTimedWeatherStatus(CommandSourceStack source) {
        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            WeatherType active = engine.getTimedWeatherType();
            source.sendSuccess(() -> Component.literal(
                "[WSM] Timed weather: " + active
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
        if (engine.isTimedWeatherActive()) {
            int remaining = engine.getTimedWeatherTicksRemaining();
            WeatherType active = engine.getTimedWeatherType();
            source.sendSuccess(() -> Component.literal(
                "[WSM] Timed weather active: " + active
                    + "\n  Remaining: " + WeatherEngine.formatTicks(remaining)
                    + " (" + remaining + " ticks)"
                    + "\n  Normal cycling resumes after timer expires."
            ), false);
            return 1;
        }

        ServerLevel level = source.getServer().overworld();
        String key = level.dimension().identifier().toString();
        int ticksLeft = engine.getTicksUntilNextChange(key);
        BiomeCategory category = getSpawnBiomeCategory(level);
        String forecast = ticksLeft > 0
            ? WeatherEngine.formatTicks(ticksLeft) + " (" + ticksLeft + " ticks)"
            : "imminent";

        source.sendSuccess(() -> Component.literal(
            "[WSM] Next weather change in ~" + forecast
                + "\n  Spawn biome influence: " + category.name()
        ), false);
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
    // The duration argument is a vanilla `TimeArgument`, whose *default unit is
    // ticks* — `/weather rain 60` means 60 ticks (3 seconds), not 60 seconds.
    // Passing `duration / 20` therefore applied every weather 400x too short:
    // a `/timedweather rain 60` cleared up after 3 seconds, and cycled weather
    // got 49,999 ticks instead of the ~13.8 h intended to outlast the cycle.
    // Emit the tick count with an explicit `t` suffix so the unit can't be
    // misread by us or by a future vanilla change.
    //
    // Note the source is anchored at the server overworld, which is also the
    // only level `onWorldTick` drives; extending this mod to other dimensions
    // means giving the source an explicit level.
    private void applyWeatherType(ServerLevel level, WeatherType type, int durationTicks) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        int ticks = Math.max(1, durationTicks);
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput(),
            "weather " + type.getCommandName() + " " + ticks + "t"
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
