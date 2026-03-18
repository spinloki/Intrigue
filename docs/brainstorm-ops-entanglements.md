# Brainstorm: New Ops & Entanglements

The vanilla-faithful baseline hostility fix revealed a structural gap: most of the existing ops and entanglements are designed for **hostile** pairs. With the corrected baseline, many subfaction pairs in a territory are now NEUTRAL — and neutral pairs have almost nothing to do except patrol, grow, and ignore each other.

This doc brainstorms new ops and entanglements to fill that gap. The goal is to make territories feel alive regardless of the hostility state, and to create **escalation ladders** where neutral pairs can drift toward hostility (or deepen cooperation) through a chain of discrete events rather than being born hostile.

## The Problem: What Do Neutral Pairs Do?

Current territory compositions and their pair relationships under the vanilla baseline:

**Ashenveil** — pirates, church, persean, path
| Pair | Parents | Baseline |
|---|---|---|
| pirates ↔ church | pirates ↔ luddic_church | **HOSTILE** |
| pirates ↔ persean | pirates ↔ persean | **HOSTILE** |
| pirates ↔ path | pirates ↔ luddic_path | NEUTRAL |
| church ↔ persean | luddic_church ↔ persean | NEUTRAL |
| church ↔ path | luddic_church ↔ luddic_path | NEUTRAL (cooperative in vanilla) |
| persean ↔ path | persean ↔ luddic_path | **HOSTILE** |

3 hostile, 3 neutral. The neutral pairs (including the Luddic family pair and the pirates-path pair that are *friendly* in vanilla) have no interactions beyond patrols.

**Shardfields** — diktat, independent, hegemony
| Pair | Parents | Baseline |
|---|---|---|
| diktat ↔ independent | sindrian_diktat ↔ independent | NEUTRAL |
| diktat ↔ hegemony | sindrian_diktat ↔ hegemony | NEUTRAL |
| independent ↔ hegemony | independent ↔ hegemony | NEUTRAL |

0 hostile, 3 neutral. **Nothing interesting happens.** No raids, no entanglements, no drama. Subfactions grow peacefully to DOMINANT and nothing disrupts them.

**Cindervast** — path, tritachyon, pirates, hegemony
| Pair | Parents | Baseline |
|---|---|---|
| path ↔ tritachyon | luddic_path ↔ tritachyon | **HOSTILE** |
| path ↔ hegemony | luddic_path ↔ hegemony | **HOSTILE** |
| pirates ↔ tritachyon | pirates ↔ tritachyon | **HOSTILE** |
| pirates ↔ hegemony | pirates ↔ hegemony | **HOSTILE** |
| path ↔ pirates | luddic_path ↔ pirates | NEUTRAL |
| hegemony ↔ tritachyon | hegemony ↔ tritachyon | **VOLATILE** |

4 hostile, 1 neutral, 1 volatile. Cindervast is already active. The gap here is smaller.

**Across all 3 territories: 7 hostile pairs, 7 neutral pairs, 1 volatile pair.** Half the pair space is inert.

---

## Design Principles for New Content

1. **Neutral ≠ friendly.** Neutral subfactions aren't allies — they're strangers sharing a frontier. They have competing interests even without shooting.
2. **Competition over cooperation.** The territory has limited base slots, limited system control, and limited growth headroom. Neutral subfactions should feel that scarcity.
3. **Escalation ladders.** New entanglement types should form a spectrum: cooperation → tension → hostility. Moving along that spectrum should happen through discrete visible events, not invisible drift.
4. **Every entanglement creates future problems.** Cooperation creates dependency or resentment. Competition creates grudges. Even positive-sum deals should have a backlash vector.
5. **Player legibility.** The player should always be able to see what's happening and understand why. "These two factions are competing for control of this system" is clear. "Diplomatic modifier -0.3" is not.

---

## New Entanglement Types

### Trade Compact

Two non-hostile subfactions establish formalized trade between their base systems, generating mutual economic benefit.

**Trigger:**
- Two subfactions are both ESTABLISHED+ in the same territory, NOT hostile, and neither is under active threat (no hostile entanglements on either).
- A successful patrol by one passes through the other's base system (proximity + stability signal).

**Hostility Effect:** None (neutral).

**Active Effects:**
- Both subfactions gain a small recurring leverage factor ("trade income") for the duration.
- Trade convoy fleets run between the two base systems — visible, attackable by the player or third parties.
- The pair's relationship is visibly warm: intel says "active trade agreement" and convoys appear on the map.

**Duration:** 60–120 days, renewable if both remain stable and non-hostile.

**Consequences:**
- Creates dependency — if one subfaction is demoted/weakened, the other loses its trade bonus. This gives the beneficiary incentive to protect or intervene (potential trigger for Hired Protection).
- Third-party hostile subfactions can raid trade convoys. Successful convoy raiding damages the compact and generates resentment against the raider.
- If either partner is attacked by a third party, the other faces a choice: protect the trade relationship (intervention) or stay neutral and lose the compact when the partner weakens.

**Why it matters:** Gives neutral pairs a reason to interact. Creates visible fleet activity (convoys). Generates secondary drama when hostiles threaten the trade route.

---

### Rivalry

Two non-hostile subfactions are competing for the same expansion space — not shooting, but actively working against each other's growth.

**Trigger:**
- Two non-hostile subfactions are both at FORTIFIED+ in the same territory with limited remaining base slots.
- OR both attempt Expansion ops within the same evaluation window.
- OR one subfaction reaches DOMINANT while the other is at FORTIFIED (the remaining power resents the leader).

**Hostility Effect:** None (still non-hostile). This is cold competition, not war.

**Active Effects:**
- Both subfactions gain a pressure factor against the other ("competitive pressure").
- Patrol routes are biased toward systems claimed by the rival — more encounters, more friction.
- Increased probability that patrol failures near the rival's systems are attributed to interference (pressure factor generation).
- Intel framing: "tensions rising between X and Y as both compete for dominance."

**Duration:** Condition-based — persists while both remain FORTIFIED+ or until a discrete event resolves it (one is demoted, a council intervenes, or escalation to Territorial War).

**Consequences:**
- If both remain FORTIFIED+ for extended time, Rivalry can **escalate to Territorial War** — the tension boils over. This is the primary neutral→hostile escalation path.
- If one is demoted, the Rivalry ends and the winner gains a leverage factor.
- A Council op can resolve a Rivalry diplomatically (one possible Selective Pacts outcome).
- Third parties may exploit the Rivalry: offering Proxy Support to one side, or forming opportunistic Trade Compacts with the distracted rivals.

**Why it matters:** The missing middle ground between "peaceful coexistence" and "Territorial War." Creates tension and drama between neutral pairs without requiring a hostility baseline. Gives FORTIFIED subfactions something to worry about besides just growing to DOMINANT.

---

### Non-Aggression Pact

Two baseline-hostile subfactions formally agree to cease hostilities, creating a fragile peace backed by mutual interest.

**Trigger:**
- Council outcome: Détente or Selective Pacts.
- OR both subfactions are weakened (ESTABLISHED after being demoted from FORTIFIED+) and neither has the strength to sustain a war.
- OR one subfaction offers an NAP after losing a Territorial War (suing for peace).

**Hostility Effect:** Suppresses hostility (like Shared-Enemy Pact, but without a shared enemy).

**Active Effects:**
- Patrols from both subfactions ignore each other (no hostile encounters).
- Raid ops cannot be launched against the NAP partner.
- A fragile stability — both can rebuild in peace.

**Duration:** 60–120 days, with a "violation" trigger.

**Consequences:**
- If either subfaction launches a raid or hostile op against the partner, the NAP is **violated** — immediate replacement with Territorial War plus a heavy "betrayal" pressure factor on the violator and a sympathy leverage factor on the victim.
- Third parties may resent the NAP (enemies of one partner now face both). This could trigger Proxy Support or Retaliation Coalition from excluded subfactions.
- NAPs create political rigidity: the territory's alliance structure becomes more fixed, which may frustrate players who want to disrupt the balance.

**Why it matters:** Gives hostile pairs an off-ramp. Currently, once two subfactions are hostile, they stay that way until one is obliterated. NAPs create diplomatic cycles (war → exhaustion → peace → betrayal → war) that are more interesting than permanent conflict.

---

### Territorial Claim

A subfaction unilaterally claims a system or base slot currently held or claimed by another, creating a focused dispute.

**Trigger:**
- A subfaction at FORTIFIED+ targets a specific system for expansion that another subfaction already considers "theirs" (has a base in that system, or has patrol routes through it).
- OR a newly-promoted subfaction claims a system adjacent to another's base.

**Hostility Effect:** None initially. Escalates to hostile if unresolved.

**Active Effects:**
- The claimant sends "survey" or "assertion" fleets to the contested system — not combat fleets, but their presence is provocative.
- The holder responds with increased patrols in the contested system.
- Creates escalating pressure factors on both sides.
- Intel framing: "X is contesting Y's claim to [system name]."

**Duration:** 30–60 days. Resolves into one of three outcomes:
1. **Claimant backs down** — ends with a minor leverage factor for the holder.
2. **Holder concedes** — the claimant gains the slot/system, holder takes a pressure factor but avoids war.
3. **Escalation** — neither backs down, claim converts to a Territorial War entanglement.

**Consequences:**
- Adjacent to Rivalry but more focused — about a specific system/slot, not general competition.
- Creates a visible flashpoint the player can interact with (defend one side, etc.).
- A potential trigger for third-party entanglements: others may take sides.

**Why it matters:** Most territorial conflicts in the real world start with specific claims, not general hostility. This gives neutral pairs a concrete escalation path through a specific, visible dispute.

---

### Détente

A formal period of reduced tensions following a resolved conflict, imposed by exhaustion or a Council resolution.

**Trigger:**
- Council outcome: Détente.
- OR a Territorial War ends with one side demoted — the winner and loser enter a cooling-off period.

**Hostility Effect:** Suppresses hostility for the duration.

**Active Effects:**
- No raids or hostile ops between the pair.
- Reduced HOSTILE_PRESENCE factor generation.
- Visible as "peace treaty" or "ceasefire" in intel.

**Duration:** 30–60 days. Shorter than an NAP — this is imposed, not negotiated.

**Consequences:**
- Expiry reverts to baseline. If the baseline is HOSTILE, hostilities resume automatically unless a new entanglement (NAP, Trade Compact) is established during the détente window.
- Creates a window of opportunity for players: during détente, other events could form lasting peace (or you could sabotage it to restart the war).

**Why it matters:** Provides breathing room in heavily contested territories. Creates natural rhythm: war → détente → new equilibrium (or new war). The temporary nature means it doesn't permanently resolve anything — just defers it.

---

## New Op Types

### Trade Convoy

Freight fleet running between two allied/cooperative base systems.

**Trigger:** Trade Compact entanglement is active.

**Fleet:** Lightly armed freighter convoy traveling between the two partners' base systems. Follows a fixed route. Vulnerable to interception.

**Resolution:**
- Arrival: both partners gain a small leverage factor ("trade profit").
- Intercepted/destroyed: Trade Compact takes damage (timer reduction). Whichever subfaction destroyed the convoy gains a "raider" tag that may trigger retaliation.

**Why it matters:** Creates visible economic activity between neutral pairs. Gives hostile subfactions (and players) something to attack that has political consequences beyond combat.

---

### Provocation Patrol

A subfaction sends a patrol deliberately close to or through a rival's base system during a Rivalry or Territorial Claim entanglement. Not an attack — a show of force.

**Trigger:** Rivalry or Territorial Claim entanglement is active.

**Fleet:** Standard patrol fleet, but routed through the rival's systems rather than neutral ones.

**Resolution:**
- If the patrol completes without incident: generates pressure on the rival ("intimidated") and leverage on the sender ("projected strength").
- If the patrol is destroyed: generates a casus belli — Rivalry can escalate to Territorial War, Territorial Claim can instantly escalate.
- If the rival ignores it: nothing changes, but repeated provocations compound pressure.

**Why it matters:** The fleet-level expression of a Rivalry. Players see these patrols entering "enemy" space and understand the political situation is deteriorating.

---

### Covert Sabotage

A subfaction covertly degrades a rival's base or logistics without open war.

**Trigger:** Rivalry or Proxy Support entanglement exists. OR a subfaction is in a Territorial Claim dispute.

**Fleet:** Small, fast, stealthy fleet (or abstracted as a non-fleet op — just a timer + roll). Not directly visible to the player unless they're in the target system.

**Resolution:**
- Success: Target gains a pressure factor ("infrastructure degraded", "supply disruption"). Source is not immediately identified.
- Failure: Nothing happens, OR the sabotage is detected — attribution creates a casus belli, potentially escalating Rivalry to Territorial War.
- Critical success: Target's base is temporarily disabled (reduced fleet production or repair capability for X days).

**Why it matters:** A tool for non-hostile subfactions to compete below the threshold of open war. Creates hidden drama that surfaces through consequences. Discovery is a major escalation event.

---

### Diplomatic Envoy

A subfaction sends an emissary fleet to another's base to negotiate — could lead to Trade Compact, NAP, or resolution of a Rivalry/Claim.

**Trigger:**
- Either subfaction is under pressure and wants to resolve a negative entanglement.
- OR two neutral subfactions with no entanglement at all — proactive relationship building.
- Less likely than Councils, more targeted (pair-level, not territory-wide).

**Fleet:** Single light/transport fleet traveling to the partner's base system. Non-combat.

**Resolution:**
- Success: New cooperative entanglement forms (Trade Compact, NAP, or dispute resolution).
- Failure: No agreement. The envoy returns. Minor pressure factor on the rejected side.
- Sabotaged: If a third party destroys the envoy fleet en route, the negotiation fails and the sender blames the third party (or the intended partner, if attribution is ambiguous).

**Why it matters:** The proactive, bottom-up counterpart to the Council op. Councils are territory-wide crisis responses. Envoys are bilateral relationship-building. Gives players something to protect or destroy for targeted political manipulation.

---

### Arms Buildup

A subfaction reinforces its military capacity in response to a Rivalry, Territorial Claim, or the other's Expansion/Supremacy.

**Trigger:** Rivalry or Territorial Claim is active, OR a rival just reached FORTIFIED/DOMINANT.

**Fleet:** Reinforcement convoy arriving at the subfaction's base. Not an attack — just buildup.

**Resolution:**
- Success: Subfaction's fleet strength temporarily increases (fleet size mult bonus) for the duration of the associated entanglement. The buildup is visible to the player and the rival.
- Failure (convoy intercepted): Buildup fails. Subfaction takes a pressure factor. Potential escalation.

**Why it matters:** Arms races are a classic pre-war dynamic. Visible buildups on both sides of a Rivalry create mounting tension the player can see and interact with.

---

## Interaction: How New Content Chains Together

### Neutral Pair Escalation Ladder

```
NEUTRAL COEXISTENCE
     │
     ├─── (both FORTIFIED+) ───→ RIVALRY
     │                              │
     │                ┌─────────────┼──────────────┐
     │                ▼             ▼              ▼
     │         Provocation    Covert         Arms Buildup
     │          Patrols      Sabotage
     │                │             │              │
     │                └─────────────┼──────────────┘
     │                              ▼
     │                      TERRITORIAL WAR
     │                              │
     │                    ┌─────────┴─────────┐
     │                    ▼                   ▼
     │             One side wins        Mutual exhaustion
     │                    │                   │
     │                    ▼                   ▼
     │               DÉTENTE              COUNCIL
     │                    │                   │
     │                    ▼                   ▼
     │         (baseline reverts)    NAP / Trade Compact
     │                    │              / Rivalry again
     │                    ▼
     │           NEW CYCLE BEGINS
     │
     ├─── (patrol proximity) ───→ TRADE COMPACT
     │                                  │
     │                   ┌──────────────┤
     │                   ▼              ▼
     │             Convoys run     Convoys raided
     │             (leverage)      (compact damaged)
     │                   │              │
     │                   ▼              ▼
     │          Dependency grows   Compact breaks →
     │          (partner stakes)   resentment → RIVALRY
     │
     └─── (envoy op) ───→ DIPLOMATIC ENVOY
                                │
                      ┌─────────┴──────────┐
                      ▼                    ▼
               NAP / Trade Compact    Rejection / sabotaged
                                           │
                                           ▼
                                     Tension / blame
```

### Key Loops

1. **Competition loop:** neutral → Rivalry → Provocation/Sabotage → Territorial War → winner/loser → Détente → new baseline → neutral or new Rivalry.
2. **Economy loop:** neutral → Trade Compact → convoys → raided → compact breaks → Rivalry → war.
3. **Diplomacy loop:** envoy → NAP or Trade Compact → expiration → reassessment → new envoy or escalation.
4. **War-exhaustion loop:** Territorial War → mutual demotion → NAP/Détente → rebuilding → Rivalry → war again.

---

## Revisiting Existing Entanglements

### Retaliation Coalition — needs implementation

Currently defined but not triggered in the sim. With the new neutral dynamics, Retaliation Coalition becomes more interesting: a neutral subfaction could intervene when a hostile one overreaches, even if they wouldn't normally care about the victim.

### Proxy Support — needs implementation

Also defined but not triggered. With Rivalries and Trade Compacts creating visible alliances, Proxy Support becomes a natural next step: subfaction A backs subfaction B against B's rival, without committing directly.

### Civil War — rare but possible

Same-parent subfactions (currently impossible in the territory configs since no territory has two subfactions from the same parent). If future territories include overlapping parent factions, Civil War becomes active.

---

## Priority: What to Build First

**Tier 1 — Rivalry + Provocation Patrol.** These fix the core problem: neutral pairs do nothing. Rivalry gives FORTIFIED+ neutral pairs something to care about, and Provocation Patrol is the fleet-level expression. Together they create the neutral→hostile escalation path.

**Tier 2 — Trade Compact + Trade Convoy.** These create visible economic cooperation between neutral pairs. The convoys are attackable, creating drama even in "peaceful" territories like Shardfields.

**Tier 3 — NAP + Détente.** These create the hostile→neutral off-ramp. Currently there's no way for hostile pairs to de-escalate except through Council Détente. NAP and Détente give pairs their own bilateral path back to peace.

**Tier 4 — Territorial Claim, Covert Sabotage, Diplomatic Envoy, Arms Buildup.** These add depth and variety to the escalation/de-escalation pathways but aren't structurally necessary for the core loop.

---

## Open Questions

- Should Trade Compacts be available between hostile pairs that have an NAP? ("Trading with the enemy" — historically common, politically volatile.)
- Should Rivalry be strictly FORTIFIED+, or should ESTABLISHED subfactions also compete when base slots are scarce?
- How visible should Covert Sabotage be? Fully hidden until discovery? Or visible to the player but hidden from the target? The player-as-observer angle is interesting.
- Should Provocation Patrols use the same fleet composition as regular patrols, or should they be visibly different (larger, more aggressive posture)?
- Can the player trigger or influence Diplomatic Envoys in Milestone 4? ("Commission a diplomatic mission" as a player action.)
- Should arms buildups give permanent fleet size bonuses, or temporary ones tied to the entanglement duration?
- How does the evaluation cycle interact with Rivalry? Does Rivalry suppress or redirect Expansion/Supremacy ops?
