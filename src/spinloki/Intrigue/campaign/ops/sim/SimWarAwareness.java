package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.spi.WarAwareness;

/**
 * Sim-mode implementation of {@link WarAwareness}. Returns neutral defaults
 * for all queries since there's no actual star sector to assess.
 */
public class SimWarAwareness implements WarAwareness {

    @Override
    public Danger getDangerForMarket(String factionId, String marketId) {
        return Danger.NONE;
    }

    @Override
    public float dangerScoreModifier(String attackerFactionId, String targetMarketId) {
        return 0f;
    }

    @Override
    public int scaleFPByDanger(int baseFP, String attackerFactionId, String targetMarketId) {
        return baseFP;
    }

    @Override
    public float computeStrengthModifier(String attackerFactionId, String targetMarketId) {
        return 0f;
    }

    @Override
    public float computeStationDefenseModifier(String defenderFactionId, String targetMarketId) {
        return 0f;
    }

    @Override
    public void triggerMilitaryResponse(String defenderFactionId, String targetMarketId, float responseDuration) {
        // No-op in sim mode
    }
}

