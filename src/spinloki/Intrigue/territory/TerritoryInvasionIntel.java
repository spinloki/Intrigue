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
 * Intel for an invasion operation triggered by stagnation. An external
 * subfaction's parent faction sends multiple large fleets to the target
 * subfaction's base system. On success, the target is evicted and the
 * invader takes over at ESTABLISHED.
 *
 * <p>Fleets spawn from the invader's parent-faction market and travel
 * together to the target base. This is a one-way deployment — no
 * return trip.</p>
 */
public class TerritoryInvasionIntel extends MultiFleetOpIntel {

    private static final Logger log = Global.getLogger(TerritoryInvasionIntel.class);

    /** FP per invasion fleet — heavy combat formations. */
    private static final float INVASION_FP = 150f;

    /** Number of invasion fleets. */
    private static final int FLEET_COUNT = 3;

    /** Days spent assaulting the target base. */
    private static final float ASSAULT_DAYS = 20f;

    private final String targetSubfactionId;
    private final String targetSubfactionName;

    /**
     * @param op            The INVASION ActiveOp.
     * @param invaderDef    The invading subfaction definition.
     * @param targetSlot    The target subfaction's base slot (assault target).
     * @param targetDef     The target subfaction definition.
     * @param territoryId   Territory being invaded.
     */
    public TerritoryInvasionIntel(ActiveOp op, SubfactionDef invaderDef,
                                   BaseSlot targetSlot, SubfactionDef targetDef,
                                   String territoryId) {
        super(op, invaderDef, territoryId);
        this.targetSubfactionId = targetDef.id;
        this.targetSubfactionName = targetDef.name;

        // Spawn from invader's parent faction market
        MarketAPI parentMarket = findFactionMarket(invaderDef.parentFactionId);
        if (parentMarket == null) {
            log.error("TerritoryInvasionIntel: no parent market for " + invaderDef.parentFactionId);
            endNow();
            return;
        }

        SectorEntityToken origin = parentMarket.getPrimaryEntity();

        // Target: the target subfaction's station
        SectorEntityToken targetStation = findStationEntity(targetSlot);
        if (targetStation == null) {
            targetStation = findSystemCenter(targetSlot.getSystemId());
        }
        if (targetStation == null) {
            log.error("TerritoryInvasionIntel: target base not found for " + targetDef.id);
            endNow();
            return;
        }

        float travelDays = computeTravelDays(origin, targetStation);

        // Create invasion fleet routes (staggered slightly)
        for (int i = 0; i < FLEET_COUNT; i++) {
            RouteData invasionRoute = addManagedRoute(parentMarket, FleetTypes.TASK_FORCE,
                    String.valueOf(i));
            float staggeredPrep = PREP_DAYS + (i * 2f);
            invasionRoute.addSegment(new RouteSegment(staggeredPrep, origin));
            invasionRoute.addSegment(new RouteSegment(travelDays, origin, targetStation));
            invasionRoute.addSegment(new RouteSegment(ASSAULT_DAYS, targetStation));
            // No return trip — fleet integrates or disbands
            allRoutes.add(invasionRoute);
        }

        initMultiFleet(true);

        log.info("TerritoryInvasionIntel: " + invaderDef.name + " invading " +
                targetSubfactionName + " with " + FLEET_COUNT + " fleets");
    }

    // ── Route / fleet overrides ──────────────────────────────────────────

    @Override
    protected String getFleetType() {
        return FleetTypes.TASK_FORCE;
    }

    @Override
    protected float getBaseCombatFP() {
        return INVASION_FP;
    }

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        MarketAPI market = route.getMarket();
        if (market == null) return null;

        float tankerFP = INVASION_FP * TANKER_FRACTION;
        float freighterFP = INVASION_FP * FREIGHTER_FRACTION;

        FleetParamsV3 params = new FleetParamsV3(
                market,
                market.getLocationInHyperspace(),
                subfactionId,
                route.getQualityOverride(),
                FleetTypes.TASK_FORCE,
                INVASION_FP,
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

        log.info("TerritoryInvasionIntel: spawned invasion fleet (" +
                fleet.getFleetPoints() + " FP) for " + def.name);

        return fleet;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Invasion";
    }

    @Override
    public String getSortString() {
        return "Intrigue Invasion";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Invading: %s", 3f, Misc.getNegativeHighlightColor(), targetSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A major %s invasion force is advancing on %s positions " +
                        "in the " + formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name, targetSubfactionName);
        info.addPara("Invasion force: %s heavy fleets", pad,
                Misc.getHighlightColor(), String.valueOf(FLEET_COUNT));
        info.addPara("Origin: %s", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId));
        info.addPara("Target: %s in %s", pad, Misc.getNegativeHighlightColor(),
                targetSubfactionName, formatSystemName(targetSystemId));
        if (fleetsDestroyed > 0) {
            info.addPara("%s of %s invasion fleets destroyed.", pad,
                    Misc.getNegativeHighlightColor(),
                    String.valueOf(fleetsDestroyed), String.valueOf(FLEET_COUNT));
        }
    }

    public String getTargetSubfactionId() {
        return targetSubfactionId;
    }
}
