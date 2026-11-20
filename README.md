# Automata (v1.2.0)

Autonomous player-like agents with 3D navigation, tactical & aerial combat, survival instincts, logistics, and squad coordination for **Minecraft 1.12.2 (CleanroomMC)**.

---

## Requirements

- **Minecraft:** `1.12.2`
- **Mod Loader:** **CleanroomMC** `0.6.X` (Java 21) or `0.7.X` (Java 25)
- **Java:** `21+` (compiled targeting CleanroomMC / Java 25)
- **Required Dependency:** **[x4ui](https://github.com/X4yii/x4ui)**|
- **Optional Integrations:** Hwyla / Waila, HEI / JEI, Useful Backpacks, Baubles & RLArtifacts (7 accessory slots in Overview), Wings (server-side flight physics & aerial combat), Scape and Run: Parasites + NoCubes SRP Combat Addon, Spartan Weaponry, OptiFine / Dynamic Lights (offhand torch), and claim/protection mods (via `ClaimProtectionCompat`)

---

## Features (`v1.2.0`)

### Navigation, Movement & Safe Placement
- **3D Asynchronous Pathfinding (`AutomatonPathfinder` + `WorldCacheManager`):**
  - Runs off the main server thread using primitive-backed section caches (`Long2ObjectOpenHashMap`) with invalidation on block placement, explosions, and chunk unloads.
  - Parkour jumps (1–4 blocks), underwater swimming & shore exit, ladder climbing, and obstacle avoidance.
- **Modular Movement & Safe Construction:**
  - Dedicated controllers (`MotorController`, `CameraController`, `ParkourController`, `SwimController`, `PathController`, `MovementPace`).
  - `BlockPlacementService` validates reach, living-entity collisions, and claim permissions before placing pillars or bridges; includes waterdrop bucket refilling fix.

### Jobs, Tactical Combat & Squads
- **Guard & Tactical Combat (`Guard`, `AggressiveGuard`, `Roam`):**
  - Melee spacing, offhand shield blocking, parabolic bow aiming, aquatic combat (`AquaticCombatController`), projectile & wave dodging (`ProjectileDodgeController`, `WavePredictionDodgeController`), and `AdaptiveThreatAnalyzer`.
  - Squad coordination (`SquadBlackboard`, `SquadSupplyService`, `WorldEntitySpatialIndex`, `BotLoadoutProfile`): shared block reservations, combat turn rotation, downed-ally rescue, and **0.5-block soft ally separation**.
- **Work Jobs & Chest Logistics:**
  - `MineJob` (ore vein detection + `ModdedOreCatalog`), `LumberjackJob` (tree felling & replanting), `FarmerJob` (tilling, planting, harvesting), and `ExcavationJob` (area clearing + `BlockWorkQueue`).
  - `LogisticsService`: supports single, double, and modded chests with 1-block edge proximity checks and safe empty-inventory guards.
  - `Courier` (`CourierJob`) remains `[In Design]` (disabled).

### Modpack Safety, UI & Tools
- **Isolated Claim & Protection Layer (`ClaimProtectionCompat`):**
  - Dry-run `BreakEvent` / `EntityPlaceEvent` and spawn protection checks across `ExcavationJob`, `MineJob`, `LumberjackJob`, `FarmerJob`, and `BlockPlacementService`, blacklisting denied blocks and sending rate-limited owner alerts.
- **Items & Tools:**
  - **`AutomaStaff`**: Server-synced multi-mode wand (`Select Zone`, `Assign Chest`, `Whip`, `Follow / Stay`) with in-world region box rendering.
  - **`AutomaDevStaff`**: Creative/developer tool with dedicated-server debug rendering synced via `automata:debug_sync` RPC.
  - **`Comida con alas`**: Custom sword (`9` Attack Damage, `1.6` Attack Speed, `3200` Durability) applying Nausea II & Slowness I (5s) on hit, plus safe dismissal of owned automata (or any with OP). Crafted with Gold Ingots, Feathers, Cooked Porkchop, and Bone.
- **Full Localization (`en_us` / `es_es`):** Complete localization for X4UI tabs, zone selection, items, alerts, and toasts.

---

## Controls (`AutomaStaff`)

- **Scroll / Mode Cycle:** Switch between `Select Zone`, `Assign Chest`, `Whip`, and `Follow / Stay`.
- **Right-Click Automaton (up to 10 blocks):** Open the X4UI control panel (Overview, Tasks/Logistics, Behavior/Squad, Telemetry).
- **Right-Click / Sneak+Right-Click Block:** Set `Pos1` / `Pos2` region bounds; **Sneak+Right-Click Automaton** to apply the region.
- **Sneak+Left-Click Chest:** Select deposit/source chest, then apply to an automaton.
- **Left-Click Automaton:** Gentle unstick nudge ("Whip").

---

## Commands & Selectors

- **`/automata spawn [skinName]`** (OP lvl 2): Spawn an automaton owned by you with an optional player skin.
- **`/automata reload`** (OP lvl 2): Reload `ModdedOreCatalog` configuration.
- **Chat `.bot` Teleport:** `.mybot tp me` or `.mybot tp <player>` (plus Tab completion for owned bots).
- **Vanilla Command Selectors:** Supports `@a` / `@a[...]` targeting extensions for automata in commands.

---

## License

Distributed under **GPL-3.0-or-later** — see [LICENSE](LICENSE).
