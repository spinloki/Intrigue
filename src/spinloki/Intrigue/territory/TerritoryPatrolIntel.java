package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;

/**
 * Intel for a cross-system patrol operation. Fleet departs from the subfaction's
 * base, patrols a target system, and returns.
 */
public class TerritoryPatrolIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryPatrolIntel.class);

    private static final float BASE_COMBAT_FP = 60f;
    private static final float PATROL_DAYS = 25f;

    public TerritoryPatrolIntel(ActiveOp op, SubfactionDef def, BaseSlot slot, String territoryId) {
        super(op, def, territoryId);

        MarketAPI baseMarket = findBaseMarket(slot);
        if (baseMarket == null) {
            log.error("TerritoryPatrolIntel: no market for slot " + slot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = baseMarket.getPrimaryEntity();
        SectorEntityToken target = findSystemCenter(targetSystemId);
        if (target == null) {
            log.error("TerritoryPatrolIntel: target system not found: " + targetSystemId);
            endNow();
            return;
        }

        init(baseMarket, origin, target);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        route.addSegment(new RouteSegment(PATROL_DAYS, destination));
        route.addSegment(new RouteSegment(travelDays, destination, origin));
        route.addSegment(new RouteSegment(END_DAYS, origin));
    }

    @Override
    protected String getFleetType() {
        return FleetTypes.PATROL_MEDIUM;
    }

    @Override
    protected float getBaseCombatFP() {
        return BASE_COMBAT_FP;
    }

    // ── Intel display ────────────────────────────────────────────────────

    @Override
    protected String getName() {
        return def.name + " Patrol";
    }

    @Override
    public String getSortString() {
        return "Intrigue Patrol";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Route: %s → %s", 3f, Misc.getHighlightColor(),
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("A %s patrol fleet is operating in the " + formatSystemName(territoryId) +
                " territory.", pad, faction.getBaseUIColor(), def.name);
        info.addPara("Route: %s → %s → return", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId), formatSystemName(targetSystemId));
        if (fleetDestroyed) {
            info.addPara("The patrol fleet was destroyed.", Misc.getNegativeHighlightColor(), pad);
        }
    }
}
