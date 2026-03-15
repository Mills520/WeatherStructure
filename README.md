<<<<<<< HEAD
# Weather & Structure Mod — Fabric 1.21.1

A Fabric mod for Minecraft 1.21.1 that does two things:

1. **Dynamic Weather Cycling** — Randomly switches the Overworld weather between
   Clear, Rain, and Thunder every **5 to 15 minutes** (6,000–18,000 ticks).
2. **Structure Spawn Boost** — Increases the spawn rate of naturally generated
   structures (villages, woodland mansions, pillager outposts, desert temples,
   etc.) by approximately **15%** by reducing their spacing/separation values.

Works on **both dedicated servers and clients** (singleplayer).

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | 0.100.8+1.21.1 (or newer) |
| Java | 21 |

---

## Building from Source

```bash
# Clone / download the project, then:
./gradlew build
```

The compiled JAR will be at:
```
build/libs/weather-structure-mod-1.0.0.jar
```

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods/` folder.
3. Place `weather-structure-mod-1.0.0.jar` in your `mods/` folder.
4. Launch Minecraft (or start your server).

### Server Installation
Place the JAR alongside Fabric API in the server's `mods/` folder.  
The mod is environment `"*"` — it is safe to have on the server only, but
players may also install it client-side with no conflicts.

---

## How It Works

### Weather Cycling (`WeatherStructureMod.java`)
- Listens to `ServerTickEvents.END_WORLD_TICK` for the Overworld.
- Maintains a per-world countdown timer, initialised to a random value
  between **6,000 and 18,000 ticks** (5–15 minutes at 20 TPS).
- When the timer reaches zero the mod calls `ServerWorld#setWeather(...)`,
  randomly choosing one of:
  - **Clear** — `setWeather(6000, 0, false, false)`
  - **Rain** — `setWeather(0, 6000, true, false)`
  - **Thunder** — `setWeather(0, 6000, true, true)`
- A new random interval is then chosen and the countdown resets.

### Structure Boost (`RandomSpreadStructurePlacementMixin.java`)
- Mixins into `RandomSpreadStructurePlacement` (the class that controls how
  vanilla structures are spread across the world).
- At the tail of the constructor it multiplies both `spacing` and `separation`
  by **0.87**, safely clamping them so `separation < spacing` is always true.
- This makes the chunk-grid cells that structure generation uses roughly 13%
  smaller, which corresponds to ~15% more structure placement opportunities.

---

## Configuration

There is no config file currently. To change the weather interval or the
density multiplier, edit the constants in the source and rebuild:

```java
// WeatherStructureMod.java
private static final int MIN_TICKS = 5  * 60 * 20;  // 5 min
private static final int MAX_TICKS = 15 * 60 * 20;  // 15 min

// RandomSpreadStructurePlacementMixin.java
private static final float DENSITY_FACTOR = 0.87f;   // 0.87 = ~15% more
```

---

## License

MIT — do whatever you like, attribution appreciated.
=======
# WeatherStructure
This is a Minecraft mod that brings I Want Weather back to life with its functionality being merged and it also now will increase chances of structures spawning
>>>>>>> 07ce76f17d5e78f69041548a887394c1f283139b
