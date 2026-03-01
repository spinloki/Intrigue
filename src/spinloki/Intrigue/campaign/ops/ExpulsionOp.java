package spinloki.Intrigue.campaign.ops;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.logging.Logger;

/**
 * Consequence op: a subfaction's presence is demoted by one tier after sustained
 * critically-low cohesion. DOMINANT→FORTIFIED→ESTABLISHED→NONE (skips SCOUTING).
 * Legitimacy takes a significant hit.
 *
 * <p>Spawns a visible evacuation fleet retreating from the territory back to
 * the home market, giving the player visual feedback that a faction is pulling out.</p>
 */
public class ExpulsionOp extends IntrigueOp {

    private static final long serialVersionUID = 2L;
    private static final Logger log = Logger.getLogger(ExpulsionOp.class.getName());
    private static final int LEGITIMACY_LOSS = 20;

    private final String subfactionId;

    public ExpulsionOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        setTerritoryId(territoryId);

        // Try to resolve the base market in the territory so an evacuation fleet can depart
        String baseMarketId = resolveBaseMarket(subfaction, territoryId);
        String homeMarketId = subfaction.getHomeMarketId();

        if (baseMarketId != null && homeMarketId != null && !baseMarketId.equals(homeMarketId)) {
            // Small retreat fleet: light escort + freighters hauling their stuff out
            int evacuationFP = 8 + (int) (subfaction.getHomeCohesion() * 0.1f);
            phases.add(new EvacuationPhase(
                    subfaction.getFactionId(),
                    baseMarketId,
                    homeMarketId,
                    evacuationFP,
                    subfaction.getName()));
        } else {
            // Can't resolve valid departure/arrival markets — resolve instantly
            phases.add(new TimedPhase("Presence failing", 1f));
        }
    }

    /**
     * Resolve the subfaction's base market in the territory.
     * Returns null if not a real game market.
     */
    private static String resolveBaseMarket(IntrigueSubfaction subfaction, String territoryId) {
        IntrigueTerritoryAccess territories = IntrigueServices.territories();
        if (territories == null) return null;

        IntrigueTerritory territory = territories.getById(territoryId);
        if (territory == null) return null;

        String baseMarketId = territory.getBaseMarketId(subfaction.getSubfactionId());
        if (baseMarketId == null || baseMarketId.isEmpty()) return null;

        // Verify it's a real market
        if (PhaseUtil.isSectorAvailable()) {
            com.fs.starfarer.api.campaign.econ.MarketAPI market =
                    com.fs.starfarer.api.Global.getSector().getEconomy().getMarket(baseMarketId);
            if (market != null && market.getPrimaryEntity() != null) {
                return baseMarketId;
            }
        }
        return null;
    }

    @Override public String getOpTypeName() { return "Expulsion"; }
    @Override protected void onStarted() {
        log.info("Presence demotion imminent for " + subfactionId + " in territory " + getTerritoryId());
    }

    @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        IntrigueTerritoryAccess territories = IntrigueServices.territories();

        if (territories != null) {
            IntrigueTerritory territory = territories.getById(getTerritoryId());
            if (territory != null) {
                IntrigueTerritory.Presence before = territory.getPresence(subfactionId);
                IntrigueTerritory.Presence after = territory.demotePresence(subfactionId);
                log.info("Expulsion: " + subfactionId + " in " + territory.getName()
                        + " demoted " + before + " → " + after);
            }
        }

        if (sf != null) {
            sf.setLegitimacy(sf.getLegitimacy() - LEGITIMACY_LOSS);
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Expulsion resolved: " + subfactionId + " legitimacy -" + LEGITIMACY_LOSS);
        }
    }
}

