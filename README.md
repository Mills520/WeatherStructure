# Weather & Structure Mod — All Platforms

Compatible with **Minecraft 26.1 – 26.1.2** on Fabric, NeoForge, Paper,
and Spigot. One repo, one command, three JARs.

| JAR | Platform | Install |
|-----|----------|---------|
| `fabric/build/libs/weather-structure-mod-fabric-1.6.0.jar` | Fabric 0.19+ on MC 26.1 – 26.1.2 | `mods/` |
| `neoforge/build/libs/weather-structure-mod-neoforge-1.6.0.jar` | NeoForge 26.1+ on MC 26.1 – 26.1.2 | `mods/` |
| `paper/build/libs/weather-structure-mod-paper-1.6.0.jar` | Paper/Spigot 26.1 – 26.1.2 | `plugins/` |

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
| `WeatherType` | Enum: CLEAR, RAIN, THUNDER with cached values and name lookup |
| `BiomeCategory` | Biome → climate mapping with weighted random weather selection |
| `WeatherEngine` | Core tick logic, timer management, timed weather state |

Platform modules depend on `common` and only contain platform-specific
wiring (event registration, commands, weather API calls, mixins,
reflection).

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

3. **Structure Spawn Boost** — ~15% more villages, mansions, outposts
   and other structures in newly generated chunks.

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
   influencing probabilities.
   - **Syntax:** `/weatherforecast`
   - **Permission:** Operator level 2 (Fabric/NeoForge) or
     `weatherstructuremod.weatherforecast` (Paper, default: op)

---

## Changelog

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
