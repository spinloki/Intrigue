package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueSubfactionAccess;

import java.util.logging.Logger;

/**
 * "Send a fleet to raid another subfaction's home market."
 *
 * Phases:
 *   1. AssemblePhase  – timed delay scaling with subfaction power
 *   2. TravelAndFightPhase – spawn fleet, travel, fight
 *   3. ReturnPhase – cooldown before leader is available again
 *
 * Outcome effects:
 *   SUCCESS → attacker subfaction gains power, defender loses power, relationship drops
 *   FAILURE → attacker subfaction loses power, relationship still drops
 *   ABORTED → minimal effect
 */
public class RaidOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(RaidOp.class.getName());

    private static final int BASE_POWER_SHIFT = 8;
    private static final int REL_DROP_ON_RAID = -15;

    private final String targetMarketId;

    /**
     * @param opId                unique operation ID
     * @param attackerSubfaction  the attacking subfaction
     * @param targetSubfaction    the defending subfaction
     */
    public RaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        super(opId,
              attackerSubfaction.getLeaderId(),
              targetSubfaction.getLeaderId(),
              attackerSubfaction.getSubfactionId(),
              targetSubfaction.getSubfactionId());
        this.targetMarketId = targetSubfaction.getHomeMarketId();

        int power = attackerSubfaction.getPower();

        // Determine fleet strength from subfaction power: 30 FP at power 0, 150 FP at power 100
        int combatFP = 30 + (int) (power * 1.2f);

        String factionId = attackerSubfaction.getFactionId();
        String sourceMarketId = attackerSubfaction.getHomeMarketId();

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
        IntriguePerson leader = getInitiator();
        if (leader == null) return;

        // Checkout leader to the target market (they're "away" during the op)
        IntriguePeopleAccess people = IntrigueServices.people();
        people.checkoutToMarket(leader.getPersonId(), targetMarketId);

        log.info("RaidOp started: " + getInitiatorSubfactionId()
                + " (leader " + leader.getPersonId() + ") raiding " + targetMarketId);
    }

    @Override
    protected OpOutcome determineOutcome() {
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
        IntrigueSubfactionAccess subfactions = IntrigueServices.subfactions();
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        IntrigueSubfaction defender = getTargetSubfaction();
        IntriguePerson leader = getInitiator();

        // Return the leader home regardless of outcome
        if (leader != null) {
            people.returnHome(leader.getPersonId());
        }

        // Set cooldown on the attacking subfaction
        if (attacker != null) {
            attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
        }

        OpOutcome result = getOutcome();
        log.info("RaidOp resolved: " + getOpId() + " → " + result);

        if (result == OpOutcome.SUCCESS) {
            applySuccess(subfactions, attacker, defender, leader);
        } else if (result == OpOutcome.FAILURE) {
            applyFailure(subfactions, attacker, defender);
        }

        // Sync memory for leader and target leader
        if (leader != null) people.syncMemory(leader.getPersonId());
        IntriguePerson targetLeader = getTargetPerson();
        if (targetLeader != null) people.syncMemory(targetLeader.getPersonId());
    }

    private void applySuccess(IntrigueSubfactionAccess subfactions,
                               IntrigueSubfaction attacker, IntrigueSubfaction defender,
                               IntriguePerson leader) {
        int powerGain = BASE_POWER_SHIFT;

        // Leader trait modifiers
        if (leader != null && leader.getTraits().contains(IntrigueTraits.MERCILESS)) {
            powerGain += 4;
        }

        if (attacker != null) {
            attacker.setPower(attacker.getPower() + powerGain);
        }
        if (defender != null) {
            defender.setPower(defender.getPower() - powerGain);
        }

        // Subfaction relationship drops
        if (attacker != null && defender != null) {
            int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
            subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                    rel + REL_DROP_ON_RAID);
        }

        // Attacker's player standing: raiding is disruptive
        if (attacker != null) {
            attacker.setRelToPlayer(attacker.getRelToPlayer() - 2);
        }

        log.info("  SUCCESS: attacker +" + powerGain + " power, defender -" + powerGain + " power");
    }

    private void applyFailure(IntrigueSubfactionAccess subfactions,
                               IntrigueSubfaction attacker, IntrigueSubfaction defender) {
        int powerLoss = BASE_POWER_SHIFT / 2;

        if (attacker != null) {
            attacker.setPower(attacker.getPower() - powerLoss);
        }
        if (defender != null) {
            defender.setPower(defender.getPower() + powerLoss / 2);
        }

        // Relationship still drops (you tried to raid them!)
        if (attacker != null && defender != null) {
            int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
            subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                    rel + REL_DROP_ON_RAID / 2);
        }

        log.info("  FAILURE: attacker -" + powerLoss + " power");
    }

    private int getSubfactionRelOrZero(IntrigueSubfaction sf, String otherId) {
        Integer rel = sf.getRelTo(otherId);
        return rel != null ? rel : 0;
    }
}