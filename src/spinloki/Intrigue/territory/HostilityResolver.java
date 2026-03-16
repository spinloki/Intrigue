package spinloki.Intrigue.territory;

import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;

import java.util.*;

/**
 * Resolves hostility between subfaction pairs in a territory. Encapsulates
 * vanilla-derived parent-faction relationship data plus dynamic hostilities
 * that emulate vanilla's {@code FactionHostilityIntel}.
 *
 * <p>All methods are static and operate on the state passed in. The mutable
 * {@code dynamicHostilities} map lives in {@link TerritoryState} and is
 * passed by reference.</p>
 */
final class HostilityResolver {

    private HostilityResolver() {} // utility class

    // ── Vanilla-derived parent-faction relationship data ─────────────────

    /**
     * Baseline relationship between two parent factions.
     * Derived from vanilla Starsector's {@code SectorGen.initFactionRelationships()}.
     */
    enum ParentRelation {
        /** Always hostile (hardcoded in SectorGen, e.g. pirates vs hegemony). */
        HOSTILE,
        /** Not hostile by default (neutral or friendly in vanilla). */
        NEUTRAL,
        /** Hostilities break out semi-randomly, emulating vanilla's FactionHostilityIntel. */
        VOLATILE
    }

    /**
     * Parent-faction pairs that are always hostile at baseline.
     * Source: {@code SectorGen.initFactionRelationships()} — all pairs set to
     * {@code RepLevel.HOSTILE} excluding the two commented-out "replaced by hostilities"
     * pairs (hegemony↔tritachyon, hegemony↔persean).
     */
    static final Set<SubfactionPair> PARENT_HOSTILE_PAIRS;

    /**
     * Parent-faction pairs whose hostility comes and goes randomly, emulating
     * vanilla's {@code FactionHostilityIntel} / {@code CoreLifecyclePluginImpl}.
     */
    static final Set<SubfactionPair> PARENT_VOLATILE_PAIRS;

    static {
        Set<SubfactionPair> hostile = new HashSet<>();
        // Pirates hostile to nearly everyone
        hostile.add(new SubfactionPair("hegemony", "pirates"));
        hostile.add(new SubfactionPair("tritachyon", "pirates"));
        hostile.add(new SubfactionPair("luddic_church", "pirates"));
        hostile.add(new SubfactionPair("sindrian_diktat", "pirates"));
        hostile.add(new SubfactionPair("persean", "pirates"));
        hostile.add(new SubfactionPair("independent", "pirates"));
        // Luddic Path hostile to most major factions
        hostile.add(new SubfactionPair("hegemony", "luddic_path"));
        hostile.add(new SubfactionPair("tritachyon", "luddic_path"));
        hostile.add(new SubfactionPair("sindrian_diktat", "luddic_path"));
        hostile.add(new SubfactionPair("persean", "luddic_path"));
        hostile.add(new SubfactionPair("independent", "luddic_path"));
        // Knights of Ludd hostile to Tri-Tachyon (set in vanilla SectorGen)
        hostile.add(new SubfactionPair("knights_of_ludd", "tritachyon"));
        PARENT_HOSTILE_PAIRS = Collections.unmodifiableSet(hostile);

        Set<SubfactionPair> volatile_ = new HashSet<>();
        volatile_.add(new SubfactionPair("hegemony", "tritachyon"));
        volatile_.add(new SubfactionPair("hegemony", "persean"));
        PARENT_VOLATILE_PAIRS = Collections.unmodifiableSet(volatile_);
    }

    /**
     * Look up the vanilla baseline relationship between two parent factions.
     *
     * @return HOSTILE, NEUTRAL, or VOLATILE.
     */
    static ParentRelation getParentRelation(String parentA, String parentB) {
        if (parentA.equals(parentB)) return ParentRelation.NEUTRAL;
        SubfactionPair pair = new SubfactionPair(parentA, parentB);
        if (PARENT_HOSTILE_PAIRS.contains(pair)) return ParentRelation.HOSTILE;
        if (PARENT_VOLATILE_PAIRS.contains(pair)) return ParentRelation.VOLATILE;
        return ParentRelation.NEUTRAL;
    }

    /**
     * Determine whether two subfactions are hostile in the given territory context.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Entanglement that {@code setsHostile} → hostile.</li>
     *   <li>Entanglement that {@code suppressesHostile} → not hostile.</li>
     *   <li>Same parent faction → not hostile.</li>
     *   <li>Vanilla baseline: HOSTILE pairs → hostile, VOLATILE pairs → check
     *       dynamic hostility state, NEUTRAL pairs → not hostile.</li>
     * </ol>
     */
    static boolean isHostile(String a, String b,
                             Map<SubfactionPair, ActiveEntanglement> entanglements,
                             Map<SubfactionPair, Float> dynamicHostilities,
                             Map<String, SubfactionDef> subfactionDefs) {
        SubfactionPair pair = new SubfactionPair(a, b);
        ActiveEntanglement ent = entanglements.get(pair);
        if (ent != null) {
            if (ent.setsHostile()) return true;
            if (ent.suppressesHostile()) return false;
        }
        SubfactionDef defA = subfactionDefs.get(a);
        SubfactionDef defB = subfactionDefs.get(b);
        if (defA == null || defB == null) return true;

        ParentRelation rel = getParentRelation(defA.parentFactionId, defB.parentFactionId);
        switch (rel) {
            case HOSTILE:  return true;
            case VOLATILE:
                SubfactionPair parentPair = new SubfactionPair(defA.parentFactionId, defB.parentFactionId);
                return dynamicHostilities.containsKey(parentPair);
            case NEUTRAL:
            default:       return false;
        }
    }

    /**
     * Tick dynamic hostilities for VOLATILE parent-faction pairs.
     * <ul>
     *   <li>Active hostilities: decrement timer; remove and emit HOSTILITIES_ENDED when expired.</li>
     *   <li>Inactive volatile pairs (where both parents have subfactions present): roll random
     *       chance to START hostilities, emulating vanilla's FactionHostilityIntel.</li>
     * </ul>
     *
     * @return TickResults for any hostility state changes.
     */
    static List<TerritoryState.TickResult> tickDynamicHostilities(
            Map<SubfactionPair, Float> dynamicHostilities,
            Map<String, SubfactionPresence> presences,
            Map<String, SubfactionDef> subfactionDefs,
            Random opRand,
            IntrigueSettings settings) {
        List<TerritoryState.TickResult> results = new ArrayList<>();

        // Tick down active dynamic hostilities
        Iterator<Map.Entry<SubfactionPair, Float>> it = dynamicHostilities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SubfactionPair, Float> entry = it.next();
            float remaining = entry.getValue() - 1f;
            if (remaining <= 0f) {
                it.remove();
                results.add(new TerritoryState.TickResult.HostilitiesEnded(entry.getKey()));
            } else {
                entry.setValue(remaining);
            }
        }

        // Collect parent faction IDs actually present in this territory
        Set<String> presentParents = new HashSet<>();
        for (SubfactionPresence presence : presences.values()) {
            SubfactionDef def = subfactionDefs.get(presence.getSubfactionId());
            if (def != null) {
                presentParents.add(def.parentFactionId);
            }
        }

        // For each volatile pair where both parents are present and not already hostile,
        // roll a random chance to start hostilities
        for (SubfactionPair volatilePair : PARENT_VOLATILE_PAIRS) {
            if (dynamicHostilities.containsKey(volatilePair)) continue;
            if (!presentParents.contains(volatilePair.getFirst())) continue;
            if (!presentParents.contains(volatilePair.getSecond())) continue;

            if (opRand.nextFloat() < settings.volatileDailyChance) {
                float duration = settings.volatileMinDays
                        + opRand.nextFloat() * (settings.volatileMaxDays - settings.volatileMinDays);
                dynamicHostilities.put(volatilePair, duration);
                results.add(new TerritoryState.TickResult.HostilitiesStarted(volatilePair));
            }
        }

        return results;
    }

    /**
     * Count other subfactions in this territory that are ESTABLISHED+.
     * @param hostile If true, count hostile others. If false, count non-hostile others.
     */
    static int countOtherSubfactions(String excludeId, boolean hostile,
                                     Map<String, SubfactionPresence> presences,
                                     Map<SubfactionPair, ActiveEntanglement> entanglements,
                                     Map<SubfactionPair, Float> dynamicHostilities,
                                     Map<String, SubfactionDef> subfactionDefs) {
        int count = 0;
        for (SubfactionPresence other : presences.values()) {
            if (other.getSubfactionId().equals(excludeId)) continue;
            if (!other.getState().canLaunchOps()) continue;
            boolean isHostileToThem = isHostile(excludeId, other.getSubfactionId(),
                    entanglements, dynamicHostilities, subfactionDefs);
            if (hostile == isHostileToThem) {
                count++;
            }
        }
        return count;
    }
}
