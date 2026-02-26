package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import spinloki.Intrigue.campaign.IntrigueSubfaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Intel item for an Intrigue subfaction raid. Extends GenericRaidFGI to get:
 *   - Multi-fleet spawning and coordinated travel
 *   - Intel panel with ETA, strength assessment, target info
 *   - Automatic defense fleet spawning via MilitaryResponseScript
 *   - Proper return-home after raid completes
 *
 * The raid targets a specific market's star system. Fleets are tagged with
 * $intrigueFleet and $intrigueSubfaction for identification.
 */
public class IntrigueRaidIntel extends GenericRaidFGI {

    private static final Logger log = Logger.getLogger(IntrigueRaidIntel.class.getName());

    private final String attackerSubfactionId;
    private final String attackerSubfactionName;
    private final String defenderSubfactionId;
    private final String memKey;

    /**
     * Create and register a raid intel.
     *
     * @param attacker the attacking subfaction
     * @param defender the defending subfaction
     * @param combatFP total fleet points for the raid
     * @throws IllegalArgumentException if source/target markets or star systems can't be resolved
     */
    public IntrigueRaidIntel(IntrigueSubfaction attacker, IntrigueSubfaction defender, int combatFP) {
        super(buildParams(attacker, defender, combatFP));
        this.attackerSubfactionId = attacker.getSubfactionId();
        this.attackerSubfactionName = attacker.getName();
        this.defenderSubfactionId = defender.getSubfactionId();
        this.memKey = params.memoryKey;

        log.info("IntrigueRaidIntel created: " + attackerSubfactionName
                + " → " + defender.getName()
                + " (" + combatFP + " FP)");
    }

    /**
     * @return the memory key used to store this intel in sector memory (for save/load re-acquisition)
     */
    public String getMemKey() {
        return memKey;
    }

    private static GenericRaidParams buildParams(IntrigueSubfaction attacker,
                                                  IntrigueSubfaction defender,
                                                  int combatFP) {
        MarketAPI sourceMarket = Global.getSector().getEconomy().getMarket(attacker.getHomeMarketId());
        MarketAPI targetMarket = Global.getSector().getEconomy().getMarket(defender.getHomeMarketId());

        if (sourceMarket == null) {
            throw new IllegalArgumentException("Source market not found: " + attacker.getHomeMarketId());
        }
        if (targetMarket == null) {
            throw new IllegalArgumentException("Target market not found: " + defender.getHomeMarketId());
        }
        if (sourceMarket.getPrimaryEntity() == null) {
            throw new IllegalArgumentException("Source market has no primary entity: " + attacker.getHomeMarketId());
        }
        if (targetMarket.getPrimaryEntity() == null) {
            throw new IllegalArgumentException("Target market has no primary entity: " + defender.getHomeMarketId());
        }
        if (targetMarket.getPrimaryEntity().getStarSystem() == null) {
            throw new IllegalArgumentException("Target market not in a star system: " + defender.getHomeMarketId());
        }

        GenericRaidParams params = new GenericRaidParams(new Random(), false);
        params.factionId = attacker.getFactionId();
        params.source = sourceMarket;

        // Split total FP into 2-4 fleet groups
        List<Integer> fleetSizes = splitIntoFleets(combatFP);
        params.fleetSizes = fleetSizes;

        // Raid target: the star system containing the defender's market
        FGRaidParams raidParams = new FGRaidParams();
        raidParams.where = targetMarket.getPrimaryEntity().getStarSystem();
        raidParams.allowedTargets.add(targetMarket);
        raidParams.raidsPerColony = 1;
        raidParams.doNotGetSidetracked = true;
        params.raidParams = raidParams;

        // Timing
        params.prepDays = 3f + (100 - attacker.getPower()) * 0.07f; // 3-10 days prep
        params.payloadDays = 20f;

        // Don't use makeFleetsHostile — that makes fleets hostile to EVERYONE including the player.
        // The fleets inherit their faction's hostility naturally, which is sufficient since
        // OpEvaluator already requires parent factions to be hostile for cross-faction raids.
        params.makeFleetsHostile = false;

        // Subfaction raids shouldn't affect the player's reputation with factions
        params.repImpact = ComplicationRepImpact.NONE;

        // Memory key for lookup
        params.memoryKey = "$intrigueRaid_" + attacker.getSubfactionId() + "_" + System.currentTimeMillis();

        return params;
    }

    /**
     * Split total FP into 2-4 fleet groups of varying sizes.
     */
    private static List<Integer> splitIntoFleets(int totalFP) {
        List<Integer> sizes = new ArrayList<>();
        Random rng = new Random();

        int numFleets = 2 + rng.nextInt(3); // 2-4 fleets
        int remaining = totalFP;

        for (int i = 0; i < numFleets - 1; i++) {
            int minPerFleet = remaining / (numFleets - i) / 2;
            int maxPerFleet = remaining / (numFleets - i) * 3 / 2;
            int size = Math.max(20, minPerFleet + rng.nextInt(Math.max(1, maxPerFleet - minPerFleet)));
            size = Math.min(size, remaining - (numFleets - i - 1) * 20);
            sizes.add(size);
            remaining -= size;
        }
        sizes.add(Math.max(20, remaining));

        return sizes;
    }

    // ── Display ─────────────────────────────────────────────────────────

    @Override
    public String getBaseName() {
        return attackerSubfactionName + " Raid";
    }

    @Override
    public String getNoun() {
        return "raid";
    }

    @Override
    public String getForcesNoun() {
        return attackerSubfactionName + " forces";
    }

    // ── Fleet customization ─────────────────────────────────────────────

    @Override
    protected void configureFleet(int size, CampaignFleetAPI fleet) {
        super.configureFleet(size, fleet);

        // Tag for intrigue identification
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", attackerSubfactionName);

        // Name fleets after the subfaction
        fleet.setName(attackerSubfactionName + " Raid Fleet");
    }

    // ── Accessors for RaidOp ────────────────────────────────────────────

    public String getAttackerSubfactionId() {
        return attackerSubfactionId;
    }

    public String getDefenderSubfactionId() {
        return defenderSubfactionId;
    }

    /**
     * Whether the raid achieved its objective.
     * Delegates to the payload action's success fraction.
     */
    public boolean raidSucceeded() {
        if (raidAction != null) {
            return raidAction.getSuccessFraction() > 0.5f;
        }
        return isSucceeded();
    }

    @Override
    public boolean isSucceeded() {
        if (raidAction != null) {
            return raidAction.getSuccessFraction() > 0.5f;
        }
        return false;
    }

    @Override
    public boolean isFailed() {
        if (isAborted()) return true;
        if (isEnding() && !isSucceeded()) return true;
        return false;
    }
}





