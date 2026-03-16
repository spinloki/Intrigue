package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
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
 * Intel for a war-raid operation during a Territorial War entanglement.
 * A hunter fleet spawns at the raider's base and travels to the target system
 * to intercept and destroy enemy patrol fleets.
 *
 * <p>Uses {@link WarRaidRouteAI} instead of {@link PatrolRouteAI} to actively
 * hunt fleets belonging to the target subfaction.</p>
 */
public class TerritoryWarRaidIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryWarRaidIntel.class);

    /** Hunter fleets are larger than standard patrols. */
    private static final float BASE_COMBAT_FP = 100f;

    /** Days spent hunting in the target system. */
    private static final float HUNT_DAYS = 20f;

    private final String targetSubfactionId;
    private final String targetSubfactionName;

    public TerritoryWarRaidIntel(ActiveOp op, SubfactionDef raiderDef,
                                  BaseSlot raiderSlot, String targetSubfactionId,
                                  String targetSubfactionName, String territoryId) {
        super(op, raiderDef, territoryId);
        this.targetSubfactionId = targetSubfactionId;
        this.targetSubfactionName = targetSubfactionName;

        MarketAPI baseMarket = findBaseMarket(raiderSlot);
        if (baseMarket == null) {
            log.error("TerritoryWarRaidIntel: no market for slot " + raiderSlot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = baseMarket.getPrimaryEntity();

        // Destination is the target system (where the patrol is operating)
        SectorEntityToken destination = findSystemCenter(op.getTargetSystemId());
        if (destination == null) {
            log.error("TerritoryWarRaidIntel: target system not found: " + op.getTargetSystemId());
            endNow();
            return;
        }

        init(baseMarket, origin, destination);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        route.addSegment(new RouteSegment(HUNT_DAYS, destination));
        route.addSegment(new RouteSegment(travelDays, destination, origin));
        route.addSegment(new RouteSegment(END_DAYS, origin));
    }

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_LARGE;
    }

    @Override
    protected float getBaseCombatFP() {
        return BASE_COMBAT_FP;
    }

    @Override
    protected void configureFleetMemory(CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
    }

    @Override
    protected void addFleetAI(CampaignFleetAPI fleet, RouteData route) {
        fleet.addScript(new WarRaidRouteAI(fleet, route, targetSubfactionId));
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " War Raid";
    }

    @Override
    public String getSortString() {
        return "Intrigue War Raid";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Hunting: %s", 3f, Misc.getNegativeHighlightColor(), targetSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A %s hunter fleet is searching for enemy patrols in the " +
                        formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name);
        info.addPara("Target: %s forces", pad, Misc.getNegativeHighlightColor(), targetSubfactionName);
        info.addPara("Hunting ground: %s", pad, Misc.getHighlightColor(),
                formatSystemName(targetSystemId));
        if (fleetDestroyed) {
            info.addPara("The hunter fleet was destroyed.", Misc.getNegativeHighlightColor(), pad);
        }
    }

    public String getTargetSubfactionId() {
        return targetSubfactionId;
    }
}
