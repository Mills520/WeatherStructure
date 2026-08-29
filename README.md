# Weather & Structure Mod — All Platforms

Compatible with **Minecraft 26.1 – 26.1.2** on Fabric, NeoForge, Paper,
and Spigot. One repo, one command, three JARs.

| JAR | Platform | Install |
|-----|----------|---------|
| `fabric/build/libs/weather-structure-mod-fabric-1.8.0.jar` | Fabric 0.19+ on MC 26.1 – 26.1.2 | `mods/` |
| `neoforge/build/libs/weather-structure-mod-neoforge-1.8.0.jar` | NeoForge 26.1+ on MC 26.1 – 26.1.2 | `mods/` |
| `paper/build/libs/weather-structure-mod-paper-1.8.0.jar` | Paper/Spigot 26.1 – 26.1.2 | `plugins/` |

> **MC version policy:** MC 26.x ships unobfuscated (yarn was retired
> after MC 1.21.11) and requires Java 25, which is a hard break from
> the 1.21.x line. This repo dropped 1.21.x support in v1.6.0. The last
> version that supported 1.21.x is v1.5.0 (git tag — and the historical
> jars for that release are still available from the release page).
> Forge has no 26.x release as of v1.6.0; if MinecraftForge ships one,
> a `forge/` subproject will be added back.

---

## Build — one command

Requires **Java 25** (MC 26.x enforces this at Loom / ModDevGradle
configuration). The script exits with a clear error if it can't find a
JDK 25 installation under the usual install locations
(`C:\Program Files\Eclipse Adoptium\jdk-25*`,
`/usr/lib/jvm/jdk-25*`, etc.) or in `JAVA_HOME`.

Install Java 25 from <https://adoptium.net> (or any vendor).

> **Pin the Gradle checksum.** This repo ships a small `gradlew` shim instead of
> a committed `gradle-wrapper.jar`. It refuses to install a distribution it
> can't verify, but the strong check needs `distributionSha256Sum` set in
> `gradle/wrapper/gradle-wrapper.properties` — take the value from
> <https://gradle.org/release-checksums/> and uncomment the line there. Without
> it the shim falls back to the publisher's `.sha256`, which catches corruption
> but not a compromised origin.

**Linux / macOS:**
```bash
chmod +x build-all.sh
./build-all.sh
```

**Windows:**
```bat
build-all.bat
```

---

## Architecture

### Shared Common Module (`common/`)
All platform-independent logic lives in `common/src/main/java`:

| Class | Purpose |
|-------|---------|
| `WeatherType` | Enum: CLEAR, RAIN, THUNDER with cached values, command tokens and case-insensitive name lookup |
| `BiomeCategory` | Biome → climate mapping with weighted random selection (weights always sum to 1.0; injectable RNG) |
| `WeatherEngine` | Core per-world tick logic, timer management, global timed-weather override, world lifecycle (`forget`/`reset`); optional seeded RNG for deterministic tests |

Platform modules depend on `common` and only contain platform-specific
wiring (event registration, commands, weather API calls, mixins,
reflection).

#### Driving the engine — two phases per server tick

The timed override is **global** while cycling timers are **per world**, so
each is advanced by its own call:

```java
// once per server tick, before any world is ticked:
if (engine.tickTimedOverride(applyClearEverywhere)) {
    log("timed weather expired");
}
// then once per world:
for (World w : worlds) {
    WeatherType changed = engine.tickWorld(w.key(), w.biomeSource(), w.applier());
}
```

`tickWorld` takes a `BiomeCategorySource` and only calls it on the tick a new
weather is actually rolled, so the spawn biome is resolved about twice an hour
instead of 20 times a second — and an operator's `/setworldspawn` is picked up
rather than masked by a cache.

The older single-call `tick(worldKey, category, applier)` folded both phases
together and is deprecated: it counted the override down once *per world*, so
`/timedweather rain 60` finished in 20 seconds on a three-world server.

The engine has no Minecraft dependencies and is covered by an extensive
JUnit suite. It compiles to **Java 21** (the platform modules target Java
25), so the core can be built and tested without the full Java-25 /
Minecraft toolchain:

```bash
./gradlew :common:test
```

### Mappings note for `fabric/`

Yarn was retired after MC 1.21.11 and Mojang stopped publishing
`client_mappings` for unobfuscated versions, so neither
`loom.officialMojangMappings()` nor a plain `mappings "net.fabricmc:yarn:..."`
declaration works. `fabric/build.gradle` synthesizes a 3-namespace
intermediary jar at build time (intermediary + official + named, where
`named == official` because MC is unobfuscated) and stages it in a
local maven repo at `$GRADLE_USER_HOME/wsm-local-maven` so Loom's
internal resolution picks it up. See the comments in
`fabric/build.gradle` for the full rationale.

---

## Features

1. **Dynamic Weather Cycling** — Randomly switches the Overworld between
   Clear, Rain, and Thunder every 30–60 minutes. Weather durations are
   set high so vanilla MC never overrides the mod's chosen weather
   before the next cycle.

2. **Biome-Aware Weather** — Weather probabilities are weighted by the
   biome at the world's spawn point:

   | Category | Biomes | Clear | Rain | Thunder |
   |----------|--------|-------|------|---------|
   | DRY | Desert, Badlands, Savanna | 60% | 25% | 15% |
   | TEMPERATE | Plains, Forest, most others | 33% | 33% | 33% |
   | WET | Jungle, Swamp, Mushroom | 20% | 50% | 30% |
   | COLD | Snowy, Frozen, Ice Spikes | 30% | 40% | 30% |

3. **Structure Spawn Boost** — villages, mansions, outposts and other
   `RandomSpreadStructurePlacement` structures generate closer together in
   newly generated chunks. Spacing and separation are each scaled to 0.87 of
   their vanilla value; because placements are laid out one per
   `spacing × spacing` chunk cell, that works out to roughly `1 / 0.87²` ≈
   **1.3× the structure density**, not the "+15%" quoted before v1.8.0.
   Values already at the floor are left alone rather than rounded up.

4. **Timed Weather Command** (`/timedweather`) — Set the weather to
   Clear, Rain, or Thunder for a specified number of seconds. Once the
   timer expires the weather automatically reverts to Clear. Normal
   weather cycling is paused while a timed weather is active.
   - **Syntax:** `/timedweather <clear|rain|thunder> <seconds>`
   - **Status:** `/timedweather status`
   - **Permission:** Operator level 2 (Fabric/NeoForge) or
     `weatherstructuremod.timedweather` (Paper, default: op)
   - **Duration:** 1–86,400 seconds (up to 24 hours)

5. **Weather Forecast Command** (`/weatherforecast`) — Shows the time
   until the next weather change and the spawn biome's climate category
   influencing probabilities. On Paper this reports every managed world, not
   just the first.
   - **Syntax:** `/weatherforecast`
   - **Permission:** Operator level 2 (Fabric/NeoForge) or
     `weatherstructuremod.weatherforecast` (Paper, default: op)

---

## Changelog

### v1.8.0

Full review pass: bug fixes, security hardening and tick-path optimizations.

#### Bug fixes

- **Fabric / NeoForge: every weather duration was applied 400× too short.**
  Weather is set by dispatching vanilla `/weather <type> <duration>`, whose
  duration argument is a `TimeArgument` — its *default unit is ticks*, not
  seconds. The code passed `durationTicks / 20`, so `/timedweather rain 60`
  visibly cleared after 3 seconds and cycled weather got 49,999 ticks (~41 min)
  instead of the ~13.8 h meant to outlast the cycle, letting vanilla revert the
  mod's weather mid-cycle. Durations are now emitted with an explicit `t`
  suffix.
- **Timed weather expired N× too fast with N worlds.** `WeatherEngine.tick()`
  advanced the *global* override counter once per world per server tick.
  Split into `tickTimedOverride()` (once per server tick) and `tickWorld()`
  (per world); `tick()` is kept and deprecated. Expiry now re-arms every
  tracked world's cycling timer, not only the world that happened to drive the
  countdown.
- **Paper: the structure boost compounded.** The registry sweep ran once per
  `NORMAL` world, but the `StructureSet` registry is server-global, so the same
  placement objects were shrunk once per world (0.87ⁿ). It now runs a single
  pass with an identity guard, and a JVM-wide marker stops `/reload` from
  compounding it again.
- **Structure boost could make structures rarer.** The floors were applied as
  `max(MIN, scaled)`, which *raised* spacing 1 → 2 and separation 0 → 1 on
  placements already at the floor. Clamps now only ever reduce.
- **Mixin could apply twice.** `@Inject(method = "<init>")` matches every
  constructor, so a delegating constructor would compound the boost; guarded
  with a `@Unique` flag.
- **NeoForge: the mixin was never registered.** `neoforge.mods.toml` had no
  `[[mixins]]` block — the `MixinConfigs` jar-manifest attribute is the legacy
  Forge mechanism, which NeoForge ignores. So the NeoForge build shipped with
  no structure boost at all.
- **Paper: reflection failures could take weather cycling down with them.**
  `boostStructureDensity()` caught only `Exception`, so a `NoSuchMethodError` or
  `ExceptionInInitializerError` from NMS reflection aborted `onEnable()`.
- **Paper: the targeted registry path never worked on modern NMS.**
  `registryOrThrow` was renamed to `lookupOrThrow`, so the clean path silently
  failed and always fell through to the reflective sweep. Candidate names are
  now tried in turn.
- **`build-all.sh` / `.bat` could not find an installed JDK 25.** `java_major`
  read the first line of `java -version`, which is a `Picked up
  JAVA_TOOL_OPTIONS: …` notice whenever `JAVA_TOOL_OPTIONS` or `_JAVA_OPTIONS`
  is set — so the scripts reported "Could not find a Java 25 installation" on
  machines that had one. `build-all.bat` also invoked `gradlew.bat` before
  checking it exists.
- **`build-all.sh` printed raw escape codes** (`\033[0;32m`) under any `/bin/sh`
  whose `echo` does not expand backslashes, e.g. bash. Uses `printf`, and only
  colours when stdout is a TTY.
- Engine state leaked between worlds in single player: Fabric/NeoForge now
  `reset()` on server stop. Paper `forget()`s unloaded worlds instead of
  growing its timer map for the life of the server.
- `formatTicks` now promotes to hours, so a 24 h timer reads `24h` rather than
  `1440m`.
- `getTicksUntilNextChange(null)` and `forget(null)` no longer throw.

#### Security

- **`gradlew` / `gradlew.bat` downloaded and executed Gradle with no integrity
  check whatsoever.** Anything that could answer for that download got code
  execution as the building user. The shims now read the URL from
  `gradle-wrapper.properties`, refuse non-HTTPS URLs and non-allowlisted hosts
  (override with `WSM_GRADLE_ALLOWED_HOSTS`), verify SHA-256 against
  `distributionSha256Sum` when pinned — **please pin it**, see the comments in
  that file — otherwise verify against the publisher's `.sha256` and print the
  digest, **fail closed** when no digest can be obtained, and install by
  directory swap so an interrupted run can't leave a half-extracted
  distribution the next run trusts.
- **Paper: the reflective fallback sweep was far too broad.** It walked into
  `java.util` and `java.lang.ref` calling `setAccessible(true)` on JDK
  internals — which throws on a modern JVM, can force lazily-initialised state
  to materialise with side effects, and touched `Reference` internals. It is now
  bounded (depth 6, 20,000 objects), traverses containers through
  `Map`/`Iterable`/array interfaces instead of reflecting into them, reflects
  fields only on `net.minecraft` / `com.mojang` classes, and skips
  `Reference`/`ClassLoader`/`Thread`/`Class`.
- **Paper: user input was echoed to chat unsanitized.** A crafted
  `/timedweather clear <arg>` could inject `§` formatting codes and control
  characters into the plugin's own reply; echoed tokens are now stripped and
  truncated.
- **Paper: commands are pinned to the main thread.** `WeatherEngine` is
  single-threaded by design and nothing stops another plugin dispatching a
  command asynchronously, which would race the tick loop and corrupt the
  override counter. `onCommand` now hops onto the main thread instead of
  trusting the caller.
- **Paper: tab completion is permission-checked**, so senders who cannot run
  `/timedweather` no longer get its argument shape suggested.
- `WeatherEngine.setTimedWeather` clamps to `MAX_TIMED_TICKS` (24 h), so a
  caller that skips its own validation cannot pause cycling for weeks.
- CI runs with `permissions: contents: read` and `persist-credentials: false`,
  so no build step — including third-party Gradle plugins — sees a token it
  doesn't need.

#### Performance

- **Paper's tick loop is allocation-free.** It previously built a
  `world.getKey().toString()` and two capturing lambdas *per world per tick* —
  ~60 objects a second on a three-world server, to service a timer that fires
  twice an hour. The key, applier and biome source are resolved once per world
  into a `TrackedWorld` and the loop indexes an array.
- **The spawn biome is resolved when weather changes, not on a timer.** Paper
  polled `Bukkit.getWorlds()` and re-resolved every spawn biome every 30
  seconds; world tracking is now driven by `WorldLoadEvent`/`WorldUnloadEvent`
  with a 5-minute safety net, and `BiomeCategorySource` defers the lookup to the
  ~2 changes an hour that need it. That also removes the stale-cache bug where
  `/setworldspawn` never took effect.
- Fabric/NeoForge cache their key, applier and biome source against the level
  instance, removing the same per-tick String and lambda allocations.
- The structure boost no longer rescans the object graph once per world.

#### Other

- Tests: 57 → 75 cases, covering the two-phase tick API, lazy biome
  resolution, duration clamping, expiry re-arming every world, a throwing
  applier not wedging the timer, and the deprecated `tick()` path.
- `common` compiles with `-Xlint:all` warning-free.
- Docs: the structure boost is described as the ~1.3× density it actually is
  instead of "+15%".

> **Not verified on a live server.** `common` is fully tested and the Paper
> plugin was type-checked against a stub Bukkit API, but the Fabric, NeoForge
> and Paper JARs need the Java 25 / Minecraft toolchain and mod mavens, which
> were not reachable from the environment these changes were made in. The
> `/weather` duration-unit fix and the NeoForge `[[mixins]]` registration in
> particular deserve a smoke test in game.

### v1.7.0
- **Engine rewrite.** `common/` (`WeatherEngine`, `BiomeCategory`,
  `WeatherType`) was rewritten from scratch — same behaviour and public
  API, cleaner internals, fuller docs. The tick path is split into focused
  helpers and the per-tick `int[]` timer reuse / no-auto-boxing
  optimizations are preserved.
- **Hardening / bug fixes:**
  - `WeatherEngine.setTimedWeather` now validates its arguments and clamps
    non-positive durations to 1 tick (previously a 0/negative duration
    applied weather but armed no timer, so it never reverted to clear).
  - `WeatherEngine.formatTicks` no longer emits negative strings (e.g.
    `"-1s"`); negative inputs format as `"0s"`.
  - `tick`/`setTimedWeather`/`weightedRandom` fail fast on `null` arguments.
  - **Paper:** the plugin now loads `POSTWORLD` instead of `STARTUP`. As a
    `STARTUP` plugin, `onEnable()` ran before any world loaded, so the
    reflective structure-density boost saw an empty `Bukkit.getWorlds()`
    and silently did nothing. *(Behavioural fix — verify on a live Paper
    server; the platform JARs require the Java 25 / Minecraft toolchain.)*
- **Testability:** `WeatherEngine` and `BiomeCategory` accept an injectable
  `RandomGenerator`, enabling deterministic tests. New `forget(worldKey)`
  and `reset()` lifecycle methods, `WeatherType.getCommandName()`, and
  `BiomeCategory` weight accessors.
- **Tests:** the `common` suite grew from 22 to 57 cases (validation,
  boundary/distribution checks with seeded RNG, multi-world cycling, timed
  override lifecycle, formatting edge cases). All green under
  `./gradlew :common:test`.

### v1.6.0
- **Dropped MC 1.21.x support.** The dual-build approach in v1.5.0 was
  too brittle to maintain; this release ships only MC 26.x JARs. The
  v1.5.0 release jars remain available for users still on 1.21.x.
- **Dropped Forge.** MinecraftForge has no public release for MC 26.x
  yet. NeoForge is the de-facto replacement for MC 1.20+ Forge users
  and ships in this release. A `forge/` subproject will be re-added if
  MinecraftForge ships for 26.x.
- All subprojects now compile under Java 25 against MC 26.1.2 with
  mojang names; the `-26x` suffix on subproject and jar names is gone.

### v1.5.0
- Dual-line release adding parallel MC 26.x JARs alongside 1.21.x JARs
  (now removed in v1.6.0). Same `WeatherEngine` core.
- **Fix:** unreachable "Timed weather expired → CLEAR" log path on
  Fabric/Forge/NeoForge.
- **Fix:** `build-all.{sh,bat}` success messages now read `mod_version`
  from `gradle.properties` instead of hard-coding `1.2.0`.
- **Fix:** added `junit-platform-launcher` as `testRuntimeOnly` so
  `./gradlew :common:test` actually runs the suite under Gradle 9.
- **Optimization:** spawn-biome category cached by registry key on
  Fabric/Forge/NeoForge (Paper already cached this).
- **Optimization:** per-world `int[1]` countdown arrays reused on timed
  weather expiry.

### v1.4.0
- **New:** biome-aware weather, `/weatherforecast` command,
  `/timedweather status` subcommand.
- **New:** shared `common/` Java library — `WeatherEngine`,
  `BiomeCategory`, `WeatherType`.
- **Optimization:** removed `HashMap<K, Integer>` auto-boxing in tick
  counters; reference-equality dimension check on Fabric; targeted
  registry access on Paper structure boost; `World.getKey()` for stable
  world IDs on Paper.

### v1.3.0
- **New:** `/timedweather` command. Normal cycling pauses while timed
  weather is active.

### v1.2.0
- Weather cycle interval bumped from 5–15 min to 30–60 min; vanilla MC
  no longer overrides the mod's weather mid-cycle.

### v1.1.0
- Initial multi-platform release (Fabric, Forge, NeoForge, Paper).

---

## License
Apache 2.0 — © 2025–2026 Mills520
