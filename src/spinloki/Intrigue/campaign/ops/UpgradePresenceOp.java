package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.IntrigueTerritory.Presence;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Operation: upgrade a subfaction's presence in a territory to the next tier.
 *
 * <p>Requires the subfaction to be ESTABLISHED or FORTIFIED with high enough
 * territory cohesion. Sends a large convoy (FGI with multiple escort fleets
 * and a heavy supply fleet). On success, the presence tier advances and the
 * base's station/military industries are upgraded. On failure, the subfaction
 * pays a cohesion penalty but keeps its current tier.</p>
 *
 * <p>Tier progression and costs:</p>
 * <ul>
 *   <li>ESTABLISHED → FORTIFIED: requires cohesion ≥ 75. Costs 25 territory cohesion.
 *       Upgrades ORBITALSTATION → BATTLESTATION and MILITARYBASE → HIGHCOMMAND.</li>
 *   <li>FORTIFIED → DOMINANT: requires cohesion ≥ 90. Costs 40 territory cohesion.
 *       Upgrades BATTLESTATION → STARFORTRESS.</li>
 * </ul>
 */
public class UpgradePresenceOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(UpgradePresenceOp.class.getName());

    /** Cohesion threshold required to attempt ESTABLISHED → FORTIFIED. */
    public static final int FORTIFY_COHESION_THRESHOLD = OpEvaluator.FORTIFY_COHESION_THRESHOLD;
    /** Cohesion threshold required to attempt FORTIFIED → DOMINANT. */
    public static final int DOMINATE_COHESION_THRESHOLD = OpEvaluator.DOMINATE_COHESION_THRESHOLD;

    /** Territory cohesion cost on success: ESTABLISHED → FORTIFIED. */
    private static final int FORTIFY_COHESION_COST = 25;
    /** Territory cohesion cost on success: FORTIFIED → DOMINANT. */
    private static final int DOMINATE_COHESION_COST = 40;
    /** Home cohesion cost on success. */
    private static final int HOME_COHESION_COST = 10;
    /** Legitimacy gain on success. */
    private static final int LEGITIMACY_GAIN = 5;
    /** Territory cohesion penalty on failure. */
    private static final int FAILURE_COHESION_PENALTY = 10;
    /** Home cohesion penalty on failure. */
    private static final int FAILURE_HOME_COHESION_PENALTY = 5;
    /** Convoy travel/setup time. */
    private static final float UPGRADE_DAYS = 40f;

    private final String subfactionId;
    private final Presence currentPresence;
    private final Presence targetPresence;
    private final EstablishTerritoryBasePhase convoyPhase;

    public UpgradePresenceOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId,
              subfaction.getLeaderId(),
              null,
              subfaction.getSubfactionId(),
              null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        IntrigueTerritory territory = territories != null ? territories.getById(territoryId) : null;
        this.currentPresence = territory != null ? territory.getPresence(subfactionId) : Presence.ESTABLISHED;
        this.targetPresence = currentPresence.next();

        // Intel arrow: home → territory
        setIntelSourceMarketId(subfaction.getHomeMarketId());
        setIntelDestinationSystemId(IntrigueOpIntel.resolveSystemIdFromTerritory(territoryId));

        // Large convoy — scaled by target tier
        boolean toFortified = (targetPresence == Presence.FORTIFIED);
        int escortFP = toFortified ? 50 : 80;
        int supplyFP = toFortified ? 30 : 50;

        // Scale up if subfaction is strong
        escortFP += (int) (subfaction.getHomeCohesion() * 0.3f);
        supplyFP += (int) (subfaction.getHomeCohesion() * 0.15f);

        this.convoyPhase = new EstablishTerritoryBasePhase(
                subfaction.getFactionId(),
                subfaction.getHomeMarketId(),
                territoryId,
                escortFP, supplyFP,
                UPGRADE_DAYS,
                subfaction.getName());
        phases.add(convoyPhase);
    }

    @Override
    public String getOpTypeName() {
        return "Upgrade Presence";
    }

    @Override
    protected void onStarted() {
        IntriguePerson leader = getInitiator();
        log.info("UpgradePresenceOp started: " + subfactionId
                + " upgrading " + currentPresence + " → " + targetPresence
                + " in territory " + getTerritoryId()
                + " (leader " + (leader != null ? leader.getPersonId() : "?") + ")");
    }

    @Override
    protected boolean shouldAbort() {
        if (getInitiator() == null) return true;

        // Abort if convoy was destroyed
        if (convoyPhase.isDone() && !convoyPhase.didSucceed()) return true;

        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return true;

        IntrigueTerritory territory = territories.getById(getTerritoryId());
        if (territory == null) return true;

        // Abort if presence dropped below current tier (e.g. expelled while upgrading)
        Presence actual = territory.getPresence(subfactionId);
        if (actual.ordinal() < currentPresence.ordinal()) {
            log.info("UpgradePresenceOp " + getOpId()
                    + " aborted: presence dropped to " + actual);
            return true;
        }

        return false;
    }

    @Override
    protected OpOutcome determineOutcome() {
        if (!convoyPhase.didSucceed()) return OpOutcome.FAILURE;
        return OpOutcome.SUCCESS;
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

        if (result == OpOutcome.SUCCESS && territory != null && targetPresence != null) {
            // Advance presence tier
            territory.setPresence(subfactionId, targetPresence);

            // Pay territory cohesion cost
            int terrCohCost = (targetPresence == Presence.FORTIFIED)
                    ? FORTIFY_COHESION_COST : DOMINATE_COHESION_COST;
            territory.setCohesion(subfactionId,
                    territory.getCohesion(subfactionId) - terrCohCost);

            // Pay home cohesion cost, gain legitimacy
            if (subfaction != null) {
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - HOME_COHESION_COST);
                subfaction.setLegitimacy(subfaction.getLegitimacy() + LEGITIMACY_GAIN);
            }

            // Upgrade station industries on the base market
            upgradeBaseIndustries(territory);

            log.info("UpgradePresenceOp SUCCESS: " + subfactionId
                    + " upgraded to " + targetPresence + " in " + territory.getName()
                    + " (terrCoh cost=" + terrCohCost + ")");
        } else {
            // Failed — cohesion penalty, no tier change
            if (territory != null) {
                territory.setCohesion(subfactionId,
                        territory.getCohesion(subfactionId) - FAILURE_COHESION_PENALTY);
            }
            if (subfaction != null) {
                subfaction.setHomeCohesion(subfaction.getHomeCohesion() - FAILURE_HOME_COHESION_PENALTY);
            }
            log.info("UpgradePresenceOp FAILURE: " + subfactionId
                    + " failed to upgrade to " + targetPresence
                    + " in " + (territory != null ? territory.getName() : getTerritoryId()));
        }

        IntriguePerson leader = getInitiator();
        if (leader != null) {
            IntrigueServices.people().syncMemory(leader.getPersonId());
        }
    }

    /**
     * Upgrade the station and military industries on the base market
     * to match the new presence tier.
     */
    private void upgradeBaseIndustries(IntrigueTerritory territory) {
        String baseMarketId = territory.getBaseMarketId(subfactionId);
        if (baseMarketId == null) return;

        // Only upgrade industries in the real game (not sim)
        if (!PhaseUtil.isSectorAvailable()) return;

        MarketAPI market = com.fs.starfarer.api.Global.getSector().getEconomy().getMarket(baseMarketId);
        if (market == null) return;

        if (targetPresence == Presence.FORTIFIED) {
            // ORBITALSTATION → BATTLESTATION
            if (market.hasIndustry(Industries.ORBITALSTATION)) {
                market.removeIndustry(Industries.ORBITALSTATION, null, false);
                market.addIndustry(Industries.BATTLESTATION);
            }
            // MILITARYBASE → HIGHCOMMAND
            if (market.hasIndustry(Industries.MILITARYBASE)) {
                market.removeIndustry(Industries.MILITARYBASE, null, false);
                market.addIndustry(Industries.HIGHCOMMAND);
            }
        } else if (targetPresence == Presence.DOMINANT) {
            // BATTLESTATION → STARFORTRESS
            if (market.hasIndustry(Industries.BATTLESTATION)) {
                market.removeIndustry(Industries.BATTLESTATION, null, false);
                market.addIndustry(Industries.STARFORTRESS);
            }
        }

        log.info("  Upgraded industries on " + baseMarketId + " for " + targetPresence);
    }

    /** Get the cohesion threshold required for a given current presence. */
    public static int getUpgradeThreshold(Presence current) {
        if (current == Presence.ESTABLISHED) return FORTIFY_COHESION_THRESHOLD;
        if (current == Presence.FORTIFIED) return DOMINATE_COHESION_THRESHOLD;
        return Integer.MAX_VALUE; // DOMINANT has no further upgrade
    }
}


