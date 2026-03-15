# Weather & Structure Mod — Forge 1.21.11

## Requirements
| | Version |
|---|---|
| Minecraft | 1.21.11 |
| Forge | 61.1.0+ (recommended) |
| Java | 21 |

## Building
```bash
chmod +x gradlew
./gradlew build
```
Output: `build/libs/weather-structure-mod-forge-1.0.0.jar`

Place the JAR in your Forge `mods/` folder.

## Version notes
- Uses ForgeGradle 6.0.7 with Gradle 8.8
- Mojang official mappings
- EventBus 7 (required for Forge 1.21.6+)
- `TickEvent.LevelTickEvent` for weather cycling
- Mixin on `RandomSpreadStructurePlacement` for +15% structure density

## License
Apache 2.0 — © 2025 Mills520
