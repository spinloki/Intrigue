# Intrigue Mod Roadmap

> Last updated: 2026-03-10

## Vision

Intrigue generates special sets of constellations called **Territories** that contain plot hooks for the various factions to pursue. **Subfactions** of each existing faction establish their presence in these territories and vie for supremacy as they pursue their goals. Subfactions are separate factions in game-code terms but have special behavior layered on by this mod.

---

## Milestones

### Milestone 1 — Territories I: Territory Generation ✅

> **Completed: 2026-03-09**

Define and generate special constellations (Territories) containing star systems, planets, and other features.

**Scope:**
- Define a Territory config schema (separate from `intrigue_settings.json`) that statically describes:
  - Star systems (star types, number of planets, orbital features, etc.)
  - Planets and other celestial bodies (conditions, types)
  - Other features (stations, jump points, etc.)
- Config supports controlled randomization — fixed skeleton with randomized details between runs.
- Sector generation hook that reads the config and creates the Territories during new-game generation.

**Exit Criteria:**
- Load the game, generate a new sector, and visually confirm the created Territories appear on the map with their defined features.

**Implementation Notes:**
- Territories co-opt existing "boring" vanilla constellations (those without significant theme tags or populated markets) rather than generating new ones or fighting for open space.
- Vanilla's `NameAssigner` re-derives all system/star/planet names from the territory name (e.g. "Alpha Ashenveil", "Alpha Ashenveil II").
- Hand-crafted systems from `data/config/territories.json` are injected into the claimed constellation.
- Diagnostic logging reports why each constellation was accepted or rejected, making filter tuning easy.
- Key files: `TerritoryGenerationPlugin.java`, `TerritoryGenerator.java`, `TerritoryConfig.java`, `data/config/territories.json`.

---

### Milestone 2 — Subfactions I: Subfaction Definition & Presence ✅

> **Completed: 2026-03-10**

Create subfactions as distinct in-game factions tied to parent factions, and let them establish themselves in Territories.

**Overall Exit Criteria:**
- Travel to a Territory and observe subfaction patrol fleets in the star systems, spawned from their dynamically-created bases, with correct doctrines and fleet-ring colors.

#### Milestone 2a — Data & Factions ✅

> **Completed: 2026-03-09**

`subfactions.json` + config parser + real game factions created at start.

**Scope:**
- `.faction` files for each subfaction registered in `factions.csv`.
- Subfaction config (`data/config/subfactions.json`) mapping each subfaction to its parent faction.
- `SubfactionDef` (pure identity data) + `SubfactionConfig` (loader) + `SubfactionSetup` (relationship copying from parent faction at game start).
- Each subfaction has a unique color scheme: primary `color` matches the parent faction, secondary UI/segment colors provide visual distinction.
- Fleet doctrines defined per-subfaction in the `.faction` files.
- Console commands for verification:
  - `IntrigueStatus` — prints all territories and subfaction data.
  - `IntrigueSpawnFleet <subfaction_id>` — spawns a subfaction patrol fleet near the player to visually verify colors and doctrine.

**Exit Criteria:**
- Console shows subfaction factions with correct doctrine/relationships. Spawned fleets have correct parent-faction primary colors and distinct secondary segment colors.

**Implementation Notes:**
- Key files: `SubfactionDef.java`, `SubfactionConfig.java`, `SubfactionSetup.java`, `IntrigueStatus.java`, `IntrigueSpawnFleet.java`, `data/config/subfactions.json`, `data/world/factions/factions.csv`, `data/world/factions/intrigue_*.faction`.

#### Milestone 2b — Manager Skeleton ✅

> **Completed: 2026-03-09**

`TerritoryManager` script per territory + `SubfactionPresence` with discrete states + save/load.

**Scope:**
- `TerritoryManager` — an `EveryFrameScript` (or similar) attached per territory that drives all subfaction activity within that territory.
- `SubfactionPresence` — tracks each subfaction's presence state within a territory using discrete states (e.g. `NONE → SCOUTING → ESTABLISHED → DOMINANT`).
- Territory decides which subfactions to bring in (territory-driven, not subfaction-driven).
- Full save/load support — managers and presence data survive save/reload cycles.

**Exit Criteria:**
- Managers survive save/reload with presence lists intact. Verifiable via console command.

#### Milestone 2c — Base Spawning ✅

> **Completed: 2026-03-10**

Day-tick timer, `SCOUTING → ESTABLISHED` transition, station entity + hidden market at pre-computed base slots.

**Scope:**
- **Base Slots** — Pre-computed at territory generation time by scanning all systems (procgen + hand-crafted) for valid base locations:
  - `PLANET_ORBIT` — orbiting a non-star planet at a close offset.
  - `ASTEROID_BELT` — embedded within an asteroid belt, orbiting the star.
  - Stable locations are explicitly excluded (reserved for vanilla comm relays, nav buoys, sensor arrays).
- **Slot Preferences** — Each subfaction can declare preferred slot types in `subfactions.json` (e.g. pirates prefer `ASTEROID_BELT`, Hegemony prefers `PLANET_ORBIT`). Falls back to any available slot if preferences can't be met.
- **Pinned Slots** — Statically defined in `territories.json` via `pinnedBaseSlots` (entity IDs guaranteed to be selected).
- **Spread Algorithm** — Round-robin across systems (1 per system before doubling up) for even distribution.
- **SCOUTING → ESTABLISHED** transition after ~25–35 days (randomized per subfaction per territory). Picks an available slot and spawns a station with a hidden market.
- **Base Station** — Makeshift station entity owned by the subfaction, with a hidden market containing:
  - Population, spaceport, military base industries (patrol fleet generation).
  - Orbital station industry (configurable per subfaction — fixed type or weighted random pick).
  - Open market + black market submarkets.
- **Economy Isolation** — `market.setEconGroup(marketId)` prevents global economy participation (no accessibility/stability penalties, no system name highlighting on sector map).
- **Commodity Supply** — `BaseEconomyListener` fills demand-supply gaps every economy tick (mirrors vanilla `PirateBaseIntel.commodityUpdated()`), preventing isolation-induced shortages from crippling military base patrols. Flavor text: "Shipped in by [parent faction]".
- Slot occupancy tracked — each slot can only hold one base.

**Exit Criteria:**
- Visible station in territory after ~30 days, correct faction, persists across save/reload. IntrigueStatus shows base slot occupancy. ✅

**Implementation Notes:**
- Key files: `BaseSlot.java`, `BaseSlotType.java`, `BaseSlotGenerator.java`, `BaseSpawner.java`, `BaseEconomyListener.java`.
- `BaseSlotGenerator` scans `system.getPlanets()` for planet orbits and `system.getTerrainCopy()` for asteroid belt terrain entities — works for both config-defined and procgen content.
- `TerritoryManager` stores slots as serializable state, drives the transition in its daily tick.
- Base market follows vanilla pirate/Pather base pattern: hidden market with basic industries.
- Station industry `finishBuildingOrUpgrading()` is called immediately so the station is combat-ready on spawn.
- `DecivTracker.NO_DECIV_KEY` set to prevent decivilization of the hidden market.

#### Milestone 2d — Patrol Fleets ✅

> **Completed: 2026-03-10** (delivered as part of 2c — military base industry provides patrol spawning out of the box)

Vanilla patrol spawner on base markets, fleet doctrine + ring colors.

**Scope:**
- Military base industry on each subfaction base market automatically spawns patrol fleets via vanilla logic — no custom patrol spawning code needed.
- Patrols are naturally scoped to the base's star system by vanilla patrol AI behavior.
- Fleet doctrine and composition driven by the subfaction's `.faction` file (`factionDoctrine` block).
- Fleet ring colors reflect the subfaction's color scheme (`color`, `secondaryUIColor`, `secondarySegments`).
- Custom patrol fleet names defined per-subfaction via `fleetTypeNames` in `.faction` files (e.g. "HEF Scout Patrol", "Corsair War Band").

**Exit Criteria:**
- Patrol fleets flying in territory with correct colors/composition. **This completes Milestone 2.** ✅

**Implementation Notes:**
- No new code needed — leveraging vanilla's military base patrol generation is a direct application of Design Principle #2 (Leverage Vanilla Code Aggressively).
- Patrol fleet doctrine parameters (warships, carriers, phase, ship size, aggression, quality) differ per subfaction to give each a distinct fleet personality.
- Known ships/fighters/weapons blueprints scoped per subfaction via parent faction's blueprint tags.
- Fleet strength is configurable per-subfaction in `subfactions.json`:
  - `fleetSizeMult` — multiplier on `Stats.COMBAT_FLEET_SIZE_MULT` (default 0.5, matching vanilla pirate bases).
  - `fleetQualityMod` — flat modifier on `Stats.FLEET_QUALITY_MOD` (default 0.0; higher = fewer D-mods).
  - `patrolExtraLight` / `patrolExtraMedium` / `patrolExtraHeavy` — flat additions to `Stats.PATROL_NUM_*_MOD` (default 0; intended for dynamic adjustment in Milestone 3).
- All fleet modifiers are applied via `BaseEconomyListener.economyUpdated()` every economy tick, matching the vanilla pirate base pattern.

#### Milestone 2e — Polish (if needed)

Debug console commands, edge-case fixes.

**Scope:**
- Additional debug console commands for inspecting territory/subfaction state.
- Edge-case fixes discovered during 2b–2d testing.
- Clean up any rough edges before moving to Milestone 3.

**Exit Criteria:**
- Clean debugging experience, no known bugs.

---

### Pre-Work — Simulation Decoupling ✅

> **Completed: 2026-03-10**

Extract pure decision logic from `TerritoryManager` into Starsector-free classes so that Milestone 3's conflict logic can be validated via a standalone test harness without running the game.

**Scope:**
- **`TerritoryState`** (new, pure Java) — Holds the territory's presence map, base slots, and establishment thresholds. Exposes `addPresence()`, `pickSlot()`, `checkEstablishmentReady()`, and the subfaction distribution factory (`distributeSubfactions()`). Zero Starsector imports.
- **`TerritoryManager`** (refactored) — Remains the `EveryFrameScript` adapter. Its daily tick delegates to `TerritoryState` for decisions, then executes Starsector-specific side effects (spawning bases via `BaseSpawner`, checking entity destruction via `Global.getSector()`). Thin wrapper.
- **`FORTIFIED` presence state** — Added to `PresenceState` enum between `ESTABLISHED` and `DOMINANT` (needed by 3b, but the enum is pure Java and costs nothing to add now).
- **Test harness** — A standalone Java `main()` that instantiates `TerritoryState` objects with synthetic data and runs the daily tick loop at thousands of iterations per second. Validates that:
  - Subfaction distribution works correctly.
  - SCOUTING → ESTABLISHED transition fires at the right time.
  - Slot picking respects preferences.
  - State is fully inspectable without Starsector APIs.

**Architecture Note (Design Principle #3):** All Milestone 3 conflict logic (leverage/pressure evaluation, state transitions, relationship tier changes) will be implemented in pure-Java classes called *by* `TerritoryManager`, not *in* it. The test harness drives these same classes directly, giving a single code path for both in-game and simulated execution. The harness is a rapid balance validation tool — it can run thousands of in-game days in seconds to detect snowballing, death spirals, and stalemates before ever launching the game.

**Exit Criteria:**
- `TerritoryState` compiles with zero Starsector imports. Test harness runs standalone and produces readable output showing territory state across simulated days.

**Implementation Notes:**
- Key files: `TerritoryState.java` (new), `TerritoryManager.java` (refactored), `PresenceState.java` (updated), `SimulationHarness.java` (new).
- `TerritoryState` is `Serializable` — `TerritoryManager` holds one as a field and delegates to it. Existing save format changes (bumped `serialVersionUID`).

---

### Milestone 3 — Operations I: Territorial Conflict

Subfactions spawn custom fleets to pursue operations within their territories, and territorial presence shifts through a leverage/pressure factor system that drives conflict between subfactions.

**Overall Exit Criteria:**
- Enter a Territory and observe subfactions running operations — fleets traveling between systems, raiding rivals, and presence levels shifting in response. Subfaction relationships evolve dynamically based on territorial friction.

#### Milestone 3a — Fleet Operations Framework ✅

> **Completed: 2026-03-10**

Decision/execution split architecture, RouteManager-based fleet spawning, and a concrete inter-system patrol operation to validate the full stack.

**Architecture — Decision/Execution Split:**

All operation logic is split into two layers:

- **Decision layer (pure Java):** `TerritoryState` decides *what* operations to launch, advances operation timers, and resolves outcomes probabilistically when the player is absent. Returns `TickResult` objects describing side effects. Runs identically in-game and in the test harness.
- **Execution layer (Starsector):** `TerritoryManager` interprets `TickResult`s by spawning Intel objects (backed by RouteManager/FleetGroupIntel/RaidIntel) that create real fleets the player can see and interact with. If the player physically intervenes (e.g. destroys a patrol fleet), the execution layer feeds the real outcome back into `TerritoryState`, overriding the probabilistic resolution.

**Key invariant:** If the player never visits a territory, the in-game results and the harness results for that territory are identical. The execution layer only diverges from the decision layer when the player directly interacts with a spawned fleet.

**Reconciling Principles 2 & 3:**
- **Principle 2 (Leverage Vanilla):** The execution layer uses RouteManager for fleet lifecycle, BaseIntelPlugin for intel screen display, and vanilla fleet creation APIs for doctrine-correct fleet spawning. We don't reimplement fleet travel, spawn/despawn, or waypoint navigation.
- **Principle 3 (Simulation–Game Parity):** The decision layer's probabilistic resolution is the canonical code path. The Intel/fleet in the execution layer is a concrete materialization that the player *can* interact with to alter the outcome, but doesn't have to. The harness always runs the probabilistic path.

**Scope:**
- **`ActiveOp`** (new, pure Java) — Data object representing an in-progress operation:
  - `opType` (PATROL, RAID, etc.), `subfactionId`, `originSystemId`, `targetSystemId`.
  - `daysRemaining` timer, `successChance` (context-dependent).
  - Tracked in `TerritoryState`'s active ops list.
- **Op lifecycle in `TerritoryState.advanceDay()`:**
  1. Launch new ops for eligible subfactions (one patrol per ESTABLISHED+ subfaction per tick window).
  2. Decrement `daysRemaining` on active ops.
  3. When timer reaches 0, roll `successChance` → produce boon or problem (wired up in 3b).
  4. Return `TickResult`s: `LAUNCH_OP`, `OP_COMPLETED`, `OP_FAILED`.
- **`TerritoryManager` execution of `TickResult`s:**
  - `LAUNCH_OP` → Spawn a `TerritoryPatrolIntel` backed by RouteManager. Fleet materializes when player is near.
  - `OP_COMPLETED` / `OP_FAILED` → Clean up Intel if it exists. (If player never visited, the Intel was never created — the probabilistic resolution stands.)
  - Player destroys fleet → `TerritoryManager` detects via fleet listener, calls `TerritoryState.overrideOpOutcome(opId, FAILURE)` to replace the probabilistic result.
- **`TerritoryPatrolIntel`** (new, Starsector) — Lightweight `BaseIntelPlugin` + `RouteFleetSpawner`:
  - Creates a single RouteData: base market → hyperspace travel → destination system → patrol (wait segment) → return.
  - Fleet spawned via vanilla fleet creation APIs, respecting subfaction doctrine.
  - Intel screen shows patrol route, faction, origin/destination systems.
  - Implements `FleetEventListener` to detect fleet destruction → feeds back to TerritoryState.
- **Harness validation:** `SimulationHarness` runs `TerritoryState.advanceDay()` and prints op launches/completions. Verifies op cadence, success rates, and that ops resolve correctly without any Starsector APIs.
- **Future harness extension:** Support "what-if" player interaction modes (e.g. `--player-helps=intrigue_hegemony_expeditionary` forces all ops by that subfaction to succeed, simulating a player who always assists them).

**Intel class selection by operation type:**
| Op type | Execution backing | Rationale |
|---|---|---|
| Territory Patrol (3a) | RouteManager + BaseIntelPlugin | Single fleet, simple route — lightest option |
| Raid / Base Attack (3b) | RaidIntel with stage pipeline | Multi-stage, FP-based success/failure |
| Coordinated Assault (future) | FleetGroupIntel | Multi-fleet coordination, straggler tracking |

**Exit Criteria:**
- Observe a subfaction fleet depart its base system, travel through hyperspace to another system in the same territory, patrol there, and return. Fleet persists across save/reload. IntrigueStatus shows active operations.
- Harness runs 1000 days and shows ops launching/completing with correct cadence. Op counts match between harness and an unvisited in-game territory.

**Implementation Notes:**
- Key files: `ActiveOp.java`, `TerritoryState.java` (op lifecycle), `TerritoryManager.java` (execution wiring), `TerritoryPatrolIntel.java` (RouteFleetSpawner + FleetEventListener), `PatrolRouteAI.java` (custom in-system behavior).
- `TerritoryPatrolIntel` uses a 5-segment route structure matching vanilla pattern: prep → travel → patrol → travel → end. `BASE_COMBAT_FP = 60f` with tanker/freighter fractions at 10%.
- `PatrolRouteAI` extends `RouteFleetAssignmentAI` with ScavengerFleetAssignmentAI-style behavior: short 3–8 day visits to weighted POIs (jump points, inhabited planets, objectives) that chain naturally via re-picks until segment time is exhausted.
- **Gotcha:** `RouteFleetAssignmentAI`'s constructor calls the overridable `addLocalAssignment()` before subclass field initializers run. Any subclass fields must use lazy initialization.
- **Gotcha:** Vanilla `addLocalAssignment()` PATROL_SYSTEM branch at system center has no `goNextScript` — routes never advance. All vanilla fleets subclass and override this, so it's never hit in practice. Custom subclass required.
- Fleet destruction detected via `FleetEventListener.reportBattleOccurred()` → `DESTROYED_BY_BATTLE`. Outcome fed back to `TerritoryState.overrideOpOutcome()`.
- `IntrigueStatus` updated to show active ops: type, subfaction, route, days remaining, outcome, fleet status.

#### Milestone 3b — Presence Dynamics

Leverage/pressure factor system driving presence state transitions (`SCOUTING → ESTABLISHED → FORTIFIED → DOMINANT`), periodic evaluation cycles that trigger promotion/demotion ops, and natural balancing forces that prevent runaway dominance.

**Core Mechanic — Leverage & Pressure:**

Each subfaction in a territory maintains a **factor ledger** — a list of active factors, each contributing either **leverage** (positive, pushes toward promotion) or **pressure** (negative, pushes toward demotion). Factors are weights on a scale, not abstract numbers. The **net balance** (sum of leverage weights minus sum of pressure weights) determines whether a subfaction's presence is stable, growing, or shrinking.

**Factor Duration Categories:**
| Category | Lifetime | Examples |
|---|---|---|
| **Intrinsic** | Always present, derived from current state. Recalculated each tick. | Logistical strain (scales with presence level) |
| **Conditional** | Active while a game-world condition holds. Checked each tick. | Station crippled, hostile subfaction present, comm relay ownership |
| **Expiring** | Triggered by event, fades after N days. Counts down automatically. | Patrol success, supply convoy lost, player mission completed, desertion |

**Intrinsic & Conditional Factors:**
- **Logistical Strain** (intrinsic, pressure) — Scales with presence state: ESTABLISHED = 1, FORTIFIED = 3, DOMINANT = 5. Higher states are inherently harder to sustain.
- **Hostile Presence** (conditional, pressure) — Each hostile subfaction in the territory exerts pressure that scales with the target's state: ESTABLISHED = 1, FORTIFIED = 2, DOMINANT = 3 per hostile subfaction.
- **Neutral Presence** (conditional, pressure) — Neutral subfactions only exert pressure against DOMINANT subfactions (1 per neutral). This is the anti-dominance coalition emerging naturally from the factor system.
- **Secure Comms** (conditional, leverage) — Subfaction's parent faction controls local comm relay. State-dependent: ESTABLISHED = 2, FORTIFIED = 2, DOMINANT = 1.
- **Station Crippled** (conditional, pressure) — Base station was "destroyed" in combat and hasn't recovered. Weight 1.

**Expiring Factors:**
- **Patrol Success/Failure** — Generated when patrol ops resolve. Leverage or pressure, weight 1, expires after ~45 days.
- **Station Raided** — Pressure, weight 1, expires after ~60 days.
- **Desertion** — Neutral pending event (weight 0, ~30 days). Daily roll per state: ESTABLISHED 1%, FORTIFIED 2.5%, DOMINANT 5%. On expiry, resolves to **Desertion Quelled** (leverage 2, 45d) or **Desertion Spiraled** (pressure 2, 45d) based on net balance (50% base ± 10% per net point, clamped 10–90%).
- **Player Mission Completed** — Leverage, weight 1, expires after ~90 days (lasts longer because player effort should matter).

All factor types, polarities, weights, and durations are defined in `intrigue_settings.json` for rapid tuning via the simulation harness.

**Evaluation Cycle (every ~60 days):**

Every `evaluationIntervalDays` (configurable, default 60), the territory evaluates each subfaction's net balance:

1. **Demotion candidate:** Subfaction with net pressure ≥ `transitionThreshold` (configurable, symmetric with promotion). Picks the most extreme.
2. **Promotion candidate:** Subfaction with net leverage ≥ `transitionThreshold`. Picks the most extreme.
3. Up to one demotion op and one promotion op triggered per evaluation cycle per territory.

**Demotion Ops:**
| Condition | Op Type | On Success |
|---|---|---|
| Hostile subfaction in territory | **Raid** — hostile subfaction launches fleet against the target | Target demoted one level |
| No hostile subfaction present | **Evacuation** — subfaction voluntarily pulls back | Demoted one level, but gains bonus leverage in new state (soft landing) |

**Promotion Ops:**
| Transition | Op Type | On Success |
|---|---|---|
| ESTABLISHED → FORTIFIED | **Expansion** — fleet secures additional infrastructure | Promoted to FORTIFIED |
| FORTIFIED → DOMINANT | **Supremacy** — major fleet action to assert control | Promoted to DOMINANT |

If a promotion/demotion op fails (intercepted by player, or probabilistically), the triggering factors may expire before the next evaluation, requiring the subfaction to rebuild its position.

**Downward Transitions** — `DOMINANT → FORTIFIED → ESTABLISHED → SCOUTING`. A subfaction reverted to `SCOUTING` loses its base (slot released) and must re-establish.

**Scope (implementation steps):**

1. ✅ **Factor data model** (`PresenceFactor.java`, pure Java) — `FactorType` enum, `FactorPolarity` (LEVERAGE/PRESSURE), weight, duration, source ID.
2. ✅ **Factor config** (`intrigue_settings.json` + `IntrigueSettings.java`) — All factor types, weights, durations, evaluation interval, transition threshold. Loaded by both the game and the harness.
3. ✅ **Factor ledger in `SubfactionPresence`** — `List<PresenceFactor>`, `addFactor()`, `getNetBalance()`, `tickFactors()` (decrement/prune expired, returns expired factors for event resolution).
4. ✅ **Intrinsic & conditional factor generation in `TerritoryState`** — Recalculate intrinsic factors each tick. Wire `OP_RESOLVED` → leverage/pressure factor. Hostile/neutral presence counting (pure Java — just counting other subfactions). Desertion roll/resolution.
5. ✅ **Evaluation cycle & transition ops in `TerritoryState`** — 60-day evaluation, threshold check, new `TickResultType`s and `OpType`s for demotion/promotion.
6. ✅ **Demotion/promotion op execution** (`TerritoryManager`) — Spawn raid/evacuation/expansion/supremacy Intels. Apply state transitions on op resolution via `applyOpOutcome()`.
7. ✅ **Starsector-side conditional factors** (`TerritoryManager`) — `injectConditionalFactors()` checks comm relay ownership (parent faction) per system, overrides pure-Java SECURE_COMMS placeholder.
8. ✅ **Harness updates** (`SimulationHarness`) — Load factor config, print factor ledgers and net balances, track promotions/demotions, inject synthetic conditionals. Enhanced metrics: SubfactionTracker, op-type breakdown, desertion stats.
9. ✅ **Tuning & verification** — Sim 1000+ days confirms: subfactions shift between all presence states, no permanent dominance, healthy promotion/demotion cadence, desertion mechanic provides meaningful variance.

**Build order:** Steps 1–5, 8 first (pure-Java loop running and tunable via harness), then 6–7 (Starsector execution), then 9 (tuning).

**Exit Criteria:**
- Harness shows subfactions shifting between presence states over 1000+ simulated days. No subfaction holds DOMINANT indefinitely. Factor ledgers are fully auditable.
- In-game: observe promotion/demotion ops firing. IntrigueStatus shows factor ledgers, net balances, and evaluation countdown per subfaction.

**Implementation Notes:**
- Key files: `PresenceFactor.java` (new), `IntrigueSettings.java` (new), `SubfactionPresence.java` (updated), `TerritoryState.java` (evaluation cycle + factor generation), `TerritoryManager.java` (op execution + conditional factor injection), `SimulationHarness.java` (updated).
- Factor weights defined in `intrigue_settings.json` — single source of truth for both game and harness.
- Transition threshold is symmetric by default (same threshold for promotion and demotion) but configurable as separate values if asymmetry is needed later.
- **Gotcha (from 3a):** `ActiveOp` static ID counter needs attention for save/load. Consider seeding from territory state.

#### Milestone 3c — Subfaction Relations

Dynamic sector-wide subfaction relationships driven by territorial friction, using discrete tiers with explicit transition triggers.

**Scope:**
- **Relationship Tiers** — Discrete tiers between subfaction pairs: `HOSTILE ↔ UNFRIENDLY ↔ NEUTRAL ↔ FRIENDLY`. Mapped to Starsector's `FactionAPI.setRelationship()` float values.
- **Sector-Wide Scope** — Subfaction relationships are inherently sector-wide (a fundamental limitation of `FactionAPI`). Two subfactions cannot be hostile in one territory and friendly in another. Territorial events feed into the single sector-wide relationship.
- **Stressors & Stabilizers** — Relationship changes are driven by accumulating discrete factors:
  - **Stressors** (push toward hostile): fleet engagements, base raids, competing for the same territory, one side reaching DOMINANT while the other is present.
  - **Stabilizers** (push toward friendly): sharing a parent faction, common enemies (both hostile to a third subfaction), prolonged absence of conflict.
  - Parent faction hostility is inherited as a baseline stressor (e.g. Hegemony vs. Tri-Tachyon subfactions start unfriendly and need strong stabilizers to overcome that).
- **Tier Transitions** — Relationship tier changes when accumulated stressors or stabilizers cross a threshold. Transitions are discrete events (not gradual drift), making them auditable and predictable.
- **Mod-Owned Relations** — The Intrigue mod is the **source of truth** for all subfaction relationships. The mod maintains its own relationship model internally, then pushes changes to Starsector's `FactionAPI.setRelationship()` so that game behavior (fleet hostility, market access, etc.) stays consistent with the mod's internal state. This means:
  - The mod decides when relationships change (not vanilla event handlers).
  - `SubfactionSetup` seeds initial relationships from parent factions at game start.
  - All subsequent relationship changes are driven by the mod's stressor/stabilizer system, not by vanilla diplomacy events.
  - The pure-Java decision layer tracks relationship tiers internally; the Starsector execution layer syncs them to `FactionAPI`.
- **Initial Relationships** — Copied from parent factions at game start (existing `SubfactionSetup` behavior). Dynamic changes layer on top of this baseline during gameplay.

**Exit Criteria:**
- Subfaction relationships shift during gameplay based on territorial events. Two subfactions competing heavily in a territory trend toward hostile; two subfactions with aligned parents trend toward friendly. IntrigueStatus shows relationship tiers and active stressors/stabilizers.

#### Milestone 3d — Polish & Balance

Tuning, debug tooling, and edge-case hardening.

**Scope:**
- Console commands for inspecting/manipulating leverage, pressure, and relationship state.
- Tuning pass on operation frequency, factor generation rates, and tier thresholds.
- Edge-case handling: what happens when all slots are occupied, subfaction eliminated from territory, base destroyed mid-operation, etc.
- Verify save/load integrity across all new state.

**Exit Criteria:**
- Clean debugging experience. Territorial conflict plays out over 100+ in-game days without degenerate outcomes (stalemates, instant dominance, oscillation). IntrigueStatus shows full factor ledgers, net balances, and relationship state.

---

### Milestone 4 — Missions: Player-Facing Content

Subfactions issue mission requests that let the player influence territorial balance.

**Scope:**
- Mission intel items appear periodically, scoped per-Territory.
- Completing missions strengthens or weakens the involved subfactions in that Territory.
- Mission types include (but are not limited to):
  - Bounties on rival subfaction fleets.
  - Escorting supply convoys.
  - Destroying rival subfaction bases.
- Missions surface in the intel screen alongside vanilla bounty/exploration missions.

**Exit Criteria:**
- Open the intel screen and see several subfaction missions available per Territory. Accept, complete, and confirm the downstream effects work correctly.

---

### Milestone 5+ — Future (TBD)

Planned but not yet scoped in detail. Likely includes:

- **Plot Hooks** — Specialized per-territory operations unique to each subfaction's narrative goals.
- **Territories II** — Refinement pass on territory generation (richer features, better variety).
- **Subfactions II** — Deeper subfaction identity, diplomacy, and progression.
- **Operations II** — More operation types, smarter AI decision-making.

---

## Design Principles (Lessons Learned)

These are hard-won lessons from the first attempt at this mod. They should guide every design and implementation decision.

### 1. Discrete States Over Continuous Values

**Problem:** The v1 design used continuous numeric ranges (0–100 presence, −100–100 relations) across 10+ sector-wide subfactions. Every behavior change required multiple rebalancing passes, and emergent interactions were nearly impossible to reason about.

**Principle:** Prefer discrete, named states with explicit transition conditions.

- Presence: `SCOUTING → ESTABLISHED → FORTIFIED → DOMINANT` (not 0–100).
- Presence transitions driven by discrete boons/problems balance, not accumulating numeric scores.
- Relations: `HOSTILE ↔ UNFRIENDLY ↔ NEUTRAL ↔ FRIENDLY` (not −100–100).
- Relation transitions driven by discrete stressors/stabilizers, not gradual drift.
- State transitions have clear, auditable triggers rather than accumulating nudges.
- Cap the number of subfactions active in a single Territory to keep the combinatorial space manageable.

### 2. Leverage Vanilla Code Aggressively

**Problem:** Many features were reimplemented from scratch to "match" vanilla behavior, leading to subtle inconsistencies and extra maintenance burden.

**Principle:** Before building anything, check if vanilla already provides the behavior (or something close enough to wrap). Use vanilla systems directly wherever possible:

- Base spawning → study pirate/Pather base creation code.
- Patrol fleet generation → reuse or extend vanilla patrol logic.
- Mission/intel framework → build on the existing intel/mission infrastructure.
- Fleet doctrine & composition → use the vanilla fleet doctrine system.

### 3. Simulation–Game Parity

**Problem:** A simulation/SPI layer was added late and had its own resolution logic, causing divergence between simulated and actual in-game outcomes.

**Principle:** There must be a single code path for operation resolution. When the player is not present, outcomes should be determined by the same logic that would run if they were. If a separate simulation layer exists, it must be a thin wrapper around the real game logic — never a parallel reimplementation.

### 4. Scope Territories, Not the Sector

**Problem:** Subfactions acting sector-wide created an explosion of interactions.

**Principle:** All subfaction activity is scoped to individual Territories. This bounds complexity and makes each Territory a self-contained theater that can be reasoned about independently.

