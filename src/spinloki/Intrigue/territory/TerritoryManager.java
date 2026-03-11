package spinloki.Intrigue.territory;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.subfaction.SubfactionSetup;

import java.io.Serializable;
import java.util.*;

/**
 * Per-territory Starsector adapter that bridges {@link TerritoryState} (pure logic)
 * with the game world (entity spawning, destruction detection, script lifecycle).
 *
 * <p>Implements {@link EveryFrameScript} for the daily tick and {@link Serializable}
 * for save/load. All decision logic lives in the embedded {@link TerritoryState};
 * this class only handles Starsector-specific side effects.</p>
 */
public class TerritoryManager implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 3L;
    private static final Logger log = Global.getLogger(TerritoryManager.class);

    /** Persistent data key for the list of all TerritoryManagers. */
    public static final String PERSISTENT_KEY = "intrigue_territory_managers";

    /** The pure-logic state for this territory. All decision logic lives here. */
    private final TerritoryState state;

    /** Accumulated fractional days since last day-tick processing. */
    private float dayAccumulator = 0f;

    /** Active patrol intels keyed by op ID. */
    private final Map<Long, TerritoryPatrolIntel> activeIntels = new LinkedHashMap<>();

    /** Tracks whether we've logged the initial diagnostic message. */
    private transient boolean loggedInit = false;

    public TerritoryManager(TerritoryState state) {
        this.state = state;
    }

    // ── Delegating accessors ─────────────────────────────────────────────

    public TerritoryState getState() {
        return state;
    }

    public String getTerritoryId() {
        return state.getTerritoryId();
    }

    public List<String> getSystemIds() {
        return state.getSystemIds();
    }

    public Map<String, SubfactionPresence> getPresences() {
        return state.getPresences();
    }

    public SubfactionPresence getPresence(String subfactionId) {
        return state.getPresence(subfactionId);
    }

    public List<BaseSlot> getBaseSlots() {
        return state.getBaseSlots();
    }

    public void setBaseSlots(List<BaseSlot> slots) {
        state.setBaseSlots(slots);
    }

    public Map<Long, TerritoryPatrolIntel> getActiveIntels() {
        return Collections.unmodifiableMap(activeIntels);
    }

    // ── EveryFrameScript ─────────────────────────────────────────────────

    @Override
    public void advance(float amount) {
        // One-time diagnostic log after load/creation
        if (!loggedInit) {
            log.info("TerritoryManager active: " + state.getTerritoryId() +
                    " with " + state.getPresences().size() + " subfactions, " +
                    state.getBaseSlots().size() + " base slots, " +
                    state.getSystemIds().size() + " systems");
            loggedInit = true;
        }

        // Convert frame time to days and accumulate
        float days = Misc.getDays(amount);
        dayAccumulator += days;

        // Process once per day
        if (dayAccumulator < 1f) return;
        dayAccumulator -= 1f;

        // Check for destroyed bases (Starsector-specific: entity/market lookup)
        checkForDestroyedBases();

        // Advance state and execute any resulting side effects
        Map<String, SubfactionDef> defMap = loadSubfactionDefMap();
        if (defMap == null) return;

        List<TerritoryState.TickResult> results = state.advanceDay(defMap);
        for (TerritoryState.TickResult result : results) {
            switch (result.type) {
                case ESTABLISH_BASE:
                    executeEstablishment(result, defMap);
                    break;
                case OP_LAUNCHED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                            result.subfactionId + " launched " + result.op.getType() +
                            " → " + result.op.getTargetSystemId());
                    executeOpLaunched(result, defMap);
                    break;
                case OP_RESOLVED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                            result.subfactionId + " " + result.op.getType() + " " +
                            result.op.getOutcome());
                    cleanupResolvedOp(result);
                    // TODO (3b): feed outcome into boons/problems
                    break;
            }
        }
    }

    /**
     * Execute the game-world side effect of establishing a base.
     */
    private void executeEstablishment(TerritoryState.TickResult result,
                                      Map<String, SubfactionDef> defMap) {
        SubfactionDef def = defMap.get(result.subfactionId);
        if (def == null) return;

        SectorEntityToken station = BaseSpawner.spawnBase(result.slotToEstablish, def);
        if (station == null) {
            log.error("TerritoryManager [" + state.getTerritoryId() + "]: base spawning failed for " +
                    def.name + " at " + result.slotToEstablish.getLabel());
            return;
        }

        state.confirmEstablishment(result.subfactionId, result.slotToEstablish);
        result.slotToEstablish.setStationEntityId(station.getId());

        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " + def.name +
                " SCOUTING → ESTABLISHED at " + result.slotToEstablish.getLabel());
    }

    /**
     * Spawn a TerritoryPatrolIntel for a newly launched patrol op.
     */
    private void executeOpLaunched(TerritoryState.TickResult result,
                                    Map<String, SubfactionDef> defMap) {
        SubfactionDef def = defMap.get(result.subfactionId);
        if (def == null) return;

        BaseSlot slot = findOccupiedSlot(result.subfactionId);
        if (slot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no occupied slot for " +
                    result.subfactionId + ", skipping patrol intel");
            return;
        }

        TerritoryPatrolIntel intel = new TerritoryPatrolIntel(
                result.op, def, slot, state.getTerritoryId());
        activeIntels.put(result.op.getOpId(), intel);
    }

    /**
     * Clean up a resolved op's intel. If the fleet was destroyed in-game,
     * override the probabilistic outcome to FAILURE.
     */
    private void cleanupResolvedOp(TerritoryState.TickResult result) {
        TerritoryPatrolIntel intel = activeIntels.remove(result.op.getOpId());
        if (intel == null) return;

        // Player override: if the fleet was physically destroyed, force FAILURE
        if (intel.wasFleetDestroyed() &&
                result.op.getOutcome() != ActiveOp.OpOutcome.FAILURE) {
            state.overrideOpOutcome(result.op.getOpId(), ActiveOp.OpOutcome.FAILURE);
            log.info("TerritoryManager [" + state.getTerritoryId() + "]: fleet destroyed override → FAILURE");
        }
    }

    /**
     * Find the first occupied base slot for a given subfaction in this territory.
     */
    private BaseSlot findOccupiedSlot(String subfactionId) {
        for (BaseSlot slot : state.getBaseSlots()) {
            if (slot.isOccupied() && subfactionId.equals(slot.getOccupiedBySubfactionId())) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Check all occupied base slots for destroyed stations/markets.
     * Starsector-specific: requires entity and economy lookups.
     */
    private void checkForDestroyedBases() {
        for (BaseSlot slot : state.getBaseSlots()) {
            if (!slot.isOccupied() || slot.getStationEntityId() == null) continue;

            boolean destroyed = false;
            SectorEntityToken station = findStationEntity(slot);
            if (station == null) {
                destroyed = true;
            } else {
                MarketAPI market = station.getMarket();
                if (market == null || Global.getSector().getEconomy().getMarket(market.getId()) == null) {
                    destroyed = true;
                }
            }

            if (!destroyed) continue;

            String subfactionId = slot.getOccupiedBySubfactionId();
            log.info("TerritoryManager [" + state.getTerritoryId() + "]: base destroyed for " +
                    subfactionId + " at " + slot.getLabel() + " — releasing slot, reverting to SCOUTING");
            state.handleBaseDestroyed(subfactionId, slot);
        }
    }

    /**
     * Find the station entity for a base slot by searching its star system.
     */
    private SectorEntityToken findStationEntity(BaseSlot slot) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (system.getId().equals(slot.getSystemId())) {
                return system.getEntityById(slot.getStationEntityId());
            }
        }
        return null;
    }

    /**
     * Load subfaction defs from persistent data into a lookup map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, SubfactionDef> loadSubfactionDefMap() {
        List<SubfactionDef> defs = (List<SubfactionDef>)
                Global.getSector().getPersistentData().get(SubfactionSetup.PERSISTENT_KEY);
        if (defs == null) {
            log.error("TerritoryManager: no subfaction defs in persistent data");
            return null;
        }
        Map<String, SubfactionDef> map = new LinkedHashMap<>();
        for (SubfactionDef d : defs) {
            map.put(d.id, d);
        }
        return map;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    // ── Factory ──────────────────────────────────────────────────────────

    /**
     * Create TerritoryManagers for all territories by delegating distribution
     * to {@link TerritoryState#distributeSubfactions}, then wrapping each state
     * in a manager.
     */
    public static List<TerritoryManager> createForAllTerritories(
            Map<String, List<String>> territories,
            List<SubfactionDef> allDefs) {

        Map<String, TerritoryState> states = TerritoryState.distributeSubfactions(
                territories, allDefs, new Random());

        List<TerritoryManager> managers = new ArrayList<>();
        for (Map.Entry<String, TerritoryState> entry : states.entrySet()) {
            TerritoryState ts = entry.getValue();
            TerritoryManager mgr = new TerritoryManager(ts);
            managers.add(mgr);

            for (SubfactionPresence p : ts.getPresences().values()) {
                log.info("  Territory '" + ts.getTerritoryId() + "': " +
                        p.getSubfactionId() + " → " + p.getState());
            }
        }

        return managers;
    }

    // ── Static lookup ────────────────────────────────────────────────────

    /**
     * Retrieve all TerritoryManagers from persistent data.
     * Returns an empty list if none are stored yet.
     */
    @SuppressWarnings("unchecked")
    public static List<TerritoryManager> getManagers() {
        Object stored = Global.getSector().getPersistentData().get(PERSISTENT_KEY);
        if (stored instanceof List) {
            return (List<TerritoryManager>) stored;
        }
        return Collections.emptyList();
    }
}
