package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Intel for a joint strike during a Shared-Enemy Pact entanglement.
 * Multiple large fleets from one side of the pact converge on a staging
 * point (jump point in the target system), then assault the enemy base.
 *
 * <p>Each JOINT_STRIKE op creates one of these Intels. Both pact members
 * launch their own ops, so the player sees two groups converging from
 * different directions on the same target.</p>
 */
public class TerritoryJointStrikeIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryJointStrikeIntel.class);

    /** FP per strike fleet — large combat formations. */
    private static final float STRIKE_FP = 130f;

    /** Number of strike fleets per Intel. */
    private static final int FLEET_COUNT = 2;

    /** Days spent staging at the jump point. */
    private static final float STAGING_DAYS = 5f;

    /** Days spent assaulting the target base. */
    private static final float ASSAULT_DAYS = 15f;

    private final String targetSubfactionId;
    private final String targetSubfactionName;

    private final List<RouteData> allRoutes = new ArrayList<>();
    private int fleetsDestroyed = 0;

    /**
     * @param op            The JOINT_STRIKE ActiveOp.
     * @param strikerDef    The attacking subfaction (one side of the pact).
     * @param strikerSlot   The attacker's base slot (fleet origin).
     * @param targetSlot    The enemy subfaction's base slot (assault target).
     * @param targetDef     The enemy subfaction definition.
     * @param territoryId   Territory this operates in.
     */
    public TerritoryJointStrikeIntel(ActiveOp op, SubfactionDef strikerDef,
                                      BaseSlot strikerSlot, BaseSlot targetSlot,
                                      SubfactionDef targetDef, String territoryId) {
        super(op, strikerDef, territoryId);
        this.targetSubfactionId = targetDef.id;
        this.targetSubfactionName = targetDef.name;

        MarketAPI strikerMarket = findBaseMarket(strikerSlot);
        if (strikerMarket == null) {
            log.error("TerritoryJointStrikeIntel: no market for striker slot " +
                    strikerSlot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = strikerMarket.getPrimaryEntity();

        // Target: enemy station
        SectorEntityToken targetStation = findStationEntity(targetSlot);
        if (targetStation == null) {
            targetStation = findSystemCenter(targetSlot.getSystemId());
        }
        if (targetStation == null) {
            log.error("TerritoryJointStrikeIntel: target base not found for " + targetDef.id);
            endNow();
            return;
        }

        // Staging point: a jump point in the target system
        SectorEntityToken stagingPoint = findJumpPoint(targetSlot.getSystemId());
        if (stagingPoint == null) {
            // Fallback: stage at system center
            stagingPoint = findSystemCenter(targetSlot.getSystemId());
        }
        if (stagingPoint == null) {
            log.error("TerritoryJointStrikeIntel: no staging point in target system");
            endNow();
            return;
        }

        float travelToStaging = computeTravelDays(origin, stagingPoint);
        float stagingToTarget = computeInSystemTravelDays(stagingPoint, targetStation);

        // Create strike fleet routes (staggered slightly)
        for (int i = 0; i < FLEET_COUNT; i++) {
            RouteData strikeRoute = addManagedRoute(strikerMarket, i);
            float staggeredPrep = PREP_DAYS + (i * 2f);
            strikeRoute.addSegment(new RouteSegment(staggeredPrep, origin));
            strikeRoute.addSegment(new RouteSegment(travelToStaging, origin, stagingPoint));
            strikeRoute.addSegment(new RouteSegment(STAGING_DAYS, stagingPoint));
            strikeRoute.addSegment(new RouteSegment(stagingToTarget, stagingPoint, targetStation));
            strikeRoute.addSegment(new RouteSegment(ASSAULT_DAYS, targetStation));
            strikeRoute.addSegment(new RouteSegment(travelToStaging + travelToStaging,
                    targetStation, origin));
            strikeRoute.addSegment(new RouteSegment(END_DAYS, origin));
            allRoutes.add(strikeRoute);
        }

        if (!allRoutes.isEmpty()) {
            route = allRoutes.get(0); // Base class route field
        }

        Global.getSector().addScript(this);
        Global.getSector().getIntelManager().addIntel(this, true);
        setImportant(false);

        log.info("TerritoryJointStrikeIntel: " + def.name + " launching " + FLEET_COUNT +
                " strike fleets against " + targetSubfactionName);
    }

    private RouteData addManagedRoute(MarketAPI market, int index) {
        OptionalFleetData extra = new OptionalFleetData(market);
        extra.factionId = subfactionId;
        extra.fleetType = FleetTypes.PATROL_LARGE;

        return RouteManager.getInstance().addRoute(
                "intrigue_joint_strike_" + opId + "_" + index,
                market,
                Misc.genRandomSeed(),
                extra,
                this
        );
    }

    /**
     * Find a jump point in the given system for staging.
     */
    private SectorEntityToken findJumpPoint(String systemId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (!system.getId().equals(systemId)) continue;
            List<SectorEntityToken> jumpPoints = system.getJumpPoints();
            if (!jumpPoints.isEmpty()) {
                return jumpPoints.get(0);
            }
            break;
        }
        return null;
    }

    /**
     * Compute in-system travel time (shorter than hyperspace travel).
     */
    private float computeInSystemTravelDays(SectorEntityToken from, SectorEntityToken to) {
        float dist = Misc.getDistance(from.getLocation(), to.getLocation());
        float speed = Misc.getSpeedForBurnLevel(8);
        float days = dist / speed / Global.getSector().getClock().getSecondsPerDay();
        return Math.max(days, 1f);
    }

    // ── Route / fleet overrides ──────────────────────────────────────────

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        // Not used — routes built in constructor
    }

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_LARGE;
    }

    @Override
    protected float getBaseCombatFP() {
        return STRIKE_FP;
    }

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        float tankerFP = STRIKE_FP * TANKER_FRACTION;
        float freighterFP = STRIKE_FP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                subfactionId,
                route.getQualityOverride(),
                FleetTypes.PATROL_LARGE,
                STRIKE_FP,
                freighterFP,
                tankerFP,
                0f, 0f, 0f,
                def.fleetQualityMod
        );
        params.timestamp = route.getTimestamp();

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (fleet == null || fleet.isEmpty()) return null;

        fleet.setFaction(subfactionId, true);
        configureFleetMemory(fleet);
        fleet.getEventListeners().add(this);

        market.getContainingLocation().addEntity(fleet);
        fleet.setFacing((float) Math.random() * 360f);
        SectorEntityToken primary = market.getPrimaryEntity();
        fleet.setLocation(primary.getLocation().x, primary.getLocation().y);

        fleet.addScript(new PatrolRouteAI(fleet, route));

        log.info("TerritoryJointStrikeIntel: spawned strike fleet (" +
                fleet.getFleetPoints() + " FP) for " + def.name);

        return fleet;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
    }

    // ── Fleet destruction tracking ───────────────────────────────────────

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
                                                CampaignEventListener.FleetDespawnReason reason,
                                                Object param) {
        if (reason == CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetsDestroyed++;
            fleetDestroyed = true;
            log.info("TerritoryJointStrikeIntel: strike fleet destroyed (" +
                    fleetsDestroyed + "/" + FLEET_COUNT + ")");
        }
    }

    // ── Lifecycle overrides (multi-route) ────────────────────────────────

    @Override
    protected void advanceImpl(float amount) {
        boolean allExpired = true;
        for (RouteData r : allRoutes) {
            if (!r.isExpired()) {
                allExpired = false;
                break;
            }
        }
        if (allExpired && !allRoutes.isEmpty()) {
            endAfterDelay();
        }
    }

    @Override
    protected void notifyEnded() {
        for (RouteData r : allRoutes) {
            RouteManager.getInstance().removeRoute(r);
        }
        Global.getSector().removeScript(this);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Joint Strike";
    }

    @Override
    public String getSortString() {
        return "Intrigue Joint Strike";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Target: %s", 3f, Misc.getNegativeHighlightColor(), targetSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A coordinated %s strike force is assembling for a joint " +
                        "assault on %s positions in the " +
                        formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name, targetSubfactionName);
        info.addPara("Strike force: %s combat fleets", pad,
                Misc.getHighlightColor(), String.valueOf(FLEET_COUNT));
        info.addPara("Staging: %s → %s", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
        if (fleetsDestroyed > 0) {
            info.addPara("%s of %s strike fleets destroyed.", pad,
                    Misc.getNegativeHighlightColor(),
                    String.valueOf(fleetsDestroyed), String.valueOf(FLEET_COUNT));
        }
    }

    public String getTargetSubfactionId() {
        return targetSubfactionId;
    }
}
