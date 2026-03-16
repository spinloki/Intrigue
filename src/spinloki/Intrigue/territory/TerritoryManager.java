package spinloki.Intrigue.territory;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
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
                    applyOpOutcome(result);
                    break;
                case PRESENCE_PROMOTED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                            result.subfactionId + " PROMOTED " + result.oldState + " → " + result.newState);
                    break;
                case PRESENCE_DEMOTED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                            result.subfactionId + " DEMOTED " + result.oldState + " → " + result.newState);
                    break;
                case ENTANGLEMENT_CREATED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT CREATED: "
                            + result.entanglement);
                    entanglementsDirty = true;
                    break;
                case ENTANGLEMENT_EXPIRED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT EXPIRED: "
                            + result.entanglement);
                    entanglementsDirty = true;
                    break;
                case ENTANGLEMENT_REPLACED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: ENTANGLEMENT REPLACED: "
                            + result.replacedEntanglement + " → " + result.entanglement);
                    entanglementsDirty = true;
                    break;
                case HOSTILITIES_STARTED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: HOSTILITIES STARTED: "
                            + result.parentPair);
                    entanglementsDirty = true;
                    break;
                case HOSTILITIES_ENDED:
                    log.info("TerritoryManager [" + state.getTerritoryId() + "]: HOSTILITIES ENDED: "
                            + result.parentPair);
                    entanglementsDirty = true;
                    break;
            }
        }

        // Sync hostility to FactionAPI if entanglements changed this tick
        if (entanglementsDirty) {
            syncHostilityToFactionAPI(defMap);
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
     * Spawn the appropriate intel for a newly launched operation.
     * Dispatches to the correct intel subclass based on op type.
     */
    private void executeOpLaunched(TerritoryState.TickResult result,
                                    Map<String, SubfactionDef> defMap) {
        SubfactionDef def = defMap.get(result.subfactionId);
        if (def == null) return;

        TerritoryOpIntel intel = null;
        switch (result.op.getType()) {
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
            activeIntels.put(result.op.getOpId(), intel);
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
    private TerritoryOpIntel createSimpleSlotIntel(TerritoryState.TickResult result,
                                                    SubfactionDef def,
                                                    SlotIntelFactory factory) {
        BaseSlot slot = findOccupiedSlot(result.subfactionId);
        if (slot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no occupied slot for " +
                    result.subfactionId + ", skipping intel");
            return null;
        }
        return factory.create(result.op, def, slot);
    }

    private TerritoryRaidIntel createRaidIntel(TerritoryState.TickResult result,
                                                Map<String, SubfactionDef> defMap,
                                                SubfactionDef raiderDef) {
        BaseSlot raiderSlot = findOccupiedSlot(result.subfactionId);
        if (raiderSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for raider " +
                    result.subfactionId);
            return null;
        }

        String targetId = result.op.getTargetSubfactionId();
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
                result.op, raiderDef, raiderSlot, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryProtectionPatrolIntel createProtectionPatrolIntel(
            TerritoryState.TickResult result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef protectorDef) {
        BaseSlot protectorSlot = findOccupiedSlot(result.subfactionId);
        if (protectorSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for protector " +
                    result.subfactionId);
            return null;
        }

        String hirerId = result.op.getTargetSubfactionId();
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
                result.op, protectorDef, protectorSlot, hirerSlot, hirerName, state.getTerritoryId());
    }

    private TerritoryJointStrikeIntel createJointStrikeIntel(
            TerritoryState.TickResult result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef strikerDef) {
        BaseSlot strikerSlot = findOccupiedSlot(result.subfactionId);
        if (strikerSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for striker " +
                    result.subfactionId);
            return null;
        }

        String targetId = result.op.getTargetSubfactionId();
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
                result.op, strikerDef, strikerSlot, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryWarRaidIntel createWarRaidIntel(
            TerritoryState.TickResult result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef raiderDef) {
        BaseSlot raiderSlot = findOccupiedSlot(result.subfactionId);
        if (raiderSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for war raider " +
                    result.subfactionId);
            return null;
        }

        String targetId = result.op.getTargetSubfactionId();
        SubfactionDef targetDef = defMap.get(targetId);
        String targetName = targetDef != null ? targetDef.name : (targetId != null ? targetId : "unknown");

        return new TerritoryWarRaidIntel(
                result.op, raiderDef, raiderSlot, targetId, targetName, state.getTerritoryId());
    }

    private TerritoryArmsShipmentIntel createArmsShipmentIntel(
            TerritoryState.TickResult result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef backerDef) {
        BaseSlot backerSlot = findOccupiedSlot(result.subfactionId);
        if (backerSlot == null) {
            log.warn("TerritoryManager [" + state.getTerritoryId() + "]: no slot for backer " +
                    result.subfactionId);
            return null;
        }

        String backedId = result.op.getTargetSubfactionId();
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
                result.op, backerDef, backerSlot, backedDef, backedSlot, state.getTerritoryId());
    }

    private TerritoryInvasionIntel createInvasionIntel(
            TerritoryState.TickResult result,
            Map<String, SubfactionDef> defMap,
            SubfactionDef invaderDef) {
        // Invader is external — they have no slot in this territory.
        // We only need the target's slot.
        String targetId = result.op.getTargetSubfactionId();
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
                result.op, invaderDef, targetSlot, targetDef, state.getTerritoryId());
    }

    private TerritoryCouncilIntel createCouncilIntel(
            TerritoryState.TickResult result,
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
                result.op, triggerDef, participants, meetingSystem,
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
    private void cleanupResolvedOp(TerritoryState.TickResult result) {
        TerritoryOpIntel intel = activeIntels.remove(result.op.getOpId());
        if (intel == null) return;

        // Player override: if the fleet was physically destroyed, force FAILURE
        if (intel.wasFleetDestroyed() &&
                result.op.getOutcome() != ActiveOp.OpOutcome.FAILURE) {
            state.overrideOpOutcome(result.op.getOpId(), ActiveOp.OpOutcome.FAILURE);
            log.info("TerritoryManager [" + state.getTerritoryId() + "]: fleet destroyed override → FAILURE");
        }
    }

    /**
     * Apply promotion/demotion from a resolved transition op.
     * Mirrors SimulationHarness.handleResolvedOp().
     */
    private void applyOpOutcome(TerritoryState.TickResult result) {
        ActiveOp op = result.op;
        switch (op.getType()) {
            case RAID: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS && op.getTargetSubfactionId() != null) {
                    TerritoryState.TickResult demotion = state.applyDemotion(op.getTargetSubfactionId());
                    if (demotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getTargetSubfactionId() + " DEMOTED (raided) " +
                                demotion.oldState + " → " + demotion.newState);
                    }
                }
                break;
            }
            case EVACUATION: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    TerritoryState.TickResult demotion = state.applyDemotion(op.getSubfactionId());
                    if (demotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getSubfactionId() + " DEMOTED (evacuation) " +
                                demotion.oldState + " → " + demotion.newState);
                    }
                }
                break;
            }
            case EXPANSION:
            case SUPREMACY: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    TerritoryState.TickResult promotion = state.applyPromotion(op.getSubfactionId());
                    if (promotion != null) {
                        log.info("TerritoryManager [" + state.getTerritoryId() + "]: " +
                                op.getSubfactionId() + " PROMOTED " +
                                promotion.oldState + " → " + promotion.newState);
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
    private void syncHostilityToFactionAPI(Map<String, SubfactionDef> defMap) {
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
                float rel = hostile ? -0.75f : 0.0f;
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
