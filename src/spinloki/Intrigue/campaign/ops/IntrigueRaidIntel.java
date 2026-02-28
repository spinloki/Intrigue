package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.ui.SectorMapAPI;
import spinloki.Intrigue.IntrigueIds;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueSubfactionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Intel item for an Intrigue subfaction raid. Extends GenericRaidFGI to get:
 *   - Multi-fleet spawning and coordinated travel
 *   - Intel panel with ETA, strength assessment, target info
 *   - Explicit defender fleet spawning at the target market (1–2 fleets, ~70% of attacker FP)
 *   - Plus vanilla MilitaryResponseScript redirection of existing patrol fleets
 *   - Proper return-home after raid completes
 *   - Grouped under the "Intrigue" intel tab via custom intel tag
 *
 * The raid targets a specific market's star system. Fleets are tagged with
 * $intrigueFleet and $intrigueSubfaction for identification.
 * Defender fleets are additionally tagged with $intrigueDefender.
 */
public class IntrigueRaidIntel extends GenericRaidFGI {

    private static final Logger log = Logger.getLogger(IntrigueRaidIntel.class.getName());

    private final String attackerSubfactionId;
    private final String attackerSubfactionName;
    private final String defenderSubfactionId;
    private final String defenderFactionId;
    private final String targetMarketId;
    private final int totalAttackerFP;
    private final String memKey;

    /** IDs of defender fleets we spawned - used for save/load re-acquisition. */
    private final List<String> defenderFleetIds = new ArrayList<>();

    /** Transient references to live defender fleets; rebuilt on load. */
    private transient List<CampaignFleetAPI> defenderFleets;

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
        this.defenderFactionId = defender.getFactionId();
        this.targetMarketId = defender.getHomeMarketId();
        this.totalAttackerFP = combatFP;
        this.memKey = params.memoryKey;
        this.defenderFleets = new ArrayList<>();

        // Spawn defender fleets immediately so the target market is defended
        // before the attacker fleets even finish assembling
        spawnDefenderFleets();

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
        raidParams.tryToCaptureObjectives = false;
        params.raidParams = raidParams;

        // Timing
        params.prepDays = 3f + (100 - attacker.getHomeCohesion()) * 0.07f; // 3-10 days prep
        params.payloadDays = 20f;

        // Don't use makeFleetsHostile - that makes fleets hostile to EVERYONE including the player.
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
    protected void spawnFleets() {
        super.spawnFleets(); // spawns attacker fleets
    }

    /**
     * Spawn defender fleets at the target market:
     *   1. The targeted subfaction's own defense fleet (~70% of attacker FP)
     *   2. Allied fleets from sibling subfactions of the same faction (~35% each)
     *
     * This gives the defending faction a clear numerical advantage, preventing
     * a death spiral where markets are perpetually destabilized by raids.
     */
    private void spawnDefenderFleets() {
        MarketAPI targetMarket = Global.getSector().getEconomy().getMarket(targetMarketId);
        if (targetMarket == null || targetMarket.getPrimaryEntity() == null) {
            log.warning("Cannot spawn defender fleets: target market not found: " + targetMarketId);
            return;
        }

        if (defenderFleets == null) {
            defenderFleets = new ArrayList<>();
        }

        Random rng = getRandom();

        // 1. Primary defender: the targeted subfaction's own fleet (~70% of attacker FP)
        int primaryFP = (int) (totalAttackerFP * 1.1f);
        if (primaryFP < 20) primaryFP = 20;
        spawnDefenseFleetGroup(targetMarket, defenderFactionId, defenderSubfactionId,
                primaryFP, 1 + rng.nextInt(2), rng);

        // 2. Allied fleets from sibling subfactions of the same faction (~35% each)
        Collection<IntrigueSubfaction> siblings = IntrigueSubfactionManager.get()
                .getByFaction(defenderFactionId);
        for (IntrigueSubfaction sibling : siblings) {
            if (sibling.getSubfactionId().equals(defenderSubfactionId)) continue; // skip self

            int allyFP = (int) (totalAttackerFP * 0.35f);
            if (allyFP < 15) allyFP = 15;

            // Allied reinforcements arrive as a single fleet from their own market
            MarketAPI allyMarket = Global.getSector().getEconomy().getMarket(sibling.getHomeMarketId());
            if (allyMarket == null || allyMarket.getPrimaryEntity() == null) continue;

            // Spawn at the target market (they've been called in to defend)
            spawnDefenseFleetGroup(targetMarket, sibling.getFactionId(), sibling.getSubfactionId(),
                    allyFP, 1, rng);

            log.info("Allied subfaction " + sibling.getName()
                    + " contributing " + allyFP + " FP to defense of " + targetMarket.getName());
        }

        log.info("Total defender fleets spawned: " + defenderFleets.size()
                + " for raid on " + targetMarket.getName());
    }

    /**
     * Spawn a group of defense fleets at the target market for a given subfaction.
     */
    private void spawnDefenseFleetGroup(MarketAPI targetMarket, String factionId,
                                         String subfactionId, int totalFP,
                                         int numFleets, Random rng) {
        int remaining = totalFP;
        for (int i = 0; i < numFleets; i++) {
            int fp = (i == numFleets - 1) ? remaining : remaining / numFleets;
            if (fp < 15) fp = 15;
            remaining -= fp;

            CampaignFleetAPI fleet = createDefenderFleet(targetMarket, factionId, subfactionId, fp);
            if (fleet != null) {
                targetMarket.getPrimaryEntity().getContainingLocation().addEntity(fleet);
                fleet.setLocation(
                        targetMarket.getPrimaryEntity().getLocation().x + rng.nextFloat() * 200 - 100,
                        targetMarket.getPrimaryEntity().getLocation().y + rng.nextFloat() * 200 - 100);

                // ORBIT_AGGRESSIVE: stays anchored near the market but engages
                // hostile fleets that approach - unlike DEFEND_LOCATION which
                // wanders the whole system, or ORBIT_PASSIVE which ignores threats
                fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE,
                        targetMarket.getPrimaryEntity(), 120f, "defending " + targetMarket.getName());

                // Don't get sidetracked chasing random pirates/patrols
                fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);

                defenderFleets.add(fleet);
                defenderFleetIds.add(fleet.getId());

                log.info("Spawned defender fleet: " + fleet.getName()
                        + " (" + fp + " FP) at " + targetMarket.getName());
            }
        }
    }

    private CampaignFleetAPI createDefenderFleet(MarketAPI market, String factionId,
                                                 String subfactionId, int combatFP) {
        // Use FleetCreatorMission - same pipeline as attacker fleets in GenericRaidFGI.
        // This ensures defender fleets are comparable in size, quality, and officer
        // count to attacker fleets for the same FP value.
        FleetCreatorMission m = new FleetCreatorMission(getRandom());
        m.beginFleet();

        m.createStandardFleet(combatFP, factionId, market.getPrimaryEntity().getLocationInHyperspace());
        m.triggerSetFleetFaction(factionId);
        m.setFleetSource(market);
        m.triggerSetWarFleet();
        m.triggerMakeLowRepImpact();

        CampaignFleetAPI fleet = m.createFleet();
        if (fleet == null) return null;

        // Tag for intrigue identification
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueDefender", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", subfactionId);

        // Name includes the subfaction for clarity
        IntrigueSubfaction sf = IntrigueSubfactionManager.get().getById(subfactionId);
        String sfName = sf != null ? sf.getName() : subfactionId;
        fleet.setName(sfName + " Defense Fleet");

        return fleet;
    }


    @Override
    protected void configureFleet(int size, CampaignFleetAPI fleet) {
        super.configureFleet(size, fleet);

        // Tag for intrigue identification
        fleet.getMemoryWithoutUpdate().set("$intrigueFleet", true);
        fleet.getMemoryWithoutUpdate().set("$intrigueSubfaction", attackerSubfactionName);

        // Name fleets after the subfaction
        fleet.setName(attackerSubfactionName + " Raid Fleet");
    }

    // ── Custom raid action: no market damage ────────────────────────────

    /**
     * Tells FGRaidAction to use our custom raid logic instead of the vanilla
     * MarketCMD.doGenericRaid / doIndustryRaid which would damage stability
     * and disrupt industries. Intrigue raids are political - their outcomes
     * are self-contained to subfaction power and relationship shifts.
     */
    @Override
    public boolean hasCustomRaidAction() {
        return true;
    }

    /**
     * No-op: we don't damage the market. The raid "succeeds" simply by
     * reaching the target system and not being destroyed. Actual effects
     * (power shifts, relationship changes) are applied by RaidOp.applyOutcome().
     */
    @Override
    public void doCustomRaidAction(CampaignFleetAPI fleet, MarketAPI market, float raidStr) {
        log.info("Intrigue raid action at " + market.getName()
                + " - no market damage applied (political raid).");
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

    // ── Defender fleet cleanup ──────────────────────────────────────────

    @Override
    protected void notifyEnding() {
        super.notifyEnding();
        cleanupDefenderFleets();
    }

    /**
     * Despawn all defender fleets. Called when the raid ends (success, failure, or abort).
     */
    private void cleanupDefenderFleets() {
        reacquireDefenderFleets();
        if (defenderFleets == null) return;

        for (CampaignFleetAPI fleet : defenderFleets) {
            if (fleet != null && fleet.isAlive()) {
                fleet.clearAssignments();
                fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
                        fleet.getContainingLocation().createToken(fleet.getLocation()),
                        30f, "standing down");
                log.info("Despawning defender fleet: " + fleet.getName());
            }
        }
    }

    /**
     * Re-acquire transient defender fleet references from stored IDs (for save/load).
     */
    private void reacquireDefenderFleets() {
        if (defenderFleets == null) {
            defenderFleets = new ArrayList<>();
        }
        if (defenderFleets.isEmpty() && !defenderFleetIds.isEmpty()) {
            for (String id : defenderFleetIds) {
                for (CampaignFleetAPI fleet : Global.getSector().getCurrentLocation().getFleets()) {
                    if (id.equals(fleet.getId())) {
                        defenderFleets.add(fleet);
                        break;
                    }
                }
            }
        }
    }

    // ── Intel tag grouping ──────────────────────────────────────────────

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(IntrigueIds.INTEL_TAG_INTRIGUE);
        return tags;
    }
}





