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
 * Intel for a raid operation. A hostile subfaction launches a fleet from its station
 * to attack a rival's station. The fleet travels to the target system, orbits the
 * target station aggressively, then returns.
 *
 * <p>Spawns from the raider's base slot. On success, the target subfaction is demoted.</p>
 */
public class TerritoryRaidIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryRaidIntel.class);

    /** Raid fleets are significantly larger than patrols. */
    private static final float BASE_COMBAT_FP = 120f;
    /** Days spent attacking/orbiting the target station. */
    private static final float RAID_DAYS = 15f;

    private final String targetSubfactionId;
    private final String targetSubfactionName;

    /**
     * @param op        The RAID ActiveOp.
     * @param raiderDef The raiding subfaction's definition.
     * @param raiderSlot The raider's base slot (fleet origin).
     * @param targetSlot The target subfaction's base slot (fleet destination).
     * @param targetDef  The target subfaction's definition (for display).
     * @param territoryId Territory this raid occurs in.
     */
    public TerritoryRaidIntel(ActiveOp op, SubfactionDef raiderDef,
                               BaseSlot raiderSlot, BaseSlot targetSlot,
                               SubfactionDef targetDef, String territoryId) {
        super(op, raiderDef, territoryId);
        this.targetSubfactionId = op.getTargetSubfactionId();
        this.targetSubfactionName = targetDef != null ? targetDef.name : targetSubfactionId;

        MarketAPI raiderMarket = findBaseMarket(raiderSlot);
        if (raiderMarket == null) {
            log.error("TerritoryRaidIntel: no market for raider slot " + raiderSlot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = raiderMarket.getPrimaryEntity();

        // Destination is the target's station entity (or system center as fallback)
        SectorEntityToken destination = findStationEntity(targetSlot);
        if (destination == null) {
            destination = findSystemCenter(targetSlot.getSystemId());
        }
        if (destination == null) {
            log.error("TerritoryRaidIntel: target station/system not found");
            endNow();
            return;
        }

        init(raiderMarket, origin, destination);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        route.addSegment(new RouteSegment(RAID_DAYS, destination));
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
        // Raid fleets are aggressive
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Raid";
    }

    @Override
    public String getSortString() {
        return "Intrigue Raid";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Targeting: %s", 3f, Misc.getNegativeHighlightColor(), targetSubfactionName);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A %s raid fleet is launching an attack in the " +
                        formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name);
        info.addPara("Target: %s forces", pad, Misc.getNegativeHighlightColor(), targetSubfactionName);
        info.addPara("Route: %s → %s → return", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
        if (fleetDestroyed) {
            info.addPara("The raid fleet was destroyed.", Misc.getNegativeHighlightColor(), pad);
        }
    }

    public String getTargetSubfactionId() {
        return targetSubfactionId;
    }
}
