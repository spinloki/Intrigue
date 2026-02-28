package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: assault an enemy subfaction's territory base to take it over.
 *
 * <p>Triggered when a subfaction wants to establish in a territory but all base
 * slots are claimed. The attacker targets the ESTABLISHED subfaction in that
 * territory (from a hostile faction) with the lowest territory cohesion.</p>
 *
 * <p>On success: the defender is expelled from the territory (presence → NONE,
 * slot released), and the attacker's presence advances to ESTABLISHED with
 * the captured slot. On failure: the attacker's presence reverts to NONE.</p>
 *
 * <p>Uses a single combat phase — the attacker's fleet raids the defender's
 * territory base market. This is a more aggressive and costly operation than
 * a normal establish-base op.</p>
 */
public class AssaultTerritoryBaseOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(AssaultTerritoryBaseOp.class.getName());

    private static final int INITIAL_TERRITORY_COHESION = 40;
    private static final int HOME_COHESION_COST = 12;
    private static final int LEGITIMACY_GAIN_SUCCESS = 8;
    private static final int LEGITIMACY_LOSS_FAILURE = 6;
    private static final int DEFENDER_LEGITIMACY_LOSS = 15;

    private final String attackerSubfactionId;
    private final String defenderSubfactionId;

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

        // Single timed phase (in real game, this would be a fleet combat phase)
        phases.add(new TimedPhase("Assaulting territory base", 30f));
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
        if (territory.getPresence(defenderSubfactionId) != IntrigueTerritory.Presence.ESTABLISHED) {
            log.info("AssaultTerritoryBaseOp " + getOpId()
                    + " aborted: defender " + defenderSubfactionId + " no longer established.");
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        // Base success chance: 50%, modified by relative cohesion
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;

        int defCoh = territory != null ? territory.getCohesion(defenderSubfactionId) : 50;
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        int atkCoh = attacker != null ? attacker.getHomeCohesion() : 50;

        // Higher attacker cohesion and lower defender cohesion = better odds
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
            // Expel the defender
            territory.removeSubfaction(defenderSubfactionId);
            if (defender != null) {
                defender.setLegitimacy(defender.getLegitimacy() - DEFENDER_LEGITIMACY_LOSS);
            }

            // Attacker takes over — claim a free slot (the one just released)
            java.util.List<IntrigueTerritory.BaseSlot> freeSlots = territory.getFreeSlots();
            if (!freeSlots.isEmpty()) {
                territory.claimSlot(freeSlots.get(0), attackerSubfactionId);
            }

            territory.setPresence(attackerSubfactionId, IntrigueTerritory.Presence.ESTABLISHED);
            territory.setCohesion(attackerSubfactionId, INITIAL_TERRITORY_COHESION);
            // Set a synthetic base market ID (real market creation would be in game-side variant)
            territory.setBaseMarketId(attackerSubfactionId,
                    "assault_base_" + attackerSubfactionId + "_" + System.currentTimeMillis());

            if (attacker != null) {
                attacker.setHomeCohesion(attacker.getHomeCohesion() - HOME_COHESION_COST);
                attacker.setLegitimacy(attacker.getLegitimacy() + LEGITIMACY_GAIN_SUCCESS);
            }

            log.info("AssaultTerritoryBaseOp SUCCESS: " + attackerSubfactionId
                    + " took over from " + defenderSubfactionId + " in " + territoryName);
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


