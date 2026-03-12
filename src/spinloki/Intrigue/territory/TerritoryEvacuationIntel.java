package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.awt.*;

/**
 * Intel for an evacuation operation. A subfaction that has no hostile rival to
 * raid them instead pulls back voluntarily. The fleet spawns at the subfaction's
 * station and travels to a parent faction market.
 *
 * <p>On success, the subfaction is demoted one level but gains bonus leverage
 * (soft landing). On failure (fleet destroyed en route), demotion still happens
 * but without the bonus.</p>
 */
public class TerritoryEvacuationIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(TerritoryEvacuationIntel.class);

    /** Evacuation fleets are moderate — carrying personnel and materiel. */
    private static final float BASE_COMBAT_FP = 80f;

    /**
     * @param op          The EVACUATION ActiveOp.
     * @param def         The subfaction definition.
     * @param slot        The subfaction's base slot (fleet origin).
     * @param territoryId Territory the evacuation departs from.
     */
    public TerritoryEvacuationIntel(ActiveOp op, SubfactionDef def,
                                     BaseSlot slot, String territoryId) {
        super(op, def, territoryId);

        MarketAPI baseMarket = findBaseMarket(slot);
        if (baseMarket == null) {
            log.error("TerritoryEvacuationIntel: no market for slot " + slot.getSlotId());
            endNow();
            return;
        }

        SectorEntityToken origin = baseMarket.getPrimaryEntity();

        // Destination is a parent faction market
        MarketAPI parentMarket = findFactionMarket(def.parentFactionId);
        if (parentMarket == null) {
            log.error("TerritoryEvacuationIntel: no parent market for " + def.parentFactionId);
            endNow();
            return;
        }

        SectorEntityToken destination = parentMarket.getPrimaryEntity();

        init(baseMarket, origin, destination);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        float travelDays = computeTravelDays(origin, destination);
        route.addSegment(new RouteSegment(PREP_DAYS, origin));
        route.addSegment(new RouteSegment(travelDays, origin, destination));
        // No return trip — the fleet is withdrawing
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
        return def.name + " Evacuation";
    }

    @Override
    public String getSortString() {
        return "Intrigue Evacuation";
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        info.addPara("Withdrawing from %s", 3f, Misc.getHighlightColor(),
                formatSystemName(territoryId));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float pad = 10f;
        FactionAPI faction = Global.getSector().getFaction(subfactionId);
        info.addPara("The %s is withdrawing forces from the " +
                        formatSystemName(territoryId) + " territory.",
                pad, faction.getBaseUIColor(), def.name);
        info.addPara("Departing: %s", pad, Misc.getHighlightColor(),
                formatSystemName(originSystemId));
        if (fleetDestroyed) {
            info.addPara("The evacuation fleet was destroyed.",
                    Misc.getNegativeHighlightColor(), pad);
        }
    }

    @Override
    public SectorEntityToken getMapLocation(com.fs.starfarer.api.ui.SectorMapAPI map) {
        // Show on map at the origin (where the fleet is departing from)
        return findSystemCenter(originSystemId);
    }
}
