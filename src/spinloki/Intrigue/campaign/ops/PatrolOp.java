package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: a subfaction sends a patrol fleet from its home market to roam
 * one of its parent faction's territories.
 *
 * <p>This is a self-op scoped to a territory. The fleet is spawned from the
 * subfaction's home market, patrols the area, and returns. If it survives,
 * the subfaction gains a small amount of Legitimacy. If the fleet is
 * destroyed, the subfaction <em>loses</em> Legitimacy instead.</p>
 *
 * <p>Note: ambient territory patrols spawning from the territory base are
 * handled separately by {@code TerritoryPatrolScript}, not by this op.</p>
 */
public class PatrolOp extends IntrigueOp {

    private static final long serialVersionUID = 2L;
    private static final Logger log = Logger.getLogger(PatrolOp.class.getName());

    private static final int LEGITIMACY_GAIN = 3;
    private static final int LEGITIMACY_LOSS = 4;
    private static final int SABOTAGE_EXTRA_LEGITIMACY_LOSS = 3;
    private static final float PATROL_DAYS = 20f;

    private final String subfactionId;
    private final PatrolPhase patrolPhase;
    private boolean sabotaged = false;

    /**
     * @param opId        unique operation ID
     * @param subfaction  the subfaction launching the patrol
     * @param territoryId territory the patrol is associated with
     */
    public PatrolOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId,
              subfaction.getLeaderId(),
              null,                         // no target person
              subfaction.getSubfactionId(),
              null);                        // no target subfaction
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        // Intel arrow: home market -> territory
        setIntelSourceMarketId(subfaction.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        // Fleet strength scales with home cohesion: 20 FP at 0, 80 FP at 100
        int combatFP = 20 + (int) (subfaction.getHomeCohesion() * 0.6f);

        this.patrolPhase = new PatrolPhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                combatFP,
                PATROL_DAYS,
                subfaction.getName());
        phases.add(patrolPhase);
    }

    @Override
    public String getOpTypeName() {
        return "Patrol";
    }

    // ── Mischief targeting ────────────────────────────────────────────

    @Override
    public boolean canBeTargetedByMischief() {
        return !isResolved();
    }

    @Override
    public String describeMischiefEffect() {
        return "Broadcasting confusing signals to disrupt patrols";
    }

    @Override
    public void applyMischiefSabotage() {
        sabotaged = true;
        log.info("PatrolOp " + getOpId() + " sabotaged — increased legitimacy loss on failure");
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        log.info("PatrolOp started: " + subfactionId
                + " (leader " + (leader != null ? leader.getPersonId() : "?") + ")"
                + " patrolling territory " + getTerritoryId());
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories != null) {
            IntrigueTerritory territory = territories.getById(getTerritoryId());
            if (territory == null) return true;
            // Abort if we lost presence in the territory
            if (!territory.getPresence(subfactionId).isEstablishedOrHigher()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        return patrolPhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction subfaction = getInitiatorSubfaction();

        if (subfaction != null) {
            subfaction.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(getTerritoryId()) : null;
        String territoryName = territory != null ? territory.getName() : getTerritoryId();

        if (result == OpOutcome.SUCCESS) {
            if (subfaction != null) {
                subfaction.setLegitimacy(subfaction.getLegitimacy() + LEGITIMACY_GAIN);
            }
            log.info("PatrolOp resolved SUCCESS: " + subfactionId
                    + " legitimacy +" + LEGITIMACY_GAIN
                    + " (territory " + territoryName + ")");
        } else {
            int loss = sabotaged
                    ? LEGITIMACY_LOSS + SABOTAGE_EXTRA_LEGITIMACY_LOSS
                    : LEGITIMACY_LOSS;
            if (subfaction != null) {
                subfaction.setLegitimacy(subfaction.getLegitimacy() - loss);
            }
            log.info("PatrolOp resolved FAILURE: " + subfactionId
                    + " patrol fleet destroyed, legitimacy -" + loss
                    + (sabotaged ? " (sabotaged, extra -" + SABOTAGE_EXTRA_LEGITIMACY_LOSS + ")" : "")
                    + " (territory " + territoryName + ")");
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}

