package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: assault an enemy subfaction's territory base to weaken their hold.
 *
 * <p>Triggered when a subfaction wants to establish in a territory but all base
 * slots are claimed. The attacker targets the subfaction in that
 * territory (from a hostile faction) with the lowest territory cohesion.</p>
 *
 * <p>Sends an attack fleet from the attacker's home market to the defender's
 * territory base. If the fleet survives (route expires without the player
 * destroying it, or player destroys the defenders), the assault succeeds.
 * If the fleet is destroyed by the player, it fails.</p>
 *
 * <p>On success: the defender's presence is demoted by one tier
 * (DOMINANT→FORTIFIED→ESTABLISHED→NONE). If the defender drops to NONE, their
 * slot is freed and the attacker claims it, advancing to ESTABLISHED.</p>
 *
 * <p>On failure: the attacker's presence reverts to NONE.</p>
 */
public class AssaultTerritoryBaseOp extends IntrigueOp {

    private static final long serialVersionUID = 2L;
    private static final Logger log = Logger.getLogger(AssaultTerritoryBaseOp.class.getName());

    private static final int INITIAL_TERRITORY_COHESION = 40;
    private static final int HOME_COHESION_COST = 12;
    private static final int LEGITIMACY_GAIN_SUCCESS = 8;
    private static final int LEGITIMACY_LOSS_FAILURE = 6;
    private static final int DEFENDER_LEGITIMACY_LOSS = 15;

    private final String attackerSubfactionId;
    private final String defenderSubfactionId;

    /** The fleet combat phase, or null if we fell back to a timed stub. */
    private final TravelAndFightPhase combatPhase;

    /**
     * @param opId               unique operation ID
     * @param attacker           the subfaction launching the assault
     * @param defender           the subfaction being assaulted
     * @param territoryId        the contested territory
     */
    public AssaultTerritoryBaseOp(String opId, IntrigueSubfaction attacker,
                                  IntrigueSubfaction defender, String territoryId) {
        super(opId,
              attacker.getLeaderId(),
              defender.getLeaderId(),
              attacker.getSubfactionId(),
              defender.getSubfactionId());
        this.attackerSubfactionId = attacker.getSubfactionId();
        this.defenderSubfactionId = defender.getSubfactionId();
        setTerritoryId(territoryId);

        // Intel arrow: attacker home → territory
        setIntelSourceMarketId(attacker.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        // Fleet strength scales with attacker cohesion
        int combatFP = 30 + (int) (attacker.getHomeCohesion() * 1.0f);

        // Try to resolve the defender's base market for the fleet to attack
        String targetMarketId = resolveDefenderBaseMarket(defender, territoryId);

        if (targetMarketId != null) {
            this.combatPhase = new TravelAndFightPhase(
                    attacker.getFactionId(),
                    attacker.getHomeMarketId(),
                    targetMarketId,
                    combatFP,
                    attacker.getName(),
                    true);  // despawn at target — fleet stays at the captured base
            phases.add(combatPhase);
        } else {
            // No valid market to attack — fall back to abstract timed phase
            log.warning("AssaultTerritoryBaseOp: no valid defender base market for "
                    + defenderSubfactionId + " in territory " + territoryId
                    + "; using abstract resolution.");
            this.combatPhase = null;
            phases.add(new TimedPhase("Assaulting territory base", 30f));
        }
    }

    /**
     * Resolve the defender's base market in the territory. First checks the
     * territory's recorded base market, then falls back to the defender's
     * home market.
     */
    private static String resolveDefenderBaseMarket(IntrigueSubfaction defender,
                                                    String territoryId) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory territory = territories.getById(territoryId);
            if (territory != null) {
                String baseMarketId = territory.getBaseMarketId(defender.getSubfactionId());
                if (baseMarketId != null && !baseMarketId.isEmpty()) {
                    // Verify it's a real market (not a synthetic placeholder ID)
                    if (PhaseUtil.isSectorAvailable()) {
                        com.fs.starfarer.api.campaign.econ.MarketAPI market =
                                com.fs.starfarer.api.Global.getSector().getEconomy().getMarket(baseMarketId);
                        if (market != null && market.getPrimaryEntity() != null) {
                            return baseMarketId;
                        }
                    }
                }
            }
        }
        // Fall back to defender's home market
        String homeMarketId = defender.getHomeMarketId();
        if (homeMarketId != null && !homeMarketId.isEmpty()) {
            return homeMarketId;
        }
        return null;
    }

    @Override
    public String getOpTypeName() {
        return "Assault Territory Base";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        log.info("AssaultTerritoryBaseOp started: " + attackerSubfactionId
                + " (leader " + (leader != null ? leader.getPersonId() : "?") + ")"
                + " assaulting " + defenderSubfactionId
                + " in territory " + getTerritoryId());
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return true;

        IntrigueTerritory territory = territories.getById(getTerritoryId());
        if (territory == null) return true;

        // Abort if the defender is no longer established (someone else took them out)
        if (!territory.getPresence(defenderSubfactionId).isEstablishedOrHigher()) {
            log.info("AssaultTerritoryBaseOp " + getOpId()
                    + " aborted: defender " + defenderSubfactionId + " no longer established.");
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        // If we had a real fleet phase, use its result
        if (combatPhase != null) {
            return combatPhase.didFleetWin() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
        }

        // Abstract fallback: base success chance 50%, modified by relative cohesion
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;

        int defCoh = territory != null ? territory.getCohesion(defenderSubfactionId) : 50;
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        int atkCoh = attacker != null ? attacker.getHomeCohesion() : 50;

        float chance = 0.50f + (atkCoh - defCoh) * 0.003f;
        chance = Math.max(0.2f, Math.min(0.8f, chance));

        return Math.random() < chance ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        IntrigueSubfaction defender = IntrigueServices.subfactions().getById(defenderSubfactionId);

        if (attacker != null) {
            attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;
        String territoryName = territory != null ? territory.getName() : getTerritoryId();

        if (result == OpOutcome.SUCCESS && territory != null) {
            // Demote the defender by one tier
            IntrigueTerritory.Presence defenderBefore = territory.getPresence(defenderSubfactionId);
            IntrigueTerritory.Presence defenderAfter = territory.demotePresence(defenderSubfactionId);
            if (defender != null) {
                defender.setLegitimacy(defender.getLegitimacy() - DEFENDER_LEGITIMACY_LOSS);
            }

            // If the defender was fully removed (demoted to NONE), attacker claims the freed slot
            if (defenderAfter == IntrigueTerritory.Presence.NONE) {
                java.util.List<IntrigueTerritory.BaseSlot> freeSlots = territory.getFreeSlots();
                if (!freeSlots.isEmpty()) {
                    territory.claimSlot(freeSlots.get(0), attackerSubfactionId);
                }
                territory.setPresence(attackerSubfactionId, IntrigueTerritory.Presence.ESTABLISHED);
                territory.setCohesion(attackerSubfactionId, INITIAL_TERRITORY_COHESION);
                territory.setBaseMarketId(attackerSubfactionId,
                        "assault_base_" + attackerSubfactionId + "_" + System.currentTimeMillis());
            }
            // If the defender was only demoted (e.g. FORTIFIED→ESTABLISHED), they keep their slot.
            // The attacker weakened them but didn't dislodge them yet.

            if (attacker != null) {
                attacker.setHomeCohesion(attacker.getHomeCohesion() - HOME_COHESION_COST);
                attacker.setLegitimacy(attacker.getLegitimacy() + LEGITIMACY_GAIN_SUCCESS);
            }

            log.info("AssaultTerritoryBaseOp SUCCESS: " + attackerSubfactionId
                    + " demoted " + defenderSubfactionId + " from " + defenderBefore + " to " + defenderAfter
                    + " in " + territoryName);
        } else {
            // Failed assault — revert attacker's presence, keep defender
            if (territory != null) {
                territory.setPresence(attackerSubfactionId, IntrigueTerritory.Presence.NONE);
                territory.releaseSlot(attackerSubfactionId);
            }

            if (attacker != null) {
                attacker.setHomeCohesion(attacker.getHomeCohesion() - HOME_COHESION_COST / 2);
                attacker.setLegitimacy(attacker.getLegitimacy() - LEGITIMACY_LOSS_FAILURE);
            }

            log.info("AssaultTerritoryBaseOp FAILURE: " + attackerSubfactionId
                    + " failed to assault " + defenderSubfactionId + " in " + territoryName);
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}


