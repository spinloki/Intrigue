package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.logging.Logger;

/**
 * "Send a fleet to raid another person's home market."
 *
 * Phases:
 *   1. AssemblePhase  – timed delay scaling with power
 *   2. TravelAndFightPhase – spawn fleet, travel, fight
 *   3. ReturnPhase – cooldown before person is available again
 *
 * Outcome effects:
 *   SUCCESS → attacker gains power, defender loses power, relationship drops
 *   FAILURE → attacker loses power, relationship still drops
 *   ABORTED → minimal effect
 */
public class RaidOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(RaidOp.class.getName());

    private static final int BASE_POWER_SHIFT = 8;
    private static final int REL_DROP_ON_RAID = -15;

    private final String targetMarketId;

    /**
     * @param opId           unique operation ID
     * @param initiatorId    the attacking IntriguePerson's ID
     * @param targetPersonId the defending IntriguePerson's ID
     * @param targetMarketId the market to raid (usually the defender's home market)
     */
    public RaidOp(String opId, String initiatorId, String targetPersonId, String targetMarketId) {
        super(opId, initiatorId, targetPersonId);
        this.targetMarketId = targetMarketId;

        IntriguePerson initiator = getInitiator();
        int power = initiator != null ? initiator.getPower() : 50;

        // Determine fleet strength from power: 30 FP at power 0, 150 FP at power 100
        int combatFP = 30 + (int) (power * 1.2f);

        String factionId = initiator != null ? initiator.getFactionId() : "independent";
        String sourceMarketId = initiator != null ? initiator.getHomeMarketId() : targetMarketId;

        // Build phases
        phases.add(new AssemblePhase(power));
        phases.add(new TravelAndFightPhase(factionId, sourceMarketId, targetMarketId, combatFP));
        phases.add(new ReturnPhase(3f));
    }

    @Override
    public String getOpTypeName() {
        return "Raid";
    }

    @Override
    protected void onStarted() {
        IntriguePerson initiator = getInitiator();
        if (initiator == null) return;

        // Checkout initiator to fleet (they're "away" during the op)
        IntriguePeopleAccess people = IntrigueServices.people();
        people.checkoutToMarket(initiator.getPersonId(), targetMarketId);

        log.info("RaidOp started: " + initiator.getPersonId() + " raiding " + targetMarketId);
    }

    @Override
    protected OpOutcome determineOutcome() {
        // Look at the TravelAndFightPhase result
        for (OpPhase phase : phases) {
            if (phase instanceof TravelAndFightPhase) {
                return ((TravelAndFightPhase) phase).didFleetWin()
                        ? OpOutcome.SUCCESS
                        : OpOutcome.FAILURE;
            }
        }
        return OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntriguePeopleAccess people = IntrigueServices.people();
        IntriguePerson attacker = getInitiator();
        IntriguePerson defender = getTargetPerson();

        // Return the attacker home regardless of outcome
        if (attacker != null) {
            people.returnHome(attacker.getPersonId());
            attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("RaidOp resolved: " + getOpId() + " → " + result);

        if (result == OpOutcome.SUCCESS) {
            applySuccess(people, attacker, defender);
        } else if (result == OpOutcome.FAILURE) {
            applyFailure(people, attacker, defender);
        }
        // ABORTED: no stat changes

        // Sync memory for both
        if (attacker != null) people.syncMemory(attacker.getPersonId());
        if (defender != null) people.syncMemory(defender.getPersonId());
    }

    private void applySuccess(IntriguePeopleAccess people, IntriguePerson attacker, IntriguePerson defender) {
        int powerGain = BASE_POWER_SHIFT;

        // Trait modifiers
        if (attacker != null && attacker.getTraits().contains(IntrigueTraits.MERCILESS)) {
            powerGain += 4; // MERCILESS attackers take bigger swings
        }

        if (attacker != null) {
            attacker.setPower(clamp(attacker.getPower() + powerGain, 0, 100));
        }
        if (defender != null) {
            defender.setPower(clamp(defender.getPower() - powerGain, 0, 100));
        }

        // Relationship between attacker and defender drops
        if (attacker != null && defender != null) {
            people.setRelationship(attacker.getPersonId(), defender.getPersonId(),
                    getRelOrZero(attacker, defender.getPersonId()) + REL_DROP_ON_RAID);
        }

        // Attacker-player relationship: slight boost if player shares faction, slight penalty otherwise
        if (attacker != null) {
            // For now, raids always slightly reduce player relations (raiding is disruptive)
            attacker.setRelToPlayer(clamp(attacker.getRelToPlayer() - 2, -100, 100));
        }

        log.info("  SUCCESS: attacker +" + powerGain + " power, defender -" + powerGain + " power");
    }

    private void applyFailure(IntriguePeopleAccess people, IntriguePerson attacker, IntriguePerson defender) {
        int powerLoss = BASE_POWER_SHIFT / 2; // fail is less costly than success is rewarding

        if (attacker != null) {
            attacker.setPower(clamp(attacker.getPower() - powerLoss, 0, 100));
        }
        // Defender gains a small amount for successfully defending
        if (defender != null) {
            defender.setPower(clamp(defender.getPower() + powerLoss / 2, 0, 100));
        }

        // Relationship still drops (you tried to raid them!)
        if (attacker != null && defender != null) {
            people.setRelationship(attacker.getPersonId(), defender.getPersonId(),
                    getRelOrZero(attacker, defender.getPersonId()) + REL_DROP_ON_RAID / 2);
        }

        log.info("  FAILURE: attacker -" + powerLoss + " power");
    }

    // ── Util ────────────────────────────────────────────────────────────

    private int getRelOrZero(IntriguePerson person, String otherId) {
        Integer rel = person.getRelTo(otherId);
        return rel != null ? rel : 0;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

