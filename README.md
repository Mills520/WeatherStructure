# Weather & Structure Mod — Multi-Platform 1.21.11

One source tree, three JARs built with a single `./gradlew build`.

| JAR | Platform | Install location |
|-----|----------|-----------------|
| `weather-structure-mod-fabric-1.0.0.jar` | Fabric 0.18+ | `mods/` |
| `weather-structure-mod-neoforge-1.0.0.jar` | NeoForge 21.11+ | `mods/` |
| `weather-structure-mod-paper-1.0.0.jar` | Paper 1.21.11 | `plugins/` |

---

## Features

1. **Dynamic Weather Cycling** — Randomly switches the Overworld between
   Clear, Rain, and Thunder every **5–15 minutes**.
2. **Structure Spawn Boost** — ~**15% more** villages, mansions, outposts,
   temples, and other naturally-generated structures in new chunks.

---

## Building

```bash
./gradlew build
```

JARs are output to:
```
fabric/build/libs/weather-structure-mod-fabric-1.0.0.jar
neoforge/build/libs/weather-structure-mod-neoforge-1.0.0.jar
paper/build/libs/weather-structure-mod-paper-1.0.0.jar
```

### Requirements
- Java 21
- Internet access (Gradle downloads dependencies automatically)

---

## Version Pins (gradle.properties)

| Property | Value | Notes |
|----------|-------|-------|
| `fabric_loom` | `1.14-SNAPSHOT` | Fabric build toolchain |
| `yarn_mappings` | `1.21.11+build.3` | **Final Yarn release** (1.21.11 is last Yarn version) |
| `fabric_loader` | `0.18.4` | |
| `fabric_api` | `0.141.3+1.21.11` | |
| `neoforge_version` | `21.11.29-beta` | Update to latest at projects.neoforged.net |
| `moddevgradle_version` | `2.0.150` | NeoForge build toolchain |
| `paper_api` | `1.21.11-R0.1-SNAPSHOT` | |

---

## Platform Notes

### Fabric
- Uses Yarn mappings + Fabric events + Mixin for both features.

### NeoForge
- Uses Mojang mappings + NeoForge `LevelTickEvent.Post` for weather.
- Uses Mixin on `RandomSpreadStructurePlacement` for structure boost.
- NeoForge 21.11 is the successor to legacy Forge for 1.21.11.

### Paper (Bukkit Plugin)
- Weather cycling uses the Bukkit scheduler + `World#setStorm()` API.
- Structure boost uses reflection on NMS internals at server startup.
  Works on vanilla Paper servers. Custom world generators may not be affected.
- Install in `plugins/` **not** `mods/`.
- Does **not** require Fabric or NeoForge.

---

## License

Apache 2.0 — © 2025 Mills520
