package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Consequence op: civil war erupts within a subfaction whose home cohesion
 * has been critically low for too long. Fleets spawn en-masse and fight
 * each other. When it resolves, the subfaction's home cohesion and
 * legitimacy are both reset to 50 - a clean slate after the chaos.
 */
public class CivilWarOp extends IntrigueOp {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(CivilWarOp.class.getName());

    private final String subfactionId;
    private final String loyalistMarketId;
    private final String rebelMarketId;

    public CivilWarOp(String opId, IntrigueSubfaction subfaction) {
        super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
        this.subfactionId = subfaction.getSubfactionId();
        this.loyalistMarketId = subfaction.getHomeMarketId();

        setIntelSourceMarketId(loyalistMarketId);
        setIntelDestinationMarketId(loyalistMarketId);

        // Civil war fleets are much larger than infighting
        int fleetFP = 40 + (int) (subfaction.getPower() * 0.6f);

        // Rebels spawn from a different market owned by the same faction
        this.rebelMarketId = pickRebelMarket(subfaction.getFactionId(), loyalistMarketId);

        phases.add(new CivilWarPhase(
                subfaction.getFactionId(),
                loyalistMarketId,
                rebelMarketId,
                fleetFP,
                subfaction.getName()));
    }

    @Override
    public void createIntelDetails(Object infoObj, float opad) {
        TooltipMakerAPI info = (TooltipMakerAPI) infoObj;
        java.awt.Color h = Misc.getHighlightColor();
        java.awt.Color tc = Misc.getTextColor();

        String loyalistName = resolveMarketDisplayName(loyalistMarketId);
        String rebelName = resolveMarketDisplayName(rebelMarketId);

        info.addPara("Loyalist HQ: %s", opad, tc, h, loyalistName);
        info.addPara("Rebel HQ: %s", opad, tc, h, rebelName);
    }

    private static String resolveMarketDisplayName(String marketId) {
        if (marketId == null) return "Unknown";
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (market == null) return marketId;
        String name = market.getName();
        if (market.getStarSystem() != null) {
            name += " (" + market.getStarSystem().getBaseName() + ")";
        }
        return name;
    }

    /**
     * Pick a market for the rebel side to spawn from, preferring a different
     * market than the loyalist home. Falls back to the same market if the
     * faction only owns one.
     */
    private static String pickRebelMarket(String factionId, String loyalistMarketId) {
        List<String> candidates = new ArrayList<>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m == null || m.getPrimaryEntity() == null) continue;
            if (!factionId.equals(m.getFactionId())) continue;
            if (!m.getId().equals(loyalistMarketId)) {
                candidates.add(m.getId());
            }
        }
        if (candidates.isEmpty()) return loyalistMarketId;
        Collections.shuffle(candidates);
        return candidates.get(0);
    }

    @Override public String getOpTypeName() { return "Civil War"; }
    @Override protected void onStarted() {
        log.info("CIVIL WAR erupted within " + subfactionId + "!");
    }

    @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

    @Override
    protected void applyOutcome() {
        IntrigueSubfaction sf = getInitiatorSubfaction();
        if (sf != null) {
            sf.setHomeCohesion(50);
            sf.setLegitimacy(50);
            sf.resetLowHomeCohesionTicks();
            sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            log.info("Civil War resolved: " + subfactionId
                    + " cohesion and legitimacy reset to 50");
        }
    }
}

