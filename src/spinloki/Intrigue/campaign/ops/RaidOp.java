package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueSubfactionAccess;

import java.util.logging.Logger;

/**
 * "Send a coordinated fleet group to raid another subfaction's home market."
 *
 * Uses {@link FGIPhase} to create an {@link IntrigueRaidIntel} (extends
 * GenericRaidFGI) which handles multi-fleet spawning, coordinated travel,
 * Intel panel display, and automatic defense fleet spawning.
 *
 * Outcome effects:
 *   SUCCESS → attacker subfaction gains power, defender loses power, relationship drops
 *   FAILURE → attacker subfaction loses power, relationship still drops
 *   ABORTED → minimal effect (e.g. factions became non-hostile)
 */
public class RaidOp extends IntrigueOp {

    private static final Logger log = Logger.getLogger(RaidOp.class.getName());

    private static final int BASE_COHESION_SHIFT = 8;
    private static final int BASE_LEGITIMACY_SHIFT = 6;
    private static final int REL_DROP_ON_RAID = -15;

    private final String targetMarketId;
    private final FGIPhase fgiPhase;

    /**
     * @param opId                unique operation ID
     * @param attackerSubfaction  the attacking subfaction
     * @param targetSubfaction    the defending subfaction
     */
    public RaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        this(opId, attackerSubfaction, targetSubfaction, null);
    }

    /**
     * @param opId                unique operation ID
     * @param attackerSubfaction  the attacking subfaction
     * @param targetSubfaction    the defending subfaction
     * @param overrideMarketId    specific market to raid (e.g. territory base), or null for home market
     */
    public RaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction,
                  String overrideMarketId) {
        super(opId,
              attackerSubfaction.getLeaderId(),
              targetSubfaction.getLeaderId(),
              attackerSubfaction.getSubfactionId(),
              targetSubfaction.getSubfactionId());
        this.targetMarketId = (overrideMarketId != null && !overrideMarketId.isEmpty())
                ? overrideMarketId
                : targetSubfaction.getHomeMarketId();

        int cohesion = attackerSubfaction.getHomeCohesion();

        // Determine fleet strength from subfaction home cohesion: 30 FP at 0, 150 FP at 100
        int combatFP = 30 + (int) (cohesion * 1.2f);

        // Single phase: FGIPhase handles prep, travel, combat, and return
        fgiPhase = new FGIPhase(
                attackerSubfaction.getSubfactionId(),
                targetSubfaction.getSubfactionId(),
                combatFP);
        phases.add(fgiPhase);

        // RaidOp uses IntrigueRaidIntel (via FGIPhase) for its intel display
        setSkipIntel(true);
    }

    @Override
    public String getOpTypeName() {
        return "Raid";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        if (leader == null) return;

        log.info("RaidOp started: " + getInitiatorSubfactionId()
                + " (leader " + leader.getPersonId() + ") raiding " + targetMarketId);
    }

    @Override
    protected boolean shouldAbort() {
        if (super.shouldAbort()) return true;

        // Cancel cross-faction raids if parent factions are no longer hostile
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        IntrigueSubfaction defender = getTargetSubfaction();
        if (attacker == null || defender == null) return true;

        boolean sameFaction = attacker.getFactionId().equals(defender.getFactionId());
        if (!sameFaction && !IntrigueServices.hostility().areHostile(attacker.getFactionId(), defender.getFactionId())) {
            log.info("RaidOp " + getOpId() + " aborted: "
                    + attacker.getFactionId() + " and " + defender.getFactionId() + " are no longer hostile.");
            // Abort the FGI so fleets return home
            fgiPhase.abort();
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        return fgiPhase.didRaidSucceed() ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
    }

    @Override
    protected void applyOutcome() {
        IntriguePeopleAccess people = IntrigueServices.people();
        IntrigueSubfactionAccess subfactions = IntrigueServices.subfactions();
        IntrigueSubfaction attacker = getInitiatorSubfaction();
        IntrigueSubfaction defender = getTargetSubfaction();
        IntriguePerson leader = getInitiator();


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
        int cohesionGain = BASE_COHESION_SHIFT;
        int legitimacyShift = BASE_LEGITIMACY_SHIFT;

        // Leader trait modifiers
        if (leader != null && leader.getTraits().contains(IntrigueTraits.MERCILESS)) {
            cohesionGain += 4;
            legitimacyShift += 2;
        }

        if (attacker != null) {
            // Attacker gains home cohesion (operational success) and some legitimacy (proved their strength)
            attacker.setHomeCohesion(attacker.getHomeCohesion() + cohesionGain);
            attacker.setLegitimacy(attacker.getLegitimacy() + legitimacyShift / 2);
        }
        if (defender != null) {
            // Defender loses legitimacy primarily (they looked weak) and some home cohesion (damaged infrastructure)
            defender.setLegitimacy(defender.getLegitimacy() - legitimacyShift);
            defender.setHomeCohesion(defender.getHomeCohesion() - cohesionGain / 2);
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

        log.info("  SUCCESS: attacker +" + cohesionGain + " home cohesion, defender -" + legitimacyShift + " legitimacy");
    }

    private void applyFailure(IntrigueSubfactionAccess subfactions,
                               IntrigueSubfaction attacker, IntrigueSubfaction defender) {
        int cohesionLoss = BASE_COHESION_SHIFT / 2;
        int legitimacyGain = BASE_LEGITIMACY_SHIFT / 2;

        if (attacker != null) {
            // Attacker loses home cohesion (failed operation, troops spent)
            attacker.setHomeCohesion(attacker.getHomeCohesion() - cohesionLoss);
        }
        if (defender != null) {
            // Defender gains legitimacy (they repelled an attack, looking strong)
            defender.setLegitimacy(defender.getLegitimacy() + legitimacyGain);
        }

        // Relationship still drops (you tried to raid them!)
        if (attacker != null && defender != null) {
            int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
            subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                    rel + REL_DROP_ON_RAID / 2);
        }

        log.info("  FAILURE: attacker -" + cohesionLoss + " home cohesion, defender +" + legitimacyGain + " legitimacy");
    }

    private int getSubfactionRelOrZero(IntrigueSubfaction sf, String otherId) {
        Integer rel = sf.getRelTo(otherId);
        return rel != null ? rel : 0;
    }
}