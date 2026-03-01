package spinloki.Intrigue.campaign.spi;

/**
 * Abstraction over system-level military awareness.
 *
 * <p>In-game: backed by vanilla's WarSimScript — provides real fleet strength
 * data, system danger levels, and can trigger military response scripts.</p>
 *
 * <p>In-sim: a no-op implementation that returns neutral defaults.</p>
 */
public interface WarAwareness {

    /** Danger level for a faction in a star system. Mirrors vanilla's WarSimScript.LocationDanger. */
    enum Danger {
        NONE,
        MINIMAL,
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }

    /**
     * Get the danger level for a faction in the system containing a market.
     * Returns NONE if the market doesn't exist or isn't in a star system.
     */
    Danger getDangerForMarket(String factionId, String marketId);

    /**
     * Compute a raid-score modifier based on how well-defended the target system is.
     * Positive = easier target, negative = harder target.
     */
    float dangerScoreModifier(String attackerFactionId, String targetMarketId);

    /**
     * Scale a base fleet FP value by the danger level of the target system.
     * More dangerous systems → larger fleets sent.
     */
    int scaleFPByDanger(int baseFP, String attackerFactionId, String targetMarketId);

    /**
     * Compute a success chance modifier based on real military balance in the
     * target system. Positive = attacker has local superiority, negative = outnumbered.
     *
     * @param attackerFactionId the faction launching the op
     * @param targetMarketId    the market being targeted
     * @return modifier roughly in [-0.3, +0.2]
     */
    float computeStrengthModifier(String attackerFactionId, String targetMarketId);

    /**
     * Compute a defense modifier from orbital stations near the target market.
     * Always ≤ 0 (makes it harder for the attacker).
     *
     * @param defenderFactionId faction that owns the stations
     * @param targetMarketId    the market being defended
     * @return modifier in [-0.25, 0]
     */
    float computeStationDefenseModifier(String defenderFactionId, String targetMarketId);

    /**
     * Trigger vanilla military response: redirect nearby faction patrols to
     * defend a target market. No-op in sim mode.
     *
     * @param defenderFactionId the faction whose patrols should respond
     * @param targetMarketId    the market being attacked
     * @param responseDuration  how long the response lasts (days)
     */
    void triggerMilitaryResponse(String defenderFactionId, String targetMarketId, float responseDuration);
}

