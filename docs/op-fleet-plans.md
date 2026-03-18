# Op Fleet Implementation Plans

Each section describes the in-game fleet behavior for an Op type.
The decision layer (TerritoryState) already handles timing, success/failure,
and factor generation. These plans cover the **execution layer**: what the
player sees in the campaign map.

---

## 1. EXPANSION / SUPREMACY (TerritoryReinforcementIntel)

**Status:** Intel exists (TerritoryReinforcementIntel), but market selection is
broken — it currently picks a subfaction market instead of the parent faction's
market.

**Fix:** The source market for the reinforcement fleet must be chosen from the
**parent faction**, not the subfaction. The subfaction doesn't own markets; the
parent faction does.

**Behavior:** A reinforcement fleet spawns from a parent-faction market and
travels to the subfaction's base in the territory. On arrival, the
promotion transition (ESTABLISHED → FORTIFIED or FORTIFIED → DOMINANT) is
applied.

---

## 2. PROTECTION_PATROL

**Trigger:** Hired Protection entanglement — the Protector defends the Hirer.

**Behavior:**
- **3 patrol fleets** from the Protector fly around the Hirer's base system
  on standard patrol routes.
- One much larger **dedicated protection fleet** orbits the **Hirer's base**
  directly.
- All fleets despawn when the op resolves (success or failure).

---

## 3. JOINT_STRIKE

**Trigger:** Shared-Enemy Pact entanglement — two subfactions coordinate
against a mutual enemy.

**Behavior:**
- Several large fleets from **both** subfactions in the pact travel to a
  **staging point at a jump point in the target system**.
- Once assembled, the combined force travels together to the enemy
  subfaction's base to raid it.
- On success, heavy pressure on the target. On failure, pressure on the
  striking subfaction.

---

## 4. WAR_RAID

**Trigger:** Territorial War entanglement — one side hunts the other's patrols.

**Behavior:**
- When the target subfaction starts a PATROL op, the raiding subfaction
  spawns a **hunter fleet**.
- The hunter fleet travels to the **same target system** as the patrol.
- The hunter fleet gets an **explicit intercept order** on the patrol fleet
  entity — it actively chases the patrol, not just coincidentally patrolling
  the same system.

---

## 5. ARMS_SHIPMENT

**Trigger:** Proxy Support entanglement — the backer sends arms to the backed
subfaction.

**Behavior:**
- A **supply convoy** fleet (soft target, no escorts) spawns from the backer
  subfaction's base.
- The convoy travels to the backed subfaction's base system.
- On arrival, it turns around and returns to origin, like a typical trading
  fleet round-trip.
- While in transit, the backed subfaction's combat ops (RAID, WAR_RAID,
  JOINT_STRIKE) get a 1.3× success boost via `applyOpInteractions()`.

**Player interaction:**
- The convoy is a **soft target** — no dedicated escorts.
- Flies as **Independent** with **transponder off** and `$lowRepImpact` — looks
  like an unremarkable Independent trade fleet. This mirrors how vanilla
  handles smugglers: Independent is neutral to all major factions, so the
  convoy won't be attacked en route. The "covert" flavor is preserved since
  it's an unmarked convoy with no visible ties to the backer.
- The player can identify the convoy via intel and **destroy it to fail the
  op immediately**.

---

## 6. INVASION

**Trigger:** Stagnation — a subfaction stuck at ESTABLISHED for 180+ days
attracts an external invader.

**Behavior:**
- Uses **FleetGroupIntel** (vanilla FGI system) — multiple large fleets from
  the invader's **parent faction** all travel together to the target
  subfaction's base system.
- Fleets spawn from the invader's parent-faction market (same pattern as
  EXPANSION/SUPREMACY).
- On success, the target is evicted and the invader takes over the slot at
  ESTABLISHED.
- On failure, the target gets a pressure factor.

---

## 7. COUNCIL

**Trigger:** Territory-wide council op — emissary fleets negotiate, may
rewrite the entanglement map.

**Behavior:**
- Fleets from **each subfaction** in the territory travel to a **single POI**
  in a system where **no subfaction bases are present**.
- Fleet size scales with the number of hostile subfaction pairs in the
  territory (more conflict → bigger delegations).
- **Subfaction relations are temporarily overridden to non-hostile** during
  the council so that council fleets don't attack each other, even if the
  subfactions they represent are at war.
- On resolution, the entanglement map may be rewritten (alliances shift,
  wars end, new pacts form).

**Fallback:** If all systems in the territory have bases, fall back to the
least-occupied system. (Future: territory generation will guarantee at least
N+1 systems for N base slots, ensuring a base-free system always exists.)
