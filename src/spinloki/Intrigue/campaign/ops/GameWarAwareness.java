package spinloki.Intrigue.campaign.ops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI.ActionType;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.MilitaryResponseScript;
import com.fs.starfarer.api.impl.campaign.MilitaryResponseScript.MilitaryResponseParams;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript.LocationDanger;
import spinloki.Intrigue.campaign.spi.WarAwareness;

import java.util.logging.Logger;

/**
 * Game-side implementation of {@link WarAwareness} backed by vanilla's
 * {@link WarSimScript}. Provides real fleet strength data, system danger
 * levels, and can trigger military response scripts.
 */
public class GameWarAwareness implements WarAwareness {

    private static final Logger log = Logger.getLogger(GameWarAwareness.class.getName());

    // ── Danger mapping ──────────────────────────────────────────────────

    private static Danger toDanger(LocationDanger ld) {
        switch (ld) {
            case NONE:    return Danger.NONE;
            case MINIMAL: return Danger.MINIMAL;
            case LOW:     return Danger.LOW;
            case MEDIUM:  return Danger.MEDIUM;
            case HIGH:    return Danger.HIGH;
            case EXTREME: return Danger.EXTREME;
            default:      return Danger.NONE;
        }
    }

    private LocationDanger getLocationDangerForMarket(String factionId, String marketId) {
        if (factionId == null || marketId == null) return LocationDanger.NONE;
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (market == null || market.getStarSystem() == null) return LocationDanger.NONE;
        return WarSimScript.getDangerFor(factionId, market.getStarSystem());
    }

    // ── WarAwareness implementation ─────────────────────────────────────

    @Override
    public Danger getDangerForMarket(String factionId, String marketId) {
        return toDanger(getLocationDangerForMarket(factionId, marketId));
    }

    @Override
    public float dangerScoreModifier(String attackerFactionId, String targetMarketId) {
        Danger danger = getDangerForMarket(attackerFactionId, targetMarketId);
        switch (danger) {
            case NONE:    return 5f;
            case MINIMAL: return 2f;
            case LOW:     return 0f;
            case MEDIUM:  return -5f;
            case HIGH:    return -15f;
            case EXTREME: return -30f;
            default:      return 0f;
        }
    }

    @Override
    public int scaleFPByDanger(int baseFP, String attackerFactionId, String targetMarketId) {
        Danger danger = getDangerForMarket(attackerFactionId, targetMarketId);
        float multiplier;
        switch (danger) {
            case NONE:    multiplier = 0.8f; break;
            case MINIMAL: multiplier = 0.9f; break;
            case LOW:     multiplier = 1.0f; break;
            case MEDIUM:  multiplier = 1.2f; break;
            case HIGH:    multiplier = 1.5f; break;
            case EXTREME: multiplier = 2.0f; break;
            default:      multiplier = 1.0f; break;
        }
        return Math.max(1, (int) (baseFP * multiplier));
    }

    @Override
    public float computeStrengthModifier(String attackerFactionId, String targetMarketId) {
        if (attackerFactionId == null || targetMarketId == null) return 0f;
        MarketAPI market = Global.getSector().getEconomy().getMarket(targetMarketId);
        if (market == null || market.getStarSystem() == null) return 0f;

        float relEnemy = WarSimScript.getRelativeEnemyStrength(attackerFactionId, market.getStarSystem());
        // at 0.5 (even) → 0, at 0 (total dominance) → +0.2, at 1 (outnumbered) → -0.3
        return 0.2f - (relEnemy * 0.5f);
    }

    @Override
    public float computeStationDefenseModifier(String defenderFactionId, String targetMarketId) {
        if (defenderFactionId == null || targetMarketId == null) return 0f;
        MarketAPI market = Global.getSector().getEconomy().getMarket(targetMarketId);
        if (market == null || market.getStarSystem() == null || market.getPrimaryEntity() == null) return 0f;

        FactionAPI faction = Global.getSector().getFaction(defenderFactionId);
        if (faction == null) return 0f;

        float stationStr = WarSimScript.getStationStrength(faction, market.getStarSystem(), market.getPrimaryEntity());
        return Math.max(-0.25f, -(stationStr / 100f) * 0.05f);
    }

    @Override
    public void triggerMilitaryResponse(String defenderFactionId, String targetMarketId, float responseDuration) {
        if (defenderFactionId == null || targetMarketId == null) return;
        MarketAPI market = Global.getSector().getEconomy().getMarket(targetMarketId);
        if (market == null || market.getPrimaryEntity() == null) return;
        if (market.getPrimaryEntity().getContainingLocation() == null) return;

        FactionAPI faction = Global.getSector().getFaction(defenderFactionId);
        if (faction == null) return;

        SectorEntityToken target = market.getPrimaryEntity();
        MilitaryResponseParams params = new MilitaryResponseParams(
                ActionType.HOSTILE,
                "intrigue_assault_" + target.getId(),
                faction,
                target,
                0.5f,
                responseDuration);
        MilitaryResponseScript script = new MilitaryResponseScript(params);
        target.getContainingLocation().addScript(script);

        log.info("GameWarAwareness: triggered military response for " + defenderFactionId
                + " at " + market.getName() + " (duration=" + responseDuration + " days)");
    }
}


