package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;

/**
 * Intel for a protection patrol during a Hired Protection entanglement.
 * The Protector sends 3 patrol fleets and 1 large guardian fleet to defend
 * the Hirer's base system.
 *
 * <p>This is a multi-fleet Intel: one large guardian fleet orbits the Hirer's
 * station directly, while 3 standard patrol fleets patrol the surrounding
 * system. All fleets despawn when the op resolves.</p>
 */
public class TerritoryProtectionPatrolIntel extends MultiFleetOpIntel {

    private static final Logger log = Global.getLogger(TerritoryProtectionPatrolIntel.class);

    /** FP for each of the 3 patrol fleets. */
    private static final float PATROL_FP = 50f;

    /** FP for the single large guardian fleet. */
    private static final float GUARDIAN_FP = 120f;

    /** Days patrols spend in the target system before returning. */
    private static final float PATROL_DAYS = 25f;

    /** Days the guardian orbits the hirer's station (long deployment). */
    private static final float GUARDIAN_ORBIT_DAYS = 60f;

    /** Number of patrol fleets (in addition to the guardian). */
    private static final int PATROL_COUNT = 3;

    /** Days between each patrol departure (stagger to avoid clumping). */
    private static final float STAGGER_DAYS = 3f;

    private final String hirerSubfactionName;

    /**
     * @param op             The PROTECTION_PATROL ActiveOp.
     * @param protectorDef   The subfaction providing protection.
     * @param protectorSlot  The protector's base slot (fleet origin).
     * @param hirerSlot      The hirer's base slot (fleet destination).
     * @param hirerName      Display name of the hirer subfaction.
     * @param territoryId    Territory this operates in.
     */
    public TerritoryProtectionPatrolIntel(ActiveOp op, SubfactionDef protectorDef,
                                           BaseSlot protectorSlot, BaseSlot hirerSlot,
                                           String hirerName, String territoryId) {
        super(op, protectorDef, territoryId);
        this.hirerSubfactionName = hirerName;

        MarketAPI protectorMarket = findBaseMarket(protectorSlot);
        if (protectorMarket == null) {
            log.error("TerritoryProtectionPatrolIntel: no market for protector slot " +
                    protectorSlot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = protectorMarket.getPrimaryEntity();

        SectorEntityToken hirerStation = findStationEntity(hirerSlot);
        if (hirerStation == null) {
            hirerStation = findSystemCenter(hirerSlot.getSystemId());
        }
        if (hirerStation == null) {
            log.error("TerritoryProtectionPatrolIntel: hirer base not found");
            endNow();
            return;
        }

        float travelDays = computeTravelDays(origin, hirerStation);

        // Guardian route — one large fleet orbiting the hirer's station
        RouteData guardianRoute = addManagedRoute(protectorMarket, FleetTypes.PATROL_LARGE, "guardian");
        guardianRoute.addSegment(new RouteSegment(PREP_DAYS, origin));
        guardianRoute.addSegment(new RouteSegment(travelDays, origin, hirerStation));
        guardianRoute.addSegment(new RouteSegment(GUARDIAN_ORBIT_DAYS, hirerStation));
        allRoutes.add(guardianRoute);

        // 3 patrol routes — staggered departures
        for (int i = 0; i < PATROL_COUNT; i++) {
            RouteData patrolRoute = addManagedRoute(protectorMarket, FleetTypes.PATROL_MEDIUM,
                    "patrol_" + i);
            float staggeredPrep = PREP_DAYS + (i * STAGGER_DAYS);
            patrolRoute.addSegment(new RouteSegment(staggeredPrep, origin));
            patrolRoute.addSegment(new RouteSegment(travelDays, origin, hirerStation));
            patrolRoute.addSegment(new RouteSegment(PATROL_DAYS, hirerStation));
            patrolRoute.addSegment(new RouteSegment(travelDays, hirerStation, origin));
            patrolRoute.addSegment(new RouteSegment(END_DAYS, origin));
            allRoutes.add(patrolRoute);
        }

        initMultiFleet(false);

        log.info("TerritoryProtectionPatrolIntel: created " + allRoutes.size() +
                " fleet routes for " + def.name + " protecting " + hirerSubfactionName);
    }

    // ── Route / fleet overrides ──────────────────────────────────────────

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_LARGE; // Default; actual type comes from route extra
    }

    @Override
    protected float getBaseCombatFP() {
        return GUARDIAN_FP; // Default; actual FP is determined in spawnFleet
    }

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        String fleetType = route.getExtra().fleetType;
        boolean isGuardian = FleetTypes.PATROL_LARGE.equals(fleetType);
        float combatFP = isGuardian ? GUARDIAN_FP : PATROL_FP;
        float tankerFP = combatFP * TANKER_FRACTION;
        float freighterFP = combatFP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                subfactionId,
                route.getQualityOverride(),
                fleetType,
                combatFP,
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

        log.info("TerritoryProtectionPatrolIntel: spawned " +
                (isGuardian ? "guardian" : "patrol") + " fleet (" +
                fleet.getFleetPoints() + " FP) for " + def.name);

        return fleet;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Protection Detail";
    }

    @Override
    public String getSortString() {
        return "Intrigue Protection";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Defending: %s", 3f, Misc.getHighlightColor(), hirerSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("The %s has deployed a protection detail to defend %s " +
                        "operations in the " + formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name, hirerSubfactionName);
        info.addPara("Forces: 1 guardian fleet + %s patrol fleets", pad,
                Misc.getHighlightColor(), String.valueOf(PATROL_COUNT));
        info.addPara("Deployed to: %s", pad, Misc.getHighlightColor(),
                formatSystemName(targetSystemId));
        if (fleetsDestroyed > 0) {
            info.addPara("%s of %s fleets destroyed.", pad,
                    Misc.getNegativeHighlightColor(),
                    String.valueOf(fleetsDestroyed), String.valueOf(allRoutes.size()));
        }
    }
}
