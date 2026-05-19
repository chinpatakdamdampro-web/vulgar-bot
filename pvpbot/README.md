# PvPBot — Carpet Fake-Player PvP AI Mod

A Fabric mod for **Minecraft Java 1.21.1** that spawns controllable fake-player
bots powered by the **Carpet Mod** API.  Bots behave like real PvP players with
intelligent, randomized combat AI.

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java (JDK)  | 21      |
| Minecraft   | 1.21.1  |
| Fabric Loader | ≥ 0.15.11 |
| Fabric API  | 0.102.0+1.21.1 |
| Carpet Mod  | 1.4.147+ (1.21.1 build) |

Both **Fabric API** and **Carpet Mod** must be installed on the server.
PvPBot itself only needs to be on the **server side**.

---

## Build Instructions

```bash
# 1. Clone / place this project
cd pvpbot/

# 2. Download the Gradle wrapper (first time only)
gradle wrapper --gradle-version 8.8

# 3. Build
./gradlew build

# 4. The compiled jar is at:
build/libs/pvpbot-1.0.0.jar
```

Copy the jar to your server's `mods/` folder alongside Fabric API and Carpet.

---

## Commands

All commands require **operator level 2** (`/op <player>`).

| Command | Description |
|---------|-------------|
| `/pb help` | Show all commands |
| `/pb spawn <name>` | Spawn a fake-player bot |
| `/pb remove <botName>` | Remove a bot permanently |
| `/pb stop <botName>` | Stop the bot's current action |
| `/pb attack <botName> <player>` | Order the bot to attack a player |
| `/pb follow <botName> <player>` | Order the bot to follow a player |
| `/pb equip <botName>` | Force the bot to re-evaluate and equip best gear |
| `/pb inventory <botName>` | Print the bot's hotbar to chat |

### Tab-completion

All `<botName>` arguments auto-complete to active bot names.  
All `<player>` arguments use vanilla player suggestion (online players).

### Example workflow

```
/pb spawn Dummy1
/pb attack Dummy1 Steve
/pb stop Dummy1
/pb remove Dummy1
```

---

## Gamemode Note

Bots are spawned using the **server's default game mode** (usually Survival).  
You can change their mode normally after spawning:

```
/gamemode creative Dummy1
/gamemode survival Dummy1
```

The mod intentionally does **not** force any game mode.

---

## Architecture

```
src/main/java/com/pvpbot/
│
├── PvPBotMod.java              ← Mod entrypoint, event hooks
│
├── command/
│   └── PvPBotCommand.java      ← Brigadier command tree (/pb ...)
│
├── config/
│   └── BotConfig.java          ← All tunable AI parameters
│
├── entity/
│   ├── BotManager.java         ← Singleton registry; drives tick loop
│   ├── BotSpawner.java         ← Carpet fake-player creation/removal
│   └── PvPBotEntity.java       ← Per-bot wrapper + state machine
│
└── ai/
    ├── targeting/
    │   └── TargetingSystem.java  ← Combat & follow target tracking
    ├── movement/
    │   └── MovementController.java ← Pathfinding, strafing, crits
    ├── combat/
    │   ├── CombatController.java   ← Attack loop, shield, combo patterns
    │   └── ComboPattern.java       ← Enum of fighting styles
    └── inventory/
        └── InventoryManager.java   ← Auto-equip armor/weapons, heal, food
```

### Subsystem overview

#### BotManager
Singleton that holds a `ConcurrentHashMap<name, PvPBotEntity>`.  
Called every `END_SERVER_TICK` to drive every bot's AI.  Dead bots are pruned automatically.

#### BotSpawner
Wraps `EntityPlayerMPFake.createFake(...)` (Carpet's public API).  
Uses offline-mode UUID generation (same algorithm as vanilla offline mode) so bots appear as fake offline accounts.

#### PvPBotEntity
Thin wrapper + state machine (`IDLE / FOLLOW / ATTACK`).  
Holds references to all four AI subsystems and routes the tick to the correct handler.

#### TargetingSystem
Manages `combatTarget` and `followTarget`.  
Validates targets are alive each tick; exposes `hasLineOfSight()` for wall-check filtering.

#### MovementController
- Uses `EntityNavigation.startMovingTo()` for terrain traversal.
- Applies manual strafe velocity perpendicular to facing direction.
- Handles jump-crit timing (`jumpForCrit()` / `isDescendingFromCrit()`).
- Manages sprint state.

#### CombatController
The most complex subsystem.  Runs a **combo state machine** per engagement.

**Combo patterns:**

| Pattern | Description |
|---------|-------------|
| `SPRINT_HITS` | 3 sprint hits → jump crit |
| `DOUBLE_STRAFE` | 2 hits → strafe pause → crit |
| `JUMP_RESET` | Jump crit → 2 follow-up hits |
| `SHIELD_BAIT` | Raise shield → drop → axe swing |

A new pattern is randomly chosen each time the previous one completes.  
Attack timing has configurable jitter (`attackTimingJitter`) to avoid a fixed CPS.

**Mace shield prediction:**  
Each tick the bot checks whether the target is above a height threshold, has a Mace in hand, and is falling faster than a velocity threshold.  If all three match, the bot raises its shield preemptively and holds it for `shieldHoldTicks`.

#### InventoryManager
- Scans inventory every 40 ticks and equips best armor (material + Protection enchantment score).
- Picks best weapon in main hand (Netherite Sword > Diamond Sword … with Sharpness bonus).
- Moves shield to offhand on demand.
- Eats Enchanted Golden Apple < 3 hearts, Golden Apple < 6 hearts, food < hunger threshold.

---

## Configuration (BotConfig)

All values are in `BotConfig.java`.  Three presets are available:

```java
BotConfig.easy()    // slow, predictable
BotConfig.medium()  // default
BotConfig.hard()    // fast, high crit rate, tight range
```

Key tunables:

| Field | Default | Description |
|-------|---------|-------------|
| `attackCooldownTicks` | 20 | Base ticks between attacks |
| `attackTimingJitter` | 4 | Random ±ticks added to cooldown |
| `critChancePercent` | 35 | % chance to jump-crit instead of normal hit |
| `sprintResetChancePercent` | 40 | % chance to sprint-reset between combos |
| `minCps / maxCps` | 6–12 | Clicks-per-second range |
| `preferredRange` | 2.8 | Target engagement distance |
| `maceShieldVelocityThreshold` | -0.5 | Y velocity to trigger mace shield |
| `maceShieldHeightThreshold` | 3.0 | Height above bot to trigger mace shield |
| `gappleThreshold` | 12 HP | Health to eat a golden apple |
| `notchAppleThreshold` | 6 HP | Health to eat an enchanted golden apple |

---

## Future Extensions

- **Crystal PvP**: Add `CrystalPlacementSystem` and `AnchorSystem` subsystems.
- **Practice mode**: Add a `DUEL` state with round tracking and match statistics.
- **Config file**: Persist `BotConfig` to `config/pvpbot.json` with GSON.
- **Difficulty command**: `/pb difficulty <botName> <easy|medium|hard>`.
- **Smart axe disabling**: Track opponent's shield cooldown and time axe hits.
- **Team support**: Bots that do not attack members of the same team.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Bot won't spawn | Ensure Carpet Mod is installed on the server |
| `NoClassDefFoundError: carpet/...` | Carpet jar missing from `mods/` |
| Bot stands still | Target is out of range or dead; use `/pb attack` again |
| Commands not appearing | You are not op (level 2); run `/op <you>` first |
| Build fails on `Enchantments.SHARPNESS` | Verify `yarn_mappings` in `gradle.properties` matches 1.21.1 |

---

## License

MIT — free to modify and distribute.
