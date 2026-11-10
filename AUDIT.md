# Platform-layer audit

Scope: `fabric/`, `neoforge/`, `paper/` and the build around them, at `main`
038fede (v1.8.0). `common/` was not re-audited. Line numbers for fixed findings
refer to `main`; line numbers for open findings refer to this branch.

How things were checked: reading the code, diffing the two loader classes,
`javap` against the real Minecraft 26.1.2 jar that the build downloads, and
inspecting the built jars and the CI artifact. **Nothing was run in a game or on
a server.** Where a finding depends on runtime behaviour, that is stated.

## Baseline

| | Before (`main`) | After (this branch) |
|---|---|---|
| `./gradlew :common:test --configure-on-demand` | 76 pass | 108 pass |
| `./build-all.sh` (Temurin 25.0.5) | builds, NeoForge jar misnamed (finding 1) | builds, all three jars named `…-1.8.0.jar` |

## Findings

### 1. NeoForge jar shipped with a garbage version in its file name — medium — fixed (fd8adea)

- Where: `neoforge/build.gradle:21`,
  `jarJar(project(':common')) { version { prefer mod_version } }`.
- What: a project dependency has no `version { }` DSL, so the call resolved
  against the enclosing project and set its version to the closure. The jar came
  out as `weather-structure-mod-neoforge-build_…$_run_closure3$_closure9$_closure10@<hash>.jar`,
  different on every build. `build-all.sh:122` and `README.md:9` name a
  `…-neoforge-1.8.0.jar` that was never produced.
- Confirmed by: the baseline build output, and the `mod-jars` artifact of CI run
  42071755797 (latest `main`), which contains the same misnamed jar.
- Fix: `jarJar project(':common')`. After the fix the jar is
  `weather-structure-mod-neoforge-1.8.0.jar` and `META-INF/jarjar/metadata.json`
  is unchanged (range `[1.8.0,)`, artifact `1.8.0`).

### 2. Paper: the structure boost's registry path could never succeed — high if the sweep is a no-op (unconfirmed), otherwise low — fixed (f6b5401), needs your in-game check

- Where: `WeatherStructurePlugin.java:545`, `registriesField("STRUCTURE_SETS")`,
  with the failure logged at `FINE` (line 559).
- What: MC 26.1.2 has `Registries.STRUCTURE_SET`, no `STRUCTURE_SETS`. The lookup
  threw `NoSuchFieldException`, so the plugin always fell through to the
  reflective sweep. The README's v1.8.0 note that this path was repaired is wrong.
- Confirmed by: `javap` on `net.minecraft.core.registries.Registries`. The rest of
  the chain matches 26.1.2: `ServerLevel.getServer()`,
  `MinecraftServer.registryAccess()`, `RegistryAccess.lookupOrThrow(ResourceKey)`
  (declared on the public interface, returns an iterable `Registry`),
  `StructureSet.placement()`.
- Consequence, from reading only: the sweep starts at
  `ServerChunkCache.getGenerator()`. In 26.1.2 neither `ChunkGenerator` nor
  `NoiseBasedChunkGenerator` holds structure sets (they live in
  `ChunkGeneratorStructureState`, reached through `getGeneratorState()`), and
  `Holder.Reference` / `MappedRegistry` have no field that crosses to another
  registry. So the sweep most likely finds nothing and **the boost was probably
  never applied on Paper**. This part is not confirmed by a run.
- Fix: look up `STRUCTURE_SET`; log failures on this path at `INFO`.
- **Behaviour note.** If the boost was a no-op, this fix turns it on: Paper
  servers start generating new chunks at 0.87 spacing, as Fabric and NeoForge
  already do. That is the documented behaviour, but it is a change on live
  servers. The fix is its own commit so it can be reverted alone.
- To settle it: start a Paper server on `main` and on this branch and compare the
  enable log. "Structure boost applied to N placement(s)" means it ran; "no
  placements found" means it did not.

### 3. Paper: the fallback sweep is not a working fallback on 26.1.2 — low — open

- Where: `WeatherStructurePlugin.java:594` (start point), 643/650/658 (loops).
- What: (a) per finding 2 it starts from an object that no longer leads to
  structure sets; (b) the `Map`/`Iterable`/array loops keep iterating after the
  20,000-node budget is spent, so the budget bounds recursion but not the time
  spent walking one very large or unbounded `Iterable`.
- Confirmed by: reading, plus the `javap` field lists above. Not run.
- Suggested: replace the sweep with `getGeneratorState().possibleStructureSets()`
  (both exist in 26.1.2) or delete it; add `if (budget[0] <= 0) break;` to the
  loops if it stays. Left alone because it cannot be exercised here.

### 4. `/weatherforecast` says "imminent" for a world with no timer yet — low — open

- Where: `WeatherMessages.java:61` (same logic as before the move).
- What: `getTicksUntilNextChange` returns `-1` for a world the engine has not
  ticked, and that is reported as "imminent" although the next change is 30–60
  minutes away. Only reachable in the first tick after a world appears, or on
  Paper for a world loaded during a timed override.
- Confirmed by: `WeatherMessagesTest.forecast_forAnUntickedWorld_isImminent`
  documents the current output. Not changed, since it alters reply text.

### 5. Paper: a world loaded during `/timedweather` does not get the override — low — open

- Where: `WeatherStructurePlugin.java:120` (`allWorldsApplier` runs only when the
  command is issued and on expiry).
- Confirmed by: reading. The world still gets the CLEAR on expiry.

## Checked, no finding

- **Fabric vs NeoForge.** Diffed the two mod classes and the two mixins. Every
  difference is loader API (entry point, event registration, the
  `instanceof ServerLevel` guard on NeoForge's tick event). No logic differences.
- **Mixin math.** Factor, floors and the `separation < spacing` repair behave
  correctly for small spacing (1, 2, 3), `separation >= spacing`, negatives and
  `Integer.MAX_VALUE`; now covered by `StructureDensityTest`, including every
  vanilla 26.1.2 `random_spread` structure set.
- **Mixin targets.** In 26.1.2 `spacing` and `separation` are still
  `private final int` and both constructors exist. Loom pins the Fabric
  `@Inject` to the 8-argument constructor at build time; NeoForge matches both
  and the `@Unique` guard stops a double application.
- **Fabric jar.** References Mojang names only, as an unobfuscated 26.1 runtime
  needs.
- **Vanilla `/weather`.** Its duration argument is `TimeArgument.time(1)`, so
  the `<ticks>t` form with a floor of 1 is right.
- **Paper world lifecycle.** Unload forgets the engine timer and rebuilds the
  tracked list without the unloading world; reload gets a fresh timer. The
  5-minute rebuild covers worlds created without an event.
- **Paper threading.** Tick task, events and commands all run on the main
  thread; `onCommand` hops there if called from elsewhere.
- **Permissions.** Paper's two `hasPermission` checks match the nodes in
  `plugin.yml` (both default `op`). Fabric and NeoForge both require
  `LEVEL_GAMEMASTERS` on both commands, the same level vanilla `/weather` needs.
- **Bad arguments.** Fabric and NeoForge are bounded by Brigadier
  (`integer(1, 86400)`); an unknown type gets a failure reply. Paper's parser
  returns empty instead of throwing for every malformed input tried in
  `WeatherCommandsTest`, and echoed input is sanitised.
- **Tick path.** All three are allocation-free per tick. The one per-tick lookup
  left is a `HashMap.get` on a cached-hash `String`; not worth changing.

## Deduplication (section 2)

Moved to `common/`, with tests:

- `StructureDensity`: the spacing/separation math, used by both mixins and Paper.
- `WeatherCommands`: the 24 h limit, seconds-to-ticks, the vanilla
  `weather <type> <ticks>t` line, Paper's seconds parser and echo sanitiser.
- `WeatherMessages`: every reply the platforms share. Wording is unchanged.

Not moved: Brigadier command registration, the tick handlers, `applyWeatherType`
and `getSpawnBiomeCategory` are still duplicated between `fabric/` and
`neoforge/`. They are written against `CommandSourceStack`, `ServerLevel` and
`Holder<Biome>`, so sharing them needs a module that compiles against Minecraft
(for example a `common-mc` source set built by both loaders). That is the next
step if you want it: the two loader classes went from about 240 lines to about
210 each, and most of what is left is still the same in both.

## Housekeeping (not changed)

### `com.example`

Real clash points, from the built jars:

- The bundled engine is identified as `com_example_common` in the Fabric nested
  jar's generated `fabric.mod.json` and as `com.example:common` in NeoForge's
  jar-in-jar metadata. Any other mod that ships an un-renamed
  `com.example:common` collides with it, and the loader keeps only one.
- Both mixin configs use package `com.example.weathermod.mixin`. A mod that
  declares the mixin package `com.example` would make Mixin refuse to load this
  mod's ordinary classes.

Proposed rename (one PR, no behaviour change):

1. Pick the namespace, e.g. group `io.github.mills520`, package
   `io.github.mills520.weatherstructure`.
2. `gradle.properties`: `maven_group`. This is not one of the pinned versions.
3. `common/build.gradle`: set `base.archivesName` to something unique such as
   `weatherstructuremod-common`, so the jar-in-jar identity is no longer `common`.
4. Move the four source trees and update `package`/`import` lines.
5. Update the string references: `fabric.mod.json` entrypoint, both
   `*.mixins.json` `package` values, `plugin.yml` `main`.
6. Leave alone: mod id `weatherstructuremod`, command names, permission nodes,
   the `weatherstructuremod.structureBoostApplied` system property. Nothing
   persisted uses the Java package, so worlds and configs are unaffected.
7. Check: build, then confirm the nested Fabric mod id and the jarjar
   `group`/`artifact` changed, and load each jar once.

### README and metadata versus the code

- `README.md:9` names the NeoForge jar correctly only after finding 1's fix.
- `README.md:99` (and `build.gradle:3`) give `./gradlew :common:test` as working
  without the Java 25 toolchain. CI adds `--configure-on-demand` and its comment
  says the flag is needed. Not re-tested on Java 21 here.
- `README.md:277` says 75 tests. It was 76 on `main`, 108 now.
- `README.md:15` refers to a `v1.5.0` git tag. The tags are `forge`, `idk`,
  `latest`, `new`, `stable`.
- The v1.8.0 changelog says the Paper registry path was fixed (finding 2).
- `fabric.mod.json:9` and `plugin.yml:7` link to
  `github.com/Mills520/weather-structure-mod`, which does not exist. The repo is
  `Mills520/WeatherStructure`.
- `gradle.properties:38-45` says Paper has no 26.x API on its maven.
  `paper-api` 26.1.2 builds are published now. The pin was not touched.
- The version string `v1.8.0` is hard-coded in the startup log line of all three
  platforms and will go stale on the next bump.
- `neoforge.mods.toml:7` uses `${file.jarVersion}` but the jar manifest has no
  `Implementation-Version`, so NeoForge may show a placeholder version. Not
  checked in game. `neoforge/build.gradle:43` still sets the `MixinConfigs`
  manifest attribute that the toml comment calls ignored.

### CI `platform-jars`

Green in all 9 completed runs between 2026-06-10 and 2026-11-05 (one more was
cancelled by the concurrency rule), with the build and upload steps both
succeeding each time. That is enough to drop `continue-on-error`. Two caveats:
it is 9 runs, and the only ruleset on `main` requires a pull request, not
status checks, so removing the line makes a failure visible (red run) without
blocking a merge until the job is also added as a required check. Note the job
was green throughout finding 1, so green means "compiles", not "artifacts are right".
