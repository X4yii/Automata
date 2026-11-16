# Automata (v1.0.0)

Autonomous player-like agents with 3D navigation, tactical combat, survival instincts, and squad coordination for **Minecraft 1.12.2 (CleanroomMC)**.

---

## Requirements

- **Minecraft:** `1.12.2`
- **Mod Loader:** **CleanroomMC** `0.6.X` (Java 21) or `0.7.X` (Java 25)
- **Java:** `21+`
- **Required Dependency:** **`x4ui` `r1.0b6`** (exact version required: `required-after:x4ui@[r1.0b6]`)
- **Optional Integrations:** Hwyla / Waila, HEI / JEI, Scape and Run: Parasites (COTH immunity), Spartan Weaponry `[WIP]`

---

## Features & Status (`v1.0.0`)

### Stable Systems
- **3D Asynchronous Pathfinding (`AutomatonPathfinder` + `WorldCacheManager`):**
  - Runs off the main server thread using primitive-backed section caches (`Long2ObjectOpenHashMap`).
  - Parkour jumps (1–4 blocks), underwater swimming & shore exit, ladder climbing, and obstacle avoidance.
- **Tactical Combat & Squads (`Defend`, `CrazyDefend`, `Roam`):**
  - Melee spacing with attack-cooldown retreat, offhand shield blocking, and ranged parabolic bow aiming (gravity + drag + target lead compensation).
  - Squad coordination (`SquadBlackboard`): shared block reservations, combat turn rotation, downed-ally rescue alerts, and **0.5-block soft ally separation** with no allied physical pushing.
- **Survival & Hybrid Utility AI (`AutomatonBrain`):**
  - Emergency lava pillar/escape, fire water/shade seeking, Creeper/TNT avoidance, eating food to heal, and downed-state revival.
- **Modpack-Ready Performance:**
  - Adaptive MSPT/TPS throttling (`BudgetedTickScheduler`) automatically scales non-critical intervals when server tick time rises.
  - Designed to scale to **50+ automata** and **20+ squads** with minimal server-thread and GC overhead.
- **Items & Tools:**
  - **`AutomaStaff`**: Multi-mode wand (`Select Zone`, `Assign Chest`, `Whip`, `Follow / Stay`) with in-world region box rendering.
  - **`Comida con alas`**: Custom sword (`9` Attack Damage, `1.6` Attack Speed, `3200` Durability) applying Nausea II & Slowness I (5s) on hit, plus safe dismissal/termination of owned automata (or any with OP). Crafted with Gold Ingots, Feathers, Cooked Porkchop, and Bone.

### Work In Progress `[WIP]` & In Design
The following mechanics are included as experimental/early implementations and may be incomplete or unstable:
- **`Mine` (`MinerJob`) `[WIP]`** — Nearby exposed ore detection and mining.
- **`Lumberjack` (`LumberjackJob`) `[WIP]`** — Tree felling and spiral sapling replanting.
- **`Farmer` (`FarmerJob`) `[WIP]`** — Row-aligned tilling, interleaved planting, and harvesting.
- **`Pillar / Bridge` `[WIP]`** — Terrain-modifying scaffolding & sneak-bridging during pathfinding.
- **`Break-through` `[WIP]`** — Digging through blocking terrain outside work zones during pathfinding.
- **`Spartan Weaponry` Compatibility `[WIP]`** — Very incomplete; basic crossbow/reach/throwing checks only.
- **`Courier` (`CourierJob`) `[In Design]`** — **Currently disabled (no active mechanic)** while its logistics design is finalized.

---

## Controls (`AutomaStaff`)

- **Scroll / Mode Cycle:** Switch between `Select Zone`, `Assign Chest`, `Whip`, and `Follow / Stay`.
- **Right-Click Automaton (up to 10 blocks):** Open the X4UI control panel (Overview, Tasks/Logistics, Behavior/Squad, Telemetry).
- **Right-Click / Sneak+Right-Click Block:** Set `Pos1` / `Pos2` region bounds; **Sneak+Right-Click Automaton** to apply the region.
- **Sneak+Left-Click Chest:** Select deposit chest, then apply to an automaton.
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
