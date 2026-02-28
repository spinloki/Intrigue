package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: mischief triggered by territory friction.
 *
 * <p>The initiator sabotages the victim's operations in a shared territory.
 * On success, the victim suffers territory cohesion and legitimacy penalties.
 * On failure, nothing happens - the mischief fizzled.</p>
 *
 * <p>This op is always free ({@code noCost = true}) and is triggered
 * externally when friction crosses a threshold, similar to vulnerability raids.</p>
 */
public class MischiefOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(MischiefOp.class.getName());

    private static final int COHESION_PENALTY = 5;
    private static final int LEGITIMACY_PENALTY = 3;

    private final String victimSubfactionId;
    private final String targetOpId;
    /** The initiator's directed friction toward the victim at the moment this op was created. */
    private final int initiatorFrictionAtCreation;

    public MischiefOp(String opId, IntrigueSubfaction initiator,
                      IntrigueSubfaction victim, String territoryId,
                      IntrigueOp targetOp) {
        super(opId,
              initiator.getLeaderId(),
              victim.getLeaderId(),
              initiator.getSubfactionId(),
              victim.getSubfactionId());
        this.victimSubfactionId = victim.getSubfactionId();
        this.targetOpId = targetOp != null ? targetOp.getOpId() : null;
        setTerritoryId(territoryId);
        setNoCost(true);

        // Intel arrow: initiator home -> victim home
        setIntelSourceMarketId(initiator.getHomeMarketId());
        setIntelDestinationMarketId(victim.getHomeMarketId());

        // Capture the initiator's friction before OpEvaluator resets it
        int capturedFriction = 0;
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory t = territories.getById(territoryId);
            if (t != null) {
                capturedFriction = t.getFriction(initiator.getSubfactionId(), victim.getSubfactionId());
            }
        }
        this.initiatorFrictionAtCreation = capturedFriction;

        // Quick op - resolves after a short phase
        phases.add(new AssemblePhase(initiator.getHomeCohesion()));
        phases.add(new ReturnPhase(3f));
    }

    @Override
    public String getOpTypeName() {
        return "Mischief";
    }

    @Override
    protected void onStarted() {
        log.info("MischiefOp started: " + getInitiatorSubfactionId()
                + " causing mischief against " + victimSubfactionId
                + " in territory " + getTerritoryId()
                + (targetOpId != null ? " (targeting op " + targetOpId + ")" : ""));
    }

    @Override
    protected OpOutcome determineOutcome() {
        // In game mode, success is based on whether the target op is still active
        // and the initiator's cohesion advantage. For now, use a simple check.
        IntrigueSubfaction initiator = getInitiatorSubfaction();
        IntrigueSubfaction victim = IntrigueServices.subfactions().getById(victimSubfactionId);
        if (initiator == null || victim == null) return OpOutcome.FAILURE;

        // Higher cohesion in the territory gives a better chance
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory t = territories.getById(getTerritoryId());
            if (t != null) {
                int initCoh = t.getCohesion(getInitiatorSubfactionId());
                int victCoh = t.getCohesion(victimSubfactionId);
                if (initCoh > victCoh) return OpOutcome.SUCCESS;
            }
        }
        // Coin flip if cohesion is equal or can't be determined
        return Math.random() < 0.5 ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction initiator = getInitiatorSubfaction();
        if (initiator != null) {
            initiator.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("MischiefOp resolved: " + getOpId() + " → " + result
                + " territory=" + getTerritoryId());

        if (result == OpOutcome.SUCCESS) {
            IntrigueSubfaction victim = IntrigueServices.subfactions().getById(victimSubfactionId);
            if (victim != null) {
                victim.setLegitimacy(victim.getLegitimacy() - LEGITIMACY_PENALTY);
            }
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory t = territories.getById(getTerritoryId());
                if (t != null) {
                    int current = t.getCohesion(victimSubfactionId);
                    t.setCohesion(victimSubfactionId, current - COHESION_PENALTY);

                    // Friction transfer: half the initiator's pre-reset friction is
                    // applied to the victim's friction toward the initiator.
                    // Getting mischief'd makes the victim more annoyed at the initiator.
                    int transfer = initiatorFrictionAtCreation / 2;
                    if (transfer > 0) {
                        int victimFriction = t.getFriction(victimSubfactionId, getInitiatorSubfactionId());
                        t.setFriction(victimSubfactionId, getInitiatorSubfactionId(),
                                victimFriction + transfer);
                        log.info("  Friction transfer: " + victimSubfactionId + "→"
                                + getInitiatorSubfactionId() + " +" + transfer
                                + " (was " + victimFriction + ", now "
                                + t.getFriction(victimSubfactionId, getInitiatorSubfactionId()) + ")");
                    }
                }
            }
            log.info("  SUCCESS: " + victimSubfactionId + " loses " + COHESION_PENALTY
                    + " territory cohesion and " + LEGITIMACY_PENALTY + " legitimacy");
        } else {
            log.info("  FAILURE: mischief fizzled");
        }
    }
}

