# Weather & Structure Mod — All Platforms 1.21.11

One repo, one command, four JARs.

| JAR | Platform | Install |
|-----|----------|---------|
| `fabric/build/libs/weather-structure-mod-fabric-1.4.0.jar` | Fabric 0.18+ | `mods/` |
| `neoforge/build/libs/weather-structure-mod-neoforge-1.4.0.jar` | NeoForge 21.11+ | `mods/` |
| `forge/build/libs/weather-structure-mod-forge-1.4.0.jar` | Forge 61.1+ | `mods/` |
| `paper/build/libs/weather-structure-mod-paper-1.4.0.jar` | Paper/Spigot 1.21.11 | `plugins/` |

---

## Build — one command

**Linux / macOS:**
```bash
chmod +x build-all.sh
./build-all.sh
```

**Windows:**
```bat
build-all.bat
```

The script handles both Gradle versions automatically.

---

## Why two Gradle versions?

| Subproject | Gradle | Reason |
|-----------|--------|--------|
| common | 9.2.0 | Shared Java library — no Minecraft deps |
| fabric | 9.2.0 | Fabric Loom 1.14 requires Gradle 9.2 |
| neoforge | 9.2.0 | ModDevGradle 2.x requires Gradle 9.2 |
| paper | 9.2.0 | Standard Java plugin, works anywhere |
| forge | 8.8 | ForgeGradle 6 only supports Gradle 8.x |

Fabric/NeoForge/Paper/Common share one Gradle 9.2 multi-project build.
Forge is a standalone Gradle 8.8 project in forge/ (includes common sources via `srcDirs`).
build-all.sh / build-all.bat runs both in sequence automatically.

---

## Features

1. **Dynamic Weather Cycling** — Randomly switches the Overworld between
   Clear, Rain, and Thunder every 30–60 minutes. Weather durations are set
   high so vanilla MC never overrides the mod's chosen weather before the
   next cycle.

2. **Biome-Aware Weather** *(new in v1.4.0)* — Weather probabilities are
   weighted by the biome at the world's spawn point:
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
   timer expires the weather automatically reverts to Clear. Normal weather
   cycling is paused while a timed weather is active.
   - **Syntax:** `/timedweather <clear|rain|thunder> <seconds>`
   - **Status:** `/timedweather status` *(new in v1.4.0)*
   - **Permission:** Operator level 2 (Fabric/Forge/NeoForge) or
     `weatherstructuremod.timedweather` (Paper, default: op)
   - **Duration:** 1–86,400 seconds (up to 24 hours)

5. **Weather Forecast Command** (`/weatherforecast`) *(new in v1.4.0)* —
   Shows the time until the next weather change and the spawn biome's
   climate category influencing probabilities.
   - **Syntax:** `/weatherforecast`
   - **Permission:** Operator level 2 (Fabric/Forge/NeoForge) or
     `weatherstructuremod.weatherforecast` (Paper, default: op)

---

## Architecture

### Shared Common Module (`common/`)
All platform-independent logic lives in `common/src/main/java`:

| Class | Purpose |
|-------|---------|
| `WeatherType` | Enum: CLEAR, RAIN, THUNDER with cached values and name lookup |
| `BiomeCategory` | Biome → climate mapping with weighted random weather selection |
| `WeatherEngine` | Core tick logic, timer management, timed weather state |

Platform modules depend on common and only contain platform-specific wiring
(event registration, commands, weather API calls, mixins/reflection).

### Dependency Versions
Dependency versions are centralized in Gradle version catalogs:
- `gradle/libs.versions.toml` — Fabric, NeoForge, Paper, testing
- `forge/gradle/libs.versions.toml` — Forge-specific versions

---

## Changelog

### v1.4.0
- **New: Biome-aware weather** — Weather probabilities are now weighted by
  the biome at the world's spawn point. Desert worlds see mostly clear skies,
  jungle worlds get more rain, etc.
- **New: `/weatherforecast` command** — Shows when the next weather change
  will occur and the spawn biome's climate influence.
- **New: `/timedweather status`** — Check the remaining time on active
  timed weather.
- **New: Shared common module** (`common/`) — Platform-independent weather
  engine, biome categories, and weather types extracted into a shared Java
  library. Reduces code duplication across all four platforms.
- **Optimization: Tick counter boxing** — Replaced `HashMap<K, Integer>` with
  `HashMap<K, int[]>` to avoid auto-boxing 20 times per second per world.
- **Optimization: Fabric overworld check** — Uses `RegistryKey` reference
  equality (`==`) instead of `.equals()` for the interned overworld key.
- **Optimization: Paper world identifiers** — Uses `World.getKey()` instead
  of `World.getName()` for stable, rename-safe world identification.
- **Optimization: Paper structure boost** — Tries targeted registry access
  before falling back to deep reflective sweep, reducing startup overhead.
- **Added: Gradle version catalogs** (`libs.versions.toml`) for centralized
  dependency version management.
- **Added: Unit tests** for `WeatherEngine`, `WeatherType`, and
  `BiomeCategory` via JUnit 5 in the common module.
- Thread-safety of `timedWeatherTicks` documented in `WeatherEngine` —
  confirmed safe because all access occurs on the server's main thread.

### v1.3.0
- **New: `/timedweather` command** — Set weather to clear, rain, or thunder
  for a specified duration (in seconds). Weather reverts to clear when the
  timer expires. Available on all four platforms.
- Normal weather cycling pauses while timed weather is active and resumes
  with a fresh random interval once the timer expires.
- Paper: command registered in plugin.yml with tab-completion and permission
  node `weatherstructuremod.timedweather`.

### v1.2.0
- Weather cycle interval changed from 5–15 min to 30–60 min for a more
  natural feel.
- Fixed a bug where vanilla MC could override the mod's weather before the
  next cycle (weather duration was only 5 min).
- Optimized per-tick HashMap lookups (single get + null check instead of
  containsKey + get).
- Precomputed interval range constant.
- Paper: reflection exceptions now logged at FINE level instead of silently
  swallowed.

### v1.1.0
- Initial multi-platform release (Fabric, Forge, NeoForge, Paper).

---

## License
Apache 2.0 - 2025 Mills520
