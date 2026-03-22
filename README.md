# Weather & Structure Mod — All Platforms 1.21.11

One repo, one command, four JARs.

| JAR | Platform | Install |
|-----|----------|---------|
| `fabric/build/libs/weather-structure-mod-fabric-1.3.0.jar` | Fabric 0.18+ | `mods/` |
| `neoforge/build/libs/weather-structure-mod-neoforge-1.3.0.jar` | NeoForge 21.11+ | `mods/` |
| `forge/build/libs/weather-structure-mod-forge-1.3.0.jar` | Forge 61.1+ | `mods/` |
| `paper/build/libs/weather-structure-mod-paper-1.3.0.jar` | Paper/Spigot 1.21.11 | `plugins/` |

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
| fabric | 9.2.0 | Fabric Loom 1.14 requires Gradle 9.2 |
| neoforge | 9.2.0 | ModDevGradle 2.x requires Gradle 9.2 |
| paper | 9.2.0 | Standard Java plugin, works anywhere |
| forge | 8.8 | ForgeGradle 6 only supports Gradle 8.x |

Fabric/NeoForge/Paper share one Gradle 9.2 multi-project build.
Forge is a standalone Gradle 8.8 project in forge/.
build-all.sh / build-all.bat runs both in sequence automatically.

---

## Features

1. **Dynamic Weather Cycling** — Randomly switches the Overworld between
   Clear, Rain, and Thunder every 30–60 minutes. Weather durations are set
   high so vanilla MC never overrides the mod's chosen weather before the
   next cycle.
2. **Structure Spawn Boost** — ~15% more villages, mansions, outposts
   and other structures in newly generated chunks.
3. **Timed Weather Command** (`/timedweather`) — Set the weather to
   Clear, Rain, or Thunder for a specified number of seconds. Once the
   timer expires the weather automatically reverts to Clear. Normal weather
   cycling is paused while a timed weather is active.
   - **Syntax:** `/timedweather <clear|rain|thunder> <seconds>`
   - **Permission:** Operator level 2 (Fabric/Forge/NeoForge) or
     `weatherstructuremod.timedweather` (Paper, default: op)
   - **Duration:** 1–86,400 seconds (up to 24 hours)

---

## Changelog

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
