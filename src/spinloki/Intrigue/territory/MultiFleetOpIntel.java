package spinloki.Intrigue.territory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for multi-fleet territory operations. Manages a list of routes,
 * tracks fleet destruction, and handles the multi-route lifecycle (advance
 * until all routes expire, then end).
 *
 * <p>Subclasses create routes in their constructors using {@link #addManagedRoute},
 * add them to {@link #allRoutes}, and call {@link #initMultiFleet} to register
 * with the sector.</p>
 */
public abstract class MultiFleetOpIntel extends TerritoryOpIntel {

    private static final Logger log = Global.getLogger(MultiFleetOpIntel.class);

    /** All routes managed by this Intel. */
    protected final List<RouteData> allRoutes = new ArrayList<>();

    /** How many of our fleets have been destroyed. */
    protected int fleetsDestroyed = 0;

    protected MultiFleetOpIntel(ActiveOp op, SubfactionDef def, String territoryId) {
        super(op, def, territoryId);
    }

    /**
     * Create and register a managed route with the RouteManager.
     *
     * @param market    The market the fleet spawns from.
     * @param fleetType The vanilla FleetTypes constant.
     * @param suffix    Unique suffix for this route within the op (e.g. "guardian", "0").
     * @return The created RouteData (not yet added to {@link #allRoutes}).
     */
    protected RouteData addManagedRoute(MarketAPI market, String fleetType, String suffix) {
        return addManagedRoute(market, fleetType, suffix, subfactionId);
    }

    /**
     * Create and register a managed route with a custom faction ID.
     *
     * @param market    The market the fleet spawns from.
     * @param fleetType The vanilla FleetTypes constant.
     * @param suffix    Unique suffix for this route within the op.
     * @param factionId The faction ID for the route's fleet.
     * @return The created RouteData (not yet added to {@link #allRoutes}).
     */
    protected RouteData addManagedRoute(MarketAPI market, String fleetType, String suffix, String factionId) {
        OptionalFleetData extra = new OptionalFleetData(market);
        extra.factionId = factionId;
        extra.fleetType = fleetType;

        return RouteManager.getInstance().addRoute(
                "intrigue_" + opType.name().toLowerCase() + "_" + opId + "_" + suffix,
                market,
                Misc.genRandomSeed(),
                extra,
                this
        );
    }

    /**
     * Register this Intel with the sector after routes have been created.
     * Sets the base class {@code route} field to the first route. Call this
     * at the end of the subclass constructor.
     *
     * @param important Whether this Intel should be marked as important.
     */
    protected void initMultiFleet(boolean important) {
        if (allRoutes.isEmpty()) {
            log.error(getClass().getSimpleName() + ": no routes created");
            endNow();
            return;
        }

        route = allRoutes.get(0);

        Global.getSector().addScript(this);
        Global.getSector().getIntelManager().addIntel(this, true);
        setImportant(important);
    }

    @Override
    protected void buildRoute(RouteData route, SectorEntityToken origin, SectorEntityToken destination) {
        // Not used — multi-fleet routes are built in subclass constructors
    }

    // ── Lifecycle (multi-route) ──────────────────────────────────────────

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

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
                                                CampaignEventListener.FleetDespawnReason reason,
                                                Object param) {
        if (reason == CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE) {
            fleetsDestroyed++;
            fleetDestroyed = true;
            log.info(getClass().getSimpleName() + ": fleet destroyed (" +
                    fleetsDestroyed + "/" + allRoutes.size() + ")");
        }
    }
}
