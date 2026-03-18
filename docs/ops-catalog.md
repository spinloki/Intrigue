# Operations Catalog

This document defines all operation types in the Intrigue mod. Operations (ops) are **discrete fleet-level actions** launched by subfactions within a territory. Each op has a trigger, spawns one or more fleets, resolves with success or failure, and produces downstream effects on presence factors and entanglements.

Ops are not entanglements. See [Ops vs. Entanglements](#ops-vs-entanglements) below.

All ops follow the decision/execution split architecture (see [Milestone 3a in the roadmap](roadmap.md)):
- **Decision layer (pure Java):** `TerritoryState` decides when to launch ops, advances timers, and resolves outcomes probabilistically when the player is absent.
- **Execution layer (Starsector):** `TerritoryManager` materializes ops as Intel objects with real fleets. Player interaction can override probabilistic outcomes.

Referenced by [Milestone 3a–3c in the roadmap](roadmap.md) and the [Entanglement Catalog](entanglement-catalog.md).

---

## Ops vs. Entanglements

**Ops** are events. **Entanglements** are states.

- An **op** is a discrete action with a beginning and an end: a fleet launches, travels, fights or negotiates, and resolves. Ops are transient — once resolved, the op is over.
- An **entanglement** is an ongoing political situation between subfactions in a territory: a hired protection contract or an open war, for example. Entanglements persist across many ticks and define the political landscape.

The two interact through a causal loop:

1. **Entanglements spawn ops.** An active Hired Protection entanglement dispatches Protection Patrol ops. A Shared-Enemy Pact spawns Joint Strike ops. The entanglement is the *reason* the op exists.
2. **Op outcomes create, modify, or destroy entanglements.** A successful Raid may cause the victim to seek Hired Protection. A Council's resolution can rewrite the entire entanglement map. The op is the *event* that changes the political state.

Neither concept subsumes the other. An op is not "part of" an entanglement, and an entanglement is not a special kind of op. They are distinct primitives connected by causality.

**Implementation model:** Entanglements are inert data — a type enum, participant IDs, a timer, and metadata. `TerritoryState` owns the entanglement map (keyed by unordered subfaction pair, at most one per pair) and queries it during ops decisions. Creating a new entanglement on a pair that already has one replaces it immediately.

---

## Op Lifecycle

1. **Launch** — `TerritoryState.advanceDay()` decides to launch an op based on trigger conditions. Returns a `LAUNCH_OP` tick result.
2. **Active** — Fleet is in transit or executing. `daysRemaining` counts down. If the player is present, a real fleet exists via the execution layer.
3. **Resolution** — Timer reaches 0, or the player intervenes (destroys/assists fleet). The op produces a `successChance` roll (probabilistic path) or a player-determined outcome (override path).
4. **Downstream effects** — Success or failure generates presence factors and may create, modify, or destroy entanglements.

---

## Presence Ops

These ops are triggered by the evaluation cycle (Milestone 3b) and directly drive presence state transitions.

### Territory Patrol

Routine inter-system patrol asserting a subfaction's presence across the territory.

**Trigger:**
- Automatic. One patrol per ESTABLISHED+ subfaction per tick window.

**Participants:**
- Single subfaction.

**Fleet:**
- Single fleet, RouteManager-based. 5-segment route: prep → travel to destination system → patrol (3–8 day visits to weighted POIs) → travel back → end.
- Fleet composition from subfaction doctrine. `BASE_COMBAT_FP = 60f`, tanker/freighter fractions at 10%.

**Resolution:**
- Success: leverage factor (weight 1, ~45 days).
- Failure (fleet destroyed): pressure factor (weight 1, ~45 days).

**Execution Backing:**
- `TerritoryPatrolIntel` (RouteManager + BaseIntelPlugin).

**Entanglement Effects:**
- None directly. Patrol success/failure feeds into the factor ledger, which drives the evaluation cycle, which can trigger transition ops and entanglements.

---

### Raid

A hostile subfaction launches a fleet to attack a rival's base system, aiming to demote them.

**Trigger:**
- Evaluation cycle identifies a demotion candidate with net pressure ≥ threshold, and a hostile subfaction exists in the territory to carry out the attack.

**Participants:**
- Attacker (hostile subfaction) and Target (demotion candidate).

**Fleet:**
- Multi-stage fleet with RaidIntel stage pipeline. Travel to target system → engage defenses → raid station.

**Resolution:**
- Success: Target demoted one presence level. Pressure factor on Target (~60 days). If this causes a significant power shift, may trigger entanglement changes (e.g. other subfactions form a Shared-Enemy Pact against the Attacker, or the Target seeks Hired Protection).
- Failure: Attacker takes pressure factor. Target may gain leverage factor (survived the attack).

**Execution Backing:**
- `TerritoryRaidIntel` (RaidIntel with stage pipeline).

**Entanglement Effects:**
- Successful raid can trigger: Hired Protection (victim seeks help), Retaliation Coalition (third party intervenes), Proxy Support (someone backs the weakened victim or the emboldened attacker).
- Repeated successful raids by the same attacker increase the chance of coalition formation against them.

---

### Evacuation

A subfaction voluntarily pulls back when under pressure but no hostile subfaction is present to carry out a raid.

**Trigger:**
- Evaluation cycle identifies a demotion candidate with net pressure ≥ threshold, but no hostile subfaction is present in the territory.

**Participants:**
- Single subfaction (the one pulling back).

**Fleet:**
- Withdrawal fleet departing the base system. Lighter execution — mostly narrative.

**Resolution:**
- Always succeeds (voluntary withdrawal).
- Target demoted one level but gains bonus leverage factor in the new state (soft landing — they chose to consolidate rather than being forced out).

**Execution Backing:**
- `TerritoryEvacuationIntel` (RouteManager + BaseIntelPlugin).

**Entanglement Effects:**
- None directly. The soft landing makes it easier to stabilize at the lower presence level.

---

### Expansion

A subfaction secures additional infrastructure to promote from ESTABLISHED to FORTIFIED.

**Trigger:**
- Evaluation cycle identifies a promotion candidate with net leverage ≥ threshold, currently at ESTABLISHED.

**Participants:**
- Single subfaction.

**Fleet:**
- Reinforcement fleet traveling to the territory. Secures infrastructure on arrival.

**Resolution:**
- Success: Promoted to FORTIFIED. Leverage factor.
- Failure (fleet intercepted): Promotion blocked. Factors may expire before next evaluation window.

**Execution Backing:**
- `TerritoryReinforcementIntel` (RouteManager + BaseIntelPlugin).

**Entanglement Effects:**
- Reaching FORTIFIED may trigger Territorial War or Civil War if another subfaction is already FORTIFIED+ in the territory.

---

### Supremacy

A major fleet action to assert dominance, promoting from FORTIFIED to DOMINANT.

**Trigger:**
- Evaluation cycle identifies a promotion candidate with net leverage ≥ threshold, currently at FORTIFIED.

**Participants:**
- Single subfaction.

**Fleet:**
- Large fleet — the subfaction's strongest commitment. Higher FP than standard ops.

**Resolution:**
- Success: Promoted to DOMINANT. This is a territory-shaking event.
- Failure: Heavy pressure factor. The failed bid for supremacy leaves the subfaction weakened.

**Execution Backing:**
- `TerritoryReinforcementIntel` or dedicated Supremacy Intel (TBD — may warrant FleetGroupIntel for multi-fleet coordination).

**Entanglement Effects:**
- Reaching DOMINANT is a major entanglement trigger:
  - Shared-Enemy Pacts may form among other subfactions.
  - Existing Territorial Wars may escalate.
  - Neutral subfactions now exert anti-dominance pressure via the factor system.

---

## Entanglement Ops

These ops are spawned by active entanglements rather than by the evaluation cycle. They are the visible fleet-level expression of political situations.

### Protection Patrol

Dispatched by the Protector subfaction to defend the Hirer's base system during a Hired Protection entanglement.

**Trigger:**
- Hired Protection entanglement is active.

**Participants:**
- Protector subfaction (fleet owner), defending Hirer's system.

**Fleet:**
- Patrol fleet operating in the Hirer's base system. Composition from the Protector's doctrine, but operating outside their own territory — visible "what are they doing here?" moment for the player.

**Resolution:**
- If the fleet successfully deters or defeats a raid on the Hirer: entanglement is reinforced (timer extended or refreshed).
- If a raid succeeds despite the protection fleet being present: betrayal — entanglement ends, both parties take hostile entanglement + pressure factor.
- If the protection fleet is destroyed by a third party or the player: entanglement may end early depending on whether the Protector can replace the fleet.

**Execution Backing:**
- RouteManager + BaseIntelPlugin (similar to Territory Patrol but stationed in another subfaction's system).

---

### Joint Strike

Coordinated parallel fleets from two subfactions targeting a shared enemy, launched during a Shared-Enemy Pact or Retaliation Coalition.

**Trigger:**
- Shared-Enemy Pact or Retaliation Coalition entanglement is active.

**Participants:**
- Two cooperating subfactions, targeting a third.

**Fleet:**
- Two separate fleets (one per participant) with synchronized timing, targeting the rival's system(s). Not mixed-faction fleets — parallel operations that the player can observe converging.

**Resolution:**
- Success: Target takes heavy pressure factor or demotion. Entanglement fulfilled — coalition members revert to baseline hostility on expiry.
- Partial success (one fleet succeeds, one fails): Reduced impact on target. Free-rider resentment may develop.
- Failure (both fleets fail): Coalition collapses. Target is emboldened (leverage factor). Participants take pressure and may turn on each other.

**Execution Backing:**
- FleetGroupIntel (multi-fleet coordination, straggler tracking) or paired RaidIntel instances (simpler, less synchronized).

---

### Proxy Raid

A raid carried out by a Proxy subfaction with covert backing from a Backer, during a Proxy Support entanglement.

**Trigger:**
- Proxy Support entanglement is active.

**Participants:**
- Proxy (fleet owner, visible), Backer (invisible supporter), Target.

**Fleet:**
- Proxy's raid fleet, but with boosted strength (fleet size mult or quality mod increase from the Backer's support). May include out-of-faction hulls from the Backer — the smoking gun if the player inspects the fleet.

**Resolution:**
- Success: Target takes demotion or pressure factor. Backer's investment pays off.
- Failure: Proxy takes pressure factor. Backer suffers no direct consequence unless the arrangement is discovered.
- Discovery: If the Target (or player) detects out-of-faction hulls or other evidence, the Proxy Support entanglement is exposed. Target gains a retaliatory entanglement against the Backer.

**Execution Backing:**
- RaidIntel with stage pipeline (same as standard Raid, but with modified fleet composition).

---

## Territory-Wide Ops

These ops involve multiple or all subfactions in a territory simultaneously and can reshape the entire political landscape.

### Council

Emissary fleets from all ESTABLISHED+ subfactions in the territory converge on a single location to negotiate the territory's political future. The highest-stakes op type — outcomes range from sweeping peace to total war.

**Trigger:**
- Fires at regular intervals (~120 days) as a political "shaker" for the territory.
- No stacking — only one council active at a time.
- Requires ≥3 ESTABLISHED+ subfactions (enough participants for meaningful negotiation).
- Does *not* require hostile entanglements — councils are held in peacetime and wartime alike.

**Participants:**
- All ESTABLISHED+ subfactions in the territory. Each sends an emissary fleet.

**Fleet:**
- One emissary fleet per participating subfaction, all converging on a designated system (highest-population system, or a system with symbolic significance like a comm relay hub).
- Emissary fleets are non-combat by default — lightly armed transport/diplomatic vessels. Their presence at the council location is what matters, not their combat power.

**Resolution (probabilistic path — player absent):**
Outcome is rolled based on the territory's current tension level, with weights that **bias toward change** — peaceful territories are more likely to break down, warlike territories are more likely to find peace. This creates a pendulum dynamic where councils prevent stagnation.

The tension level is measured as `hostileRatio = hostileCount / totalPairs`, where totalPairs = C(n,2) for n ESTABLISHED+ subfactions. A `pacificationBias` (= hostileRatio) and `disruptionBias` (= 1 - hostileRatio) scale the weights:

| Outcome | Base Weight | Scaling | Peaceful Territory | Warlike Territory |
|---|---|---|---|---|
| **Détente** | 0.05 | + 0.30 × pacificationBias | 5% | 35% |
| **Selective Pacts** | 0.10 | + 0.20 × pacificationBias | 10% | 30% |
| **Breakdown** | 0.15 | + 0.30 × disruptionBias | 45% | 15% |
| **Catastrophic Breakdown** | 0.05 | + 0.15 × disruptionBias | 20% | 5% |
| **Status Quo** | remainder | — | ~20% | ~15% |

Council-created hostile entanglements (BREAKDOWN, CATASTROPHIC_BREAKDOWN) use timer-based expiry (120 days for regular breakdown, 90 days for catastrophic) rather than condition-based FORTIFIED+ gating, so they persist even when subfactions are only at ESTABLISHED. This ensures councils can disrupt peaceful territories where nobody has reached FORTIFIED.

**Resolution (player present):**
The player's actions directly influence the outcome:

- **Destroy a specific emissary fleet** → That subfaction is excluded from negotiations. Remaining subfactions may form pacts *against* the excluded subfaction (or its perceived backer). Skews outcome toward Breakdown.
- **Destroy all emissary fleets** → Guaranteed Catastrophic Breakdown. Every pair goes hostile. The player has deliberately shattered the peace process.
- **Defend a specific emissary fleet** (protect it from pirates, hostile patrols, or another subfaction's interference) → That subfaction's negotiating position improves. More likely to emerge with favorable pacts.
- **Observe without intervening** → Probabilistic resolution proceeds normally, but the player witnesses the outcome play out.

**Duration:**
- Council ops have a gathering phase (~10–15 days for fleets to converge) and a negotiation phase (~5–10 days at the location). Total ~15–25 days.

**Execution Backing:**
- FleetGroupIntel (multi-fleet coordination) for the gathering phase. Each emissary fleet is tracked as a participant converging on the council location.

**Entanglement Effects:**
- The council's outcome directly rewrites the territory's entanglement map. This is the only op type that can replace multiple pair entanglements simultaneously.
- Since each pair can hold at most one entanglement, a council outcome that creates a new entanglement on a pair replaces whatever was there before.
- Post-council entanglements have a "negotiated" tag that may give them slightly different properties (e.g. negotiated ceasefires have a longer grace period before they can be broken by events).
- A Catastrophic Breakdown is self-reinforcing: the resulting all-hostile state accelerates raids and presence shifts, making the territory dramatically unstable until a dominant victor emerges or another council is called.

---

## Op Summary Table

| Op Type | Category | Trigger | Participants | Execution Backing | Entanglement Link |
|---|---|---|---|---|---|
| Territory Patrol | Presence | Automatic (ESTABLISHED+) | Single subfaction | RouteManager + BaseIntelPlugin | Indirect (factor ledger) |
| Raid | Presence | Evaluation: demotion + hostile present | Attacker + Target | RaidIntel | Can trigger Hired Protection, Retaliation Coalition, Proxy Support |
| Evacuation | Presence | Evaluation: demotion, no hostile present | Single subfaction | RouteManager + BaseIntelPlugin | None |
| Expansion | Presence | Evaluation: promotion (EST→FORT) | Single subfaction | RouteManager + BaseIntelPlugin | May trigger Territorial War, Civil War |
| Supremacy | Presence | Evaluation: promotion (FORT→DOM) | Single subfaction | TBD (possibly FleetGroupIntel) | Triggers Shared-Enemy Pacts, coalition pressure |
| Protection Patrol | Entanglement | Hired Protection active | Protector subfaction | RouteManager + BaseIntelPlugin | Reinforces or breaks Hired Protection |
| Joint Strike | Entanglement | Shared-Enemy Pact / Retaliation Coalition | Two cooperating subfactions | FleetGroupIntel or paired RaidIntel | Fulfills pact; may dissolve on completion |
| Proxy Raid | Entanglement | Proxy Support active | Proxy + (covert) Backer | RaidIntel | Discovery exposes Proxy Support |
| Council | Territory-wide | Political exhaustion / instability | All ESTABLISHED+ subfactions | FleetGroupIntel | Mass entanglement rewrite |

## Open Questions

- Should councils be triggerable by the player (e.g. via a mission or diplomatic action in Milestone 4)?
- What is the minimum number of subfactions required for a council to fire (3? 4?)?
- Should emissary fleets be truly non-combat, or lightly armed enough to fight if attacked? Non-combat makes player destruction trivially easy, which may be the right call for gameplay.
- Should council outcome weights be purely state-derived, or should subfaction personality modifiers influence them (e.g. hardliners increase Breakdown probability)?
- How should the gathering phase handle stragglers — does the council proceed if one emissary fleet is delayed or destroyed en route?
