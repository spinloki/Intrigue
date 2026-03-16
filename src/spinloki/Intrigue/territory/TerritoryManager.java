package spinloki.Intrigue.territory;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import spinloki.Intrigue.config.IntrigueSettings;
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

    /** Active operation intels keyed by op ID. */
    private final Map<Long, TerritoryOpIntel> activeIntels = new LinkedHashMap<>();

    /** Tracks whether we've logged the initial diagnostic message. */
    private transient boolean loggedInit = false;

    /** Cached settings loaded from intrigue_settings.json. Transient — reloaded after save/load. */
    private transient IntrigueSettings cachedSettings = null;

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

    public Map<Long, TerritoryOpIntel> getActiveIntels() {
        return Collections.unmodifiableMap(activeIntels);
    }

    /**
     * Create an op, register it in state, and immediately execute the game-world side effect
     * (spawn intel/fleet). Used by console commands to test ops in-game.
     *
     * @return The OpLaunched result, or null if the op couldn't be created.
     */
    public TerritoryState.TickResult.OpLaunched createAndLaunchOp(
            ActiveOp.OpType opType, String subfactionId,
            String originSystem, String targetSystem,
            String targetSubfactionId) {
        ActiveOp op = new ActiveOp(state.nextOpId(), opType, subfactionId,
                originSystem, targetSystem, 30f, 0.75f);
        if (targetSubfactionId != null) {
            op.setTargetSubfactionId(targetSubfactionId);
        }
        state.activeOpsMutable().add(op);

        TerritoryState.TickResult.OpLaunched launched =
                new TerritoryState.TickResult.OpLaunched(subfactionId, op);

        Map<String, SubfactionDef> defMap = loadSubfactionDefMap();
        if (defMap == null) return null;

        executeOpLaunched(launched, defMap);
        return launched;
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

        // Inject Starsector-side conditional factors before advancing state
        injectConditionalFactors();

        // Advance state and execute any resulting side effects
        Map<String, SubfactionDef> defMap = loadSubfactionDefMap();
        if (defMap == null) return;

        IntrigueSettings settings = getSettings();
        List<TerritoryState.TickResult> results = state.advanceDay(defMap, settings);
        boolean entanglementsDirty = false;
        for (TerritoryState.TickResult result : results) {
            if (handleTickResult(result, defMap)) {
                entanglementsDirty = true;
            }
        }

        // Sync hostility to FactionAPI only if the player is in this territory's systems.
        // FactionAPI.setRelationship() is sector-wide, so only the territory the player
        // is actually in should project its political state onto faction relations.
        if (entanglementsDirty && isPlayerInTerritory()) {
            syncHostilityToFactionAPI(defMap, settings);
        }
    }

    /**
     * Handle a single tick result. Returns true if the result changes entanglement/hostility state.
     */
    private boolean handleTickResult(TerritoryState.TickResult result,
                                     Map<String, SubfactionDef> defMap) {
        switch (result.type()) {
            case ESTABLISH_BASE: {
                var r = (TerritoryState.TickResult.EstablishBase) result;
                executeEstablishment(r, defMap);
                return false;
            }
            case OP_LAUNCHED: {
                var r = (TerritoryState.TickResult.OpLaunched) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                        r.subfactionId() + " launched " + r.op().getType() +
                        " → " + r.op().getTargetSystemId());
                executeOpLaunched(r, defMap);
                return false;
            }
            case OP_RESOLVED: {
                var r = (TerritoryState.TickResult.OpResolved) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                        r.subfactionId() + " " + r.op().getType() + " " +
                        r.op().getOutcome());
                cleanupResolvedOp(r);
                applyOpOutcome(r);
                return false;
            }
            case PRESENCE_PROMOTED: {
                var r = (TerritoryState.TickResult.PresencePromoted) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                        r.subfactionId() + " PROMOTED " + r.oldState() + " → " + r.newState());
                executeStationTierChange(r.subfactionId(), r.newState());
                return false;
            }
            case PRESENCE_DEMOTED: {
                var r = (TerritoryState.TickResult.PresenceDemoted) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                        r.subfactionId() + " DEMOTED " + r.oldState() + " → " + r.newState());
                if (r.newState().canLaunchOps()) {
                    executeStationTierChange(r.subfactionId(), r.newState());
                }
                return false;
            }
            case ENTANGLEMENT_CREATED: {
                var r = (TerritoryState.TickResult.EntanglementCreated) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT CREATED: "
                        + r.entanglement());
                return true;
            }
            case ENTANGLEMENT_EXPIRED: {
                var r = (TerritoryState.TickResult.EntanglementExpired) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT EXPIRED: "
                        + r.entanglement());
                return true;
            }
            case ENTANGLEMENT_REPLACED: {
                var r = (TerritoryState.TickResult.EntanglementReplaced) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT REPLACED: "
                        + r.replaced() + " → " + r.newEntanglement());
                return true;
            }
            case HOSTILITIES_STARTED: {
                var r = (TerritoryState.TickResult.HostilitiesStarted) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: HOSTILITIES STARTED: "
                        + r.parentPair());
                return true;
            }
            case HOSTILITIES_ENDED: {
                var r = (TerritoryState.TickResult.HostilitiesEnded) result;
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: HOSTILITIES ENDED: "
                        + r.parentPair());
                return true;
            }
            case SUBFACTION_ARRIVED: {
                var r = (TerritoryState.TickResult.SubfactionArrived) result;
                SubfactionDef def = defMap.get(r.subfactionId());
                String name = def != null ? def.name : r.subfactionId();
                log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                        name + " ARRIVED (SCOUTING)");
                return false;
            }
            default:
                return false;
        }
    }

    /**
     * Execute the game-world side effect of establishing a base.
     */
    private void executeEstablishment(TerritoryState.TickResult.EstablishBase result,
                                      Map<String, SubfactionDef> defMap) {
        SubfactionDef def = defMap.get(result.subfactionId());
        if (def == null) return;

        SectorEntityToken station = BaseSpawner.spawnBase(result.slot(), def);
        if (station == null) {
            log.error("TerritoryManager [" + state.getTerritoryId() + "]: base spawning failed for " +
                    def.name + " at " + result.slot().getLabel());
            return;
        }

        state.confirmEstablishment(result.subfactionId(), result.slot());
        result.slot().setStationEntityId(station.getId());

        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " + def.name +
                " SCOUTING → ESTABLISHED at " + result.slot().getLabel());
    }

    /**
     * Upgrade or downgrade the station industry at a subfaction's base to match
     * its new presence state (FORTIFIED → Battlestation, DOMINANT → Star Fortress, etc.).
     */
    private void executeStationTierChange(String subfactionId, PresenceState newState) {
        BaseSlot slot = findOccupiedSlot(subfactionId);
        if (slot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for " +
                    subfactionId + ", cannot change station tier");
            return;
        }
        if (BaseSpawner.setStationTier(slot, newState)) {
            log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                    subfactionId + " station tier updated for " + newState);
        }
    }

    /**
     * Spawn the appropriate intel for a newly launched operation.
     * Dispatches to the correct intel subclass based on op type.
     */
    public void executeOpLaunched(TerritoryState.TickResult.OpLaunched result,
                                    Map<String, SubfactionDef> defMap) {
        SubfactionDef def = defMap.get(result.subfactionId());
        if (def == null) return;

        TerritoryOpIntel intel = null;
        switch (result.op().getType()) {
            case PATROL:
                intel = createSimpleSlotIntel(result, def,
                        (op, d, slot) -> new TerritoryPatrolIntel(op, d, slot, state.getTerritoryId()));
                break;
            case RAID:
                intel = createRaidIntel(result, defMap, def);
                break;
            case EXPANSION:
            case SUPREMACY:
                intel = createSimpleSlotIntel(result, def,
                        (op, d, slot) -> new TerritoryReinforcementIntel(op, d, slot, state.getTerritoryId()));
                break;
            case EVACUATION:
                intel = createSimpleSlotIntel(result, def,
                        (op, d, slot) -> new TerritoryEvacuationIntel(op, d, slot, state.getTerritoryId()));
                break;
            case PROTECTION_PATROL:
                intel = createProtectionPatrolIntel(result, defMap, def);
                break;
            case JOINT_STRIKE:
                intel = createJointStrikeIntel(result, defMap, def);
                break;
            case WAR_RAID:
                intel = createWarRaidIntel(result, defMap, def);
                break;
            case ARMS_SHIPMENT:
                intel = createArmsShipmentIntel(result, defMap, def);
                break;
            case INVASION:
                intel = createInvasionIntel(result, defMap, def);
                break;
            case COUNCIL:
                intel = createCouncilIntel(result, defMap, def);
                break;
        }

        if (intel != null) {
            activeIntels.put(result.op().getOpId(), intel);
        }
    }

    // ── Intel factory methods ────────────────────────────────────────────

    /**
     * Functional interface for creating single-slot Intel instances.
     */
    @FunctionalInterface
    private interface SlotIntelFactory {
        TerritoryOpIntel create(ActiveOp op, SubfactionDef def, BaseSlot slot);
    }

    /**
     * Create an Intel that only needs the launcher's own occupied slot.
     * Used by PATROL, EXPANSION, SUPREMACY, EVACUATION.
     */
    private TerritoryOpIntel createSimpleSlotIntel(TerritoryState.TickResult.OpLaunched result,
                                                    SubfactionDef def,
                                                    SlotIntelFactory factory) {
        BaseSlot slot = findOccupiedSlot(result.subfactionId());
        if (slot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no occupied slot for " +
                    result.subfactionId() + ", skipping intel");
            return null;
        }
        return factory.create(result.op(), def, slot);
    }

    private TerritoryRaidIntel createRaidIntel(TerritoryState.TickResult.OpLaunched result,
                                                Map<String, SubfactionDef> defMap,
                                                SubfactionDef raiderDef) {
        BaseSlot raiderSlot = findOccupiedSlot(result.subfactionId());
        if (raiderSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for raider " +
                    result.subfactionId());
            return null;
        }

        String targetId = result.op().getTargetSubfactionId();
        if (targetId == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: RAID op has no target subfaction");
            return null;
        }

        BaseSlot targetSlot = findOccupiedSlot(targetId);
        if (targetSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for raid target " + targetId);
            return null;
        }

        SubfactionDef targetDef = defMap.get(targetId);
        return new TerritoryRaidIntel(
                result.op(), raiderDef, raiderSlot, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryProtectionPatrolIntel createProtectionPatrolIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef protectorDef) {
        BaseSlot protectorSlot = findOccupiedSlot(result.subfactionId());
        if (protectorSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for protector " +
                    result.subfactionId());
            return null;
        }

        String hirerId = result.op().getTargetSubfactionId();
        if (hirerId == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: PROTECTION_PATROL has no hirer");
            return null;
        }

        BaseSlot hirerSlot = findOccupiedSlot(hirerId);
        if (hirerSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for hirer " + hirerId);
            return null;
        }

        SubfactionDef hirerDef = defMap.get(hirerId);
        String hirerName = hirerDef != null ? hirerDef.name : hirerId;

        return new TerritoryProtectionPatrolIntel(
                result.op(), protectorDef, protectorSlot, hirerSlot, hirerName, state.getTerritoryId());
    }

    private TerritoryJointStrikeIntel createJointStrikeIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef strikerDef) {
        BaseSlot strikerSlot = findOccupiedSlot(result.subfactionId());
        if (strikerSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for striker " +
                    result.subfactionId());
            return null;
        }

        String targetId = result.op().getTargetSubfactionId();
        if (targetId == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: JOINT_STRIKE has no target");
            return null;
        }

        BaseSlot targetSlot = findOccupiedSlot(targetId);
        if (targetSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for strike target " +
                    targetId);
            return null;
        }

        SubfactionDef targetDef = defMap.get(targetId);
        return new TerritoryJointStrikeIntel(
                result.op(), strikerDef, strikerSlot, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryWarRaidIntel createWarRaidIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef raiderDef) {
        BaseSlot raiderSlot = findOccupiedSlot(result.subfactionId());
        if (raiderSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for war raider " +
                    result.subfactionId());
            return null;
        }

        String targetId = result.op().getTargetSubfactionId();
        SubfactionDef targetDef = defMap.get(targetId);
        String targetName = targetDef != null ? targetDef.name : (targetId != null ? targetId : "unknown");

        return new TerritoryWarRaidIntel(
                result.op(), raiderDef, raiderSlot, targetId, targetName, state.getTerritoryId());
    }

    private TerritoryArmsShipmentIntel createArmsShipmentIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef backerDef) {
        BaseSlot backerSlot = findOccupiedSlot(result.subfactionId());
        if (backerSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for backer " +
                    result.subfactionId());
            return null;
        }

        String backedId = result.op().getTargetSubfactionId();
        if (backedId == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: ARMS_SHIPMENT has no backed id");
            return null;
        }

        BaseSlot backedSlot = findOccupiedSlot(backedId);
        if (backedSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for backed " + backedId);
            return null;
        }

        SubfactionDef backedDef = defMap.get(backedId);
        if (backedDef == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no def for backed " + backedId);
            return null;
        }

        return new TerritoryArmsShipmentIntel(
                result.op(), backerDef, backerSlot, backedDef, backedSlot, state.getTerritoryId());
    }

    private TerritoryInvasionIntel createInvasionIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef invaderDef) {
        // Invader is external — they have no slot in this territory.
        // We only need the target's slot.
        String targetId = result.op().getTargetSubfactionId();
        if (targetId == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: INVASION has no target");
            return null;
        }

        BaseSlot targetSlot = findOccupiedSlot(targetId);
        if (targetSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for invasion target " +
                    targetId);
            return null;
        }

        SubfactionDef targetDef = defMap.get(targetId);
        if (targetDef == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no def for invasion target " +
                    targetId);
            return null;
        }

        return new TerritoryInvasionIntel(
                result.op(), invaderDef, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryCouncilIntel createCouncilIntel(
            TerritoryState.TickResult.OpLaunched result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef triggerDef) {
        // Gather all ESTABLISHED+ subfactions as participants
        List<TerritoryCouncilIntel.Participant> participants = new ArrayList<>();
        for (SubfactionPresence presence : state.getPresences().values()) {
            if (!presence.getState().canLaunchOps()) continue;
            SubfactionDef pDef = defMap.get(presence.getSubfactionId());
            if (pDef == null) continue;
            BaseSlot pSlot = findOccupiedSlot(presence.getSubfactionId());
            if (pSlot == null) continue;
            participants.add(new TerritoryCouncilIntel.Participant(pDef, pSlot));
        }

        if (participants.size() < 2) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: not enough council participants");
            return null;
        }

        // Find a base-free system for the meeting
        String meetingSystem = findBaseFreeSystem();

        // Count hostile pairs for fleet sizing
        int hostilePairs = countHostilePairs(defMap);

        return new TerritoryCouncilIntel(
                result.op(), triggerDef, participants, meetingSystem,
                hostilePairs, state.getTerritoryId());
    }

    /**
     * Find a system in this territory where no subfaction has a base.
     * Falls back to the least-occupied system if all have bases.
     */
    private String findBaseFreeSystem() {
        Set<String> occupiedSystems = new HashSet<>();
        for (BaseSlot slot : state.getBaseSlots()) {
            if (slot.isOccupied()) {
                occupiedSystems.add(slot.getSystemId());
            }
        }

        // Prefer a completely base-free system
        for (String systemId : state.getSystemIds()) {
            if (!occupiedSystems.contains(systemId)) {
                return systemId;
            }
        }

        // Fallback: pick the system with the fewest bases
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        for (String systemId : state.getSystemIds()) {
            int count = 0;
            for (BaseSlot slot : state.getBaseSlots()) {
                if (slot.isOccupied() && slot.getSystemId().equals(systemId)) count++;
            }
            if (count < bestCount) {
                bestCount = count;
                best = systemId;
            }
        }
        return best != null ? best : (state.getSystemIds().isEmpty() ? null : state.getSystemIds().get(0));
    }

    /**
     * Count the number of hostile subfaction pairs in this territory.
     */
    private int countHostilePairs(Map<String, SubfactionDef> defMap) {
        List<String> ids = new ArrayList<>();
        for (SubfactionPresence p : state.getPresences().values()) {
            if (p.getState().canLaunchOps()) {
                ids.add(p.getSubfactionId());
            }
        }

        int count = 0;
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (state.isHostile(ids.get(i), ids.get(j), defMap)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clean up a resolved op's intel. If the fleet was destroyed in-game,
     * override the probabilistic outcome to FAILURE.
     */
    private void cleanupResolvedOp(TerritoryState.TickResult.OpResolved result) {
        TerritoryOpIntel intel = activeIntels.remove(result.op().getOpId());
        if (intel == null) return;

        // Player override: if the fleet was physically destroyed, force FAILURE
        if (intel.wasFleetDestroyed() &&
                result.op().getOutcome() != ActiveOp.OpOutcome.FAILURE) {
            state.overrideOpOutcome(result.op().getOpId(), ActiveOp.OpOutcome.FAILURE);
            log.info("TerritoryManager [" + state.getTerritoryId() + "]: fleet destroyed override → FAILURE");
        }
    }

    /**
     * Apply promotion/demotion from a resolved transition op.
     * Mirrors SimulationHarness.handleResolvedOp().
     */
    private void applyOpOutcome(TerritoryState.TickResult.OpResolved result) {
        ActiveOp op = result.op();
        switch (op.getType()) {
            case RAID: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS && op.getTargetSubfactionId() != null) {
                    TerritoryState.TickResult.PresenceDemoted demotion = state.applyDemotion(op.getTargetSubfactionId());
                    if (demotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getTargetSubfactionId() + " DEMOTED (raided) " +
                                demotion.oldState() + " → " + demotion.newState());
                    }
                }
                break;
            }
            case EVACUATION: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    TerritoryState.TickResult.PresenceDemoted demotion = state.applyDemotion(op.getSubfactionId());
                    if (demotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getSubfactionId() + " DEMOTED (evacuation) " +
                                demotion.oldState() + " → " + demotion.newState());
                    }
                }
                break;
            }
            case EXPANSION:
            case SUPREMACY: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    TerritoryState.TickResult.PresencePromoted promotion = state.applyPromotion(op.getSubfactionId());
                    if (promotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getSubfactionId() + " PROMOTED " +
                                promotion.oldState() + " → " + promotion.newState());
                    }
                }
                break;
            }
            case INVASION: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS && op.getTargetSubfactionId() != null) {
                    Map<String, SubfactionDef> defMap = loadSubfactionDefMap();
                    SubfactionDef invaderDef = defMap != null ? defMap.get(op.getSubfactionId()) : null;
                    List<TerritoryState.TickResult> invasionResults =
                            state.applyInvasion(op.getSubfactionId(), op.getTargetSubfactionId(), invaderDef);
                    String invName = invaderDef != null ? invaderDef.name : op.getSubfactionId();
                    String targetName = op.getTargetSubfactionId();
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                            invName + " INVADED and replaced " + targetName);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Push entanglement-derived hostility to FactionAPI for all subfaction pairs
     * in this territory. Because FactionAPI.setRelationship() is sector-wide,
     * this is called whenever entanglements change and reflects the current
     * territory's political state.
     *
     * <p>Hostile pairs get a strongly negative relationship; non-hostile pairs
     * that were baseline-hostile get a neutral relationship (entanglement suppresses
     * hostility). Pairs unaffected by entanglements are left at baseline.</p>
     */
    private void syncHostilityToFactionAPI(Map<String, SubfactionDef> defMap,
                                            IntrigueSettings settings) {
        List<String> subfactionIds = new ArrayList<>(state.getPresences().keySet());
        for (int i = 0; i < subfactionIds.size(); i++) {
            for (int j = i + 1; j < subfactionIds.size(); j++) {
                String a = subfactionIds.get(i);
                String b = subfactionIds.get(j);

                SubfactionDef defA = defMap.get(a);
                SubfactionDef defB = defMap.get(b);
                if (defA == null || defB == null) continue;

                FactionAPI factionA = Global.getSector().getFaction(a);
                FactionAPI factionB = Global.getSector().getFaction(b);
                if (factionA == null || factionB == null) continue;

                boolean hostile = state.isHostile(a, b, defMap);
                float rel = hostile ? settings.hostileRelationValue : settings.neutralRelationValue;
                factionA.setRelationship(b, rel);

                log.debug("TerritoryManager [" + state.getTerritoryId() + "]: hostility sync " +
                        a + " ↔ " + b + " → " + (hostile ? "HOSTILE" : "NEUTRAL") +
                        " (rel=" + rel + ")");
            }
        }
    }

    /**
     * Inject Starsector-side conditional factors that can't be evaluated in pure Java.
     * Currently: SECURE_COMMS based on comm relay ownership in the subfaction's base system.
     */
    private void injectConditionalFactors() {
        IntrigueSettings settings = getSettings();
        Map<String, SubfactionDef> defMap = loadSubfactionDefMap();
        if (defMap == null) return;

        for (SubfactionPresence presence : state.getPresences().values()) {
            if (!presence.getState().canLaunchOps()) continue;

            String subfactionId = presence.getSubfactionId();

            // ── SECURE_COMMS: does this subfaction's parent faction control a comm relay in their base system? ──
            // Remove the pure-Java placeholder — we're taking ownership of this factor
            presence.removeFactorsByType(PresenceFactor.FactorType.SECURE_COMMS);

            BaseSlot slot = findOccupiedSlot(subfactionId);
            if (slot == null) continue;

            SubfactionDef def = defMap.get(subfactionId);
            if (def == null) continue;

            boolean controlsRelay = checkCommRelayControl(slot.getSystemId(), def.parentFactionId);
            if (controlsRelay) {
                IntrigueSettings.FactorDef commsDef =
                        settings.getFactorDef(PresenceFactor.FactorType.SECURE_COMMS);
                if (commsDef != null) {
                    int w = commsDef.getWeight(presence.getState());
                    if (w > 0) {
                        presence.addFactor(new PresenceFactor(
                                PresenceFactor.FactorType.SECURE_COMMS,
                                commsDef.polarity, w, null));
                    }
                }
            }
        }
    }

    /**
     * Check if a faction controls a comm relay in the given star system.
     * "Controls" = the comm relay entity's faction matches the given faction ID.
     */
    private boolean checkCommRelayControl(String systemId, String factionId) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (!system.getId().equals(systemId)) continue;

            for (SectorEntityToken entity : system.getAllEntities()) {
                if (entity.getCustomEntityType() != null &&
                        entity.getCustomEntityType().equals(Entities.COMM_RELAY)) {
                    if (entity.getFaction() != null &&
                            factionId.equals(entity.getFaction().getId())) {
                        return true;
                    }
                }
            }
            break;
        }
        return false;
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
    public Map<String, SubfactionDef> loadSubfactionDefMap() {
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

    /**
     * Load and cache IntrigueSettings from the mod's config JSON.
     * Transient — reloaded after save/load on first access.
     */
    private IntrigueSettings getSettings() {
        if (cachedSettings != null) return cachedSettings;
        try {
            org.json.JSONObject json = Global.getSettings()
                    .loadJSON("data/config/intrigue_settings.json", "spinloki_intrigue");
            cachedSettings = IntrigueSettings.parse(json.toString());
            log.info("TerritoryManager: loaded intrigue_settings.json (threshold=" +
                    cachedSettings.transitionThreshold + ", evalInterval=" +
                    cachedSettings.evaluationIntervalDays + ")");
        } catch (Exception e) {
            log.error("TerritoryManager: failed to load intrigue_settings.json, using defaults", e);
            try {
                cachedSettings = IntrigueSettings.parse(
                        "{\"evaluationIntervalDays\":60,\"transitionThreshold\":3}");
            } catch (Exception e2) {
                throw new RuntimeException("Failed to create default settings", e2);
            }
        }
        return cachedSettings;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    /**
     * Check whether the player fleet is currently located in one of this territory's
     * star systems. Used to gate sector-wide side effects (e.g. hostility sync) so
     * only the territory the player is in projects its political state.
     */
    private boolean isPlayerInTerritory() {
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return false;
        LocationAPI loc = playerFleet.getContainingLocation();
        if (!(loc instanceof StarSystemAPI)) return false;
        String playerSystemId = ((StarSystemAPI) loc).getId();
        for (String systemId : state.getSystemIds()) {
            if (systemId.equals(playerSystemId)) {
                return true;
            }
        }
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
