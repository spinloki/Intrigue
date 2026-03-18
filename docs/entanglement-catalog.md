# Entanglement Catalog

This document defines the entanglement types available in the Intrigue mod. Entanglements are **ongoing political states** — concrete, named situations between subfaction pairs (or groups) within a territory that determine hostility and define the political landscape.

Entanglements are not ops. An entanglement is a persistent condition (e.g. a protection contract, an open war) that can last weeks or months. Ops are the discrete fleet-level actions that entanglements spawn and that op outcomes create or destroy. See the [Operations Catalog](ops-catalog.md) for all op types and the full distinction.

Referenced by [Milestone 3c in the roadmap](roadmap.md).

## Design Direction

- **One per pair.** At most one entanglement is active between a given pair of subfactions in a territory. If a new entanglement forms, it immediately replaces the existing one. The replacement *is* the narrative: a ceasefire collapsing into war, a protection contract escalating into a pact.
- **Inert data.** Entanglements are pure data structs (type, participants, timer, metadata), not self-managing scripts. `TerritoryState` owns the entanglement map (keyed by unordered subfaction pair), queries it during its tick, and creates/destroys entries when ops resolve.
- Entanglements are triggered by discrete events (op outcomes, presence transitions, other entanglements expiring), never by slow invisible drift.
- Entanglements are territory-scoped. The same pair of subfactions can have different entanglements in different territories.
- Every entanglement is legible to the player: intel entries, fleet behavior changes, and status output all explain what is happening and why.
- Most entanglements produce future consequences. Solving one problem creates another. Cooperation breeds resentment from third parties. Conflict invites intervention.
- Betrayal and fallout are as important as the entanglement itself.

## Entanglement Types

### 1. Hired Protection

One subfaction pays another — typically a hostile or baseline-unfriendly one — to defend its territory.

**Trigger:**
- A subfaction suffers a discrete shock: successful raid against it, station crippled, repeated patrol losses, or a nearby rival reaching DOMINANT.

**Participants:**
- Hirer (the weakened subfaction) and Protector (the hired subfaction, normally hostile).

**Hostility Effect:**
- Suppresses hostility between Hirer and Protector for the duration.

**Active Effects:**
- Protector dispatches protection patrols to the Hirer's base system(s).
- These fleets patrol locally and help defend the station.
- The relationship is explicitly transactional — represented in intel as a contract, not an alliance.

**Duration:**
- ~60–90 days, or until the triggering threat subsides (e.g. the raiding subfaction is demoted).

**Expiry / Consequences:**
- While active, generates increasing resentment from third-party subfactions in the territory (leverage/pressure factor on third parties, or triggers further entanglements).
- If the Protector fails to defend the Hirer when needed (e.g. a raid succeeds during the contract), the entanglement ends in **betrayal**: the Hired Protection is replaced by a Territorial War entanglement between the two parties, plus a pressure factor on both.
- Strong player manipulation vector: keeping a toxic hired-protection entanglement alive long enough poisons multiple relationships in the territory.

---

### 2. Proxy Support

One subfaction covertly backs another against a shared rival, without formally changing their own hostility.

**Trigger:**
- An active Territorial War exists between two subfactions (A vs. B) in the territory.
- A third-party subfaction (C) is ESTABLISHED+ and has a stake: hostile to one side of the war, not hostile to the other.
- C backs the friendly side against the hostile side.
- Low per-day probability (1%) checked each tick while the conditions hold.

**Participants:**
- Backer (subfaction A), Proxy (subfaction B), Target (subfaction C).

**Hostility Effect:**
- No direct hostility change between Backer and Proxy. The backing is covert/deniable.
- Backer and Target hostility is unchanged (they may already be hostile, or may not be).

**Active Effects:**
- Proxy's fleets gain boosted strength for the duration (fleet size mult or quality mod increase).
- Proxy may receive out-of-faction hulls appearing in its fleet composition — visible to an observant player.
- Target suffers increased raid frequency from the Proxy.

**Variants:**
- Material support: stronger fleets, out-of-faction hulls.
- Backchannel logistics: Proxy's patrol sustainability improves (reduced patrol failure chance).

**Duration:**
- ~45–60 days, or until Target C is demoted one level.

**Expiry / Consequences:**
- If the proxy arrangement is "discovered" (perhaps a probabilistic event, or player-triggered), the Target gains a retaliatory entanglement against the Backer.
- If the Proxy is weakened (demoted) during the entanglement, the backing ends with no further consequence.

---

### 3. Shared-Enemy Pact

Two otherwise hostile subfactions agree to a temporary ceasefire to deal with a common dominant rival.

**Trigger:**
- Two subfactions are both under heavy pressure from the same rival (rival at DOMINANT or FORTIFIED with aggressive ops against both).
- OR a rival's recent aggression (successive raids, reaching DOMINANT) creates coalition incentive.

**Participants:**
- Two subfactions that share a common enemy in this territory.

**Hostility Effect:**
- Suppresses hostility between the two pact members for the duration.

**Active Effects:**
- Pact members can launch joint anti-dominance ops against the shared rival during the pact window.
- Joint ops are coordinated: parallel fleets with synchronized timing targeting the rival's systems.

**Duration:**
- Until the dominant rival is demoted below FORTIFIED, or until the threat recedes (rival's op frequency drops).
- Auto-expires if no hostile action from the rival occurs within a refresh window (~30 days).

**Expiry / Consequences:**
- When the pact expires, hostility between the two members reverts to baseline. No lasting friendship.
- If one pact member free-rides (doesn't contribute ops while the other fights the rival), the pact can end in resentment — the contributing member may gain a hostile entanglement against the free-rider.

---

### 4. Retaliation Coalition

A subfaction overreaches (successful raid, station crippling) and a third party uses that as justification to cooperate with the victim.

**Trigger:**
- A subfaction carries out a successful raid against another subfaction.
- 15% base chance (40% if the raider is DOMINANT).
- A third-party ESTABLISHED+ subfaction must be available: not hostile to the victim, not already entangled with the victim, and not the raider.

**Participants:**
- Victim, Responder (third party), and Aggressor.

**Hostility Effect:**
- Suppresses hostility between Victim and Responder for the duration (they may have been hostile before).
- Ensures hostility between Responder and Aggressor for the duration.

**Active Effects:**
- Victim and Responder launch a coordinated punitive op against the Aggressor.
- Single joint operation — this is a one-shot coalition, not an ongoing alliance.

**Duration:**
- Single op duration (~30 days). Expires when the punitive op resolves.

**Expiry / Consequences:**
- On success: Aggressor takes a demotion or heavy pressure factor. Victim and Responder hostility reverts to baseline afterward.
- On failure: coalition dissolves. Aggressor is emboldened (leverage factor). Victim takes additional pressure.
- The Responder's motivation may not be altruistic — they may be using the opportunity to weaken the Aggressor for their own benefit. This can be expressed in intel flavor text.

---

### 5. Territorial War

Two subfactions are in open, escalated conflict within a territory. This is the "hot war" state.

**Trigger:**
- Two subfactions competing for dominance in the same territory — both at FORTIFIED or higher.
- OR escalation from failed diplomatic entanglements (betrayed hired protection, broken pact).

**Participants:**
- Two subfactions in direct conflict.

**Hostility Effect:**
- Sets hostility between the pair (hostile).

**Active Effects:**
- Increased raid frequency between the pair.
- Raid ops against each other have boosted priority in the evaluation cycle.
- Both subfactions allocate more resources to fighting each other than to other rivals.

**Duration:**
- Until one side is demoted below FORTIFIED — the war is over when one side can no longer sustain the fight.

**Expiry / Consequences:**
- The loser (demoted side) may become a target for further exploitation by the winner or by third parties.
- The winner's dominance may trigger Shared-Enemy Pacts among other subfactions in the territory — winning a war paints a target.

---

### 6. Civil War

Two subfactions of the same parent faction turn on each other within a territory.

**Trigger:**
- Two subfactions sharing a parent faction both reach FORTIFIED or higher in the same territory.
- Their goals, methods, or local power bases become incompatible.

**Participants:**
- Two same-parent subfactions.

**Hostility Effect:**
- Sets hostility between the pair despite shared parent faction.

**Active Effects:**
- Behaves like Territorial War but with additional narrative framing: this is a jurisdictional or ideological schism, not random betrayal.
- Intel and fleet descriptions emphasize the intra-faction nature of the conflict.
- Outside subfactions may intervene on one side — can trigger Proxy Support entanglements from third parties who prefer one internal bloc over the other.

**Duration:**
- Until one side is demoted below FORTIFIED.

**Expiry / Consequences:**
- The losing side may be permanently weakened in this territory (bonus pressure factor).
- The winning side's parent faction takes a reputational hit in the territory — other subfactions see internal fractures as a sign of instability.

---

## Subfaction Personality & Entanglement Eligibility

Not all subfactions should be equally willing to enter all entanglement types. Subfaction personality biases influence which entanglements they can participate in:

- **Hardliner subfactions** (Hegemony Expeditionary, Pather Zealots) are more willing to enter Territorial Wars and less willing to accept Hired Protection from ideological enemies.
- **Commercial/opportunist subfactions** (Tri-Tachyon Ventures, Pirate Freehold) are more willing to enter transactional entanglements (Hired Protection, Proxy Support) across faction lines.
- **Traditionalist subfactions** may refuse certain entanglements entirely regardless of strategic benefit.

These biases are represented as static compatibility modifiers in config, not complex AI. They make entanglement *more or less likely*, not guaranteed or impossible. The specific modifiers are TBD during implementation and tuning.

## Presentation

- Intel entries explicitly name both participating subfactions and explain the trigger event.
- Parallel fleets with synchronized timing for joint ops (Shared-Enemy Pact, Retaliation Coalition) rather than mixed-faction fleets.
- Contract/ceasefire text in fleet descriptions and intel for cooperative entanglements.
- Out-of-faction ships appearing in supported fleets when Proxy Support is active.
- IntrigueStatus shows all active entanglements per territory: type, participants, trigger reason, hostility effect, remaining duration.
- Intel for hostile entanglements (Territorial War, Civil War) shows escalation history and op results.

## Design Guardrails

- **One per pair, always.** No stacking. No conflict resolution. The active entanglement on a pair is the relationship. New entanglements replace old ones atomically.
- Entanglements are temporary by design. No entanglement should create a permanent relationship change.
- Cooperative entanglements do not erase baseline hostility — they *suppress* it for a duration. When the entanglement ends, hostility reverts to the parent-faction baseline unless another entanglement replaces it.
- Every entanglement has a cost, a timer, or a backlash path.
- The player should always be able to understand *why* an entanglement exists (trigger event is recorded and shown in intel).
- The player should eventually get tools to prolong, sabotage, expose, or redirect entanglements (Milestone 4+).

## Implementation Priority

Strongest candidates for the initial implementation (Milestone 3c):

1. **Territorial War** — simplest hostile entanglement, validates the basic system.
2. **Hired Protection** — simplest cooperative entanglement, validates hostility suppression.
3. **Shared-Enemy Pact** — validates multi-party entanglement logic and joint ops.

Proxy Support, Retaliation Coalition, and Civil War can follow once the core entanglement lifecycle is proven.

## Open Questions

- Should Hired Protection be available only after discrete shocks, or also as a voluntary strategic choice by subfactions under sustained pressure?
- Should Proxy Support discovery be purely probabilistic, or should the player be able to expose it through missions?
- How should subfaction personality biases be tuned — per-subfaction config flags, or derived from parent-faction culture?
- What happens when an entanglement's participants are no longer in the same territory (one is demoted to SCOUTING/NONE)?
- Should entanglements produce intel notifications on creation and expiry, or only be visible via the status command?