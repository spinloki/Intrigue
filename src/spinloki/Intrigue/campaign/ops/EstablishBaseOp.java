package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * Operation: a homeless criminal subfaction scouts and establishes a hidden base
 * outside the core worlds.
 *
 * This is a "self-op" — there is no target subfaction. The initiator's leader
 * goes out, finds a suitable unclaimed planet in a non-core system, and sets up
 * a hidden market there.
 *
 * On success, the subfaction's homeMarketId is set to the new base, and all
 * members are placed at it. On failure, the subfaction remains homeless and
 * can try again after cooldown.
 */
public class EstablishBaseOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(EstablishBaseOp.class.getName());

    private static final int POWER_GAIN_ON_SUCCESS = 10;
    private static final int POWER_LOSS_ON_FAILURE = 5;

    private final String initiatorSubfactionId;
    private final EstablishBasePhase basePhase;

    /**
     * @param opId       unique operation ID
     * @param subfaction the criminal subfaction establishing a base
     */
    public EstablishBaseOp(String opId, IntrigueSubfaction subfaction) {
        super(opId,
              subfaction.getLeaderId(),
              null,    // no target person
              subfaction.getSubfactionId(),
              null);   // no target subfaction
        this.initiatorSubfactionId = subfaction.getSubfactionId();
        this.basePhase = new EstablishBasePhase(subfaction.getFactionId(), subfaction.getSubfactionId(), subfaction.getName());
        phases.add(basePhase);
    }

    @Override
    public String getOpTypeName() {
        return "Establish Base";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        if (leader == null) return;

        log.info("EstablishBaseOp started: " + initiatorSubfactionId
                + " (leader " + leader.getPersonId() + ") scouting for a base location");
    }

    @Override
    protected boolean shouldAbort() {
        // Abort if the leader no longer exists
        if (getInitiator() == null) return true;

        // Abort if the subfaction already got a home (e.g. via resolveHomelessSubfactions)
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null && sf.hasHomeMarket()) {
            log.info("EstablishBaseOp " + getOpId() + " aborted: subfaction already has a home market.");
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        return basePhase.didSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction subfaction = getInitiatorSubfaction();

        // Set cooldown
        if (subfaction != null) {
            subfaction.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("EstablishBaseOp resolved: " + getOpId() + " → " + result);

        if (result == OpOutcome.SUCCESS && subfaction != null) {
            String marketId = basePhase.getCreatedMarketId();
            if (marketId != null) {
                // Assign the new base as home
                subfaction.setHomeMarketId(marketId);

                // Place all members at the new base
                var peopleMgr = IntrigueServices.people();
                for (String personId : subfaction.getAllPersonIds()) {
                    IntriguePerson ip = peopleMgr.getById(personId);
                    if (ip != null) {
                        ip.setHomeMarketId(marketId);
                    }
                    peopleMgr.syncMemory(personId);
                }

                // Power boost for successfully establishing a base
                subfaction.setPower(subfaction.getPower() + POWER_GAIN_ON_SUCCESS);

                log.info("  SUCCESS: " + subfaction.getName() + " established base at " + marketId
                        + ", power +" + POWER_GAIN_ON_SUCCESS);
            }
        } else if (result == OpOutcome.FAILURE && subfaction != null) {
            // Minor power loss for failed scouting
            subfaction.setPower(subfaction.getPower() - POWER_LOSS_ON_FAILURE);
            log.info("  FAILURE: " + subfaction.getName() + " failed to find a base location"
                    + ", power -" + POWER_LOSS_ON_FAILURE);
        }

        // Sync leader memory
        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }
}

