package spinloki.Intrigue.test;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import spinloki.Intrigue.config.IntrigueSettings;
import spinloki.Intrigue.subfaction.SubfactionDef;
import spinloki.Intrigue.territory.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Standalone test harness for validating territory conflict dynamics without
 * running Starsector. Reads subfaction and territory definitions from the
 * same JSON config files used by the game, then simulates daily ticks at
 * high speed using pure-Java {@link TerritoryState} objects.
 *
 * <p>Run via: {@code ./sim.sh [--days N] [--seed N]}</p>
 *
 * <p>No Starsector imports — compiles and runs with only the mod's pure-logic
 * classes plus org.json.</p>
 */
public class SimulationHarness {

    /** Number of extra synthetic "procgen" systems per territory. */
    private static final int PROCGEN_SYSTEMS_PER_TERRITORY = 2;

    // ── Main ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        int days = 100;
        long seed = 42L;

        // Parse optional CLI args
        for (int i = 0; i < args.length; i++) {
            if ("--days".equals(args[i]) && i + 1 < args.length) {
                days = Integer.parseInt(args[++i]);
            } else if ("--seed".equals(args[i]) && i + 1 < args.length) {
                seed = Long.parseLong(args[++i]);
            }
        }

        System.out.println("=== Intrigue Simulation Harness ===");
        System.out.println("Days: " + days + ", Seed: " + seed);
        System.out.println();

        // ── Load config files (single source of truth) ───────────────────

        Path projectDir = Paths.get(System.getProperty("project.dir", "."));

        String subfactionsJson = Files.readString(projectDir.resolve("data/config/subfactions.json"));
        List<SubfactionDef> subfactions = SubfactionDef.parseAll(new JSONObject(subfactionsJson));
        System.out.println("Loaded " + subfactions.size() + " subfactions from subfactions.json");

        String territoriesJson = Files.readString(projectDir.resolve("data/config/territories.json"));
        Map<String, List<String>> territories = parseTerritorySystemIds(new JSONObject(territoriesJson));
        System.out.println("Loaded " + territories.size() + " territories from territories.json");

        String settingsJson = Files.readString(projectDir.resolve("data/config/intrigue_settings.json"));
        IntrigueSettings settings = IntrigueSettings.parse(settingsJson);
        System.out.println("Loaded settings: eval interval=" + settings.evaluationIntervalDays +
                "d, threshold=" + settings.transitionThreshold);
        System.out.println();

        // ── Distribute subfactions ───────────────────────────────────────

        Map<String, TerritoryState> states = TerritoryState.distributeSubfactions(
                territories, subfactions, new Random(seed));

        // Generate base slots from territory JSON + synthetic procgen planets
        JSONArray terrArray = new JSONObject(territoriesJson).getJSONArray("territories");
        for (int i = 0; i < terrArray.length(); i++) {
            JSONObject terrJson = terrArray.getJSONObject(i);
            String tid = terrJson.getString("id");
            TerritoryState state = states.get(tid);
            if (state != null) {
                state.setBaseSlots(generateSlotsFromConfig(terrJson));
            }
        }

        // Build subfaction def lookup
        Map<String, SubfactionDef> defMap = new LinkedHashMap<>();
        for (SubfactionDef def : subfactions) {
            defMap.put(def.id, def);
        }

        // Print initial state
        System.out.println("── Initial State ──────────────────────────────────────");
        for (TerritoryState state : states.values()) {
            System.out.print(state);
        }
        System.out.println();

        // Op tracking for summary
        Map<String, int[]> opStats = new LinkedHashMap<>(); // territory → [launched, success, failure]
        // Transition tracking
        Map<String, int[]> transitionStats = new LinkedHashMap<>(); // territory → [promotions, demotions]
        // Op type breakdown: territory → {opType → [launched, success, failure]}
        Map<String, Map<String, int[]>> opTypeStats = new LinkedHashMap<>();
        // Desertion tracking: territory → [total, quelled, spiraled]
        Map<String, int[]> desertionStats = new LinkedHashMap<>();
        // Entanglement tracking: territory → [created, expired, replaced]
        Map<String, int[]> entanglementStats = new LinkedHashMap<>();
        // Entanglement type breakdown: territory → {type → count}
        Map<String, Map<String, Integer>> entanglementTypeStats = new LinkedHashMap<>();
        // Per-(territory, subfaction) tracking
        // Key: "territory|subfaction" → tracker
        Map<String, SubfactionTracker> trackers = new LinkedHashMap<>();

        // Initialize trackers
        for (TerritoryState state : states.values()) {
            for (SubfactionPresence p : state.getPresences().values()) {
                String key = state.getTerritoryId() + "|" + p.getSubfactionId();
                trackers.put(key, new SubfactionTracker(p.getState()));
            }
        }

        // Run simulation
        for (int day = 1; day <= days; day++) {
            for (TerritoryState state : states.values()) {
                List<TerritoryState.TickResult> results = state.advanceDay(defMap, settings);
                String tid = state.getTerritoryId();
                opStats.putIfAbsent(tid, new int[3]);
                transitionStats.putIfAbsent(tid, new int[2]);
                opTypeStats.putIfAbsent(tid, new LinkedHashMap<>());
                desertionStats.putIfAbsent(tid, new int[3]);

                for (TerritoryState.TickResult result : results) {
                    SubfactionDef def = defMap.get(result.subfactionId());
                    String name = def != null ? def.name : result.subfactionId();
                    String tkey = tid + "|" + result.subfactionId();

                    switch (result.type()) {
                        case ESTABLISH_BASE: {
                            var est = (TerritoryState.TickResult.EstablishBase) result;
                            state.confirmEstablishment(est.subfactionId(), est.slot());
                            est.slot().setStationEntityId("sim_station_" + est.subfactionId());
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " SCOUTING \u2192 ESTABLISHED at " +
                                    est.slot().getLabel());
                            break;
                        }
                        case OP_LAUNCHED: {
                            var launched = (TerritoryState.TickResult.OpLaunched) result;
                            opStats.get(tid)[0]++;
                            opTypeStats.get(tid).computeIfAbsent(
                                    launched.op().getType().name(), k -> new int[3])[0]++;
                            String extra = "";
                            if ((launched.op().getType() == ActiveOp.OpType.RAID
                                    || launched.op().getType() == ActiveOp.OpType.WAR_RAID
                                    || launched.op().getType() == ActiveOp.OpType.JOINT_STRIKE
                                    || launched.op().getType() == ActiveOp.OpType.INVASION)
                                    && launched.op().getTargetSubfactionId() != null) {
                                SubfactionDef targetDef = defMap.get(launched.op().getTargetSubfactionId());
                                extra = " (targeting " +
                                        (targetDef != null ? targetDef.name : launched.op().getTargetSubfactionId()) + ")";
                            } else if (launched.op().getType() == ActiveOp.OpType.ARMS_SHIPMENT
                                    && launched.op().getTargetSubfactionId() != null) {
                                SubfactionDef recipientDef = defMap.get(launched.op().getTargetSubfactionId());
                                extra = " (supplying " +
                                        (recipientDef != null ? recipientDef.name : launched.op().getTargetSubfactionId()) + ")";
                            }
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " launched " + launched.op().getType() +
                                    " → " + launched.op().getTargetSystemId() + extra);
                            break;
                        }
                        case OP_RESOLVED: {
                            var resolved = (TerritoryState.TickResult.OpResolved) result;
                            if (resolved.op().getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                                opStats.get(tid)[1]++;
                                opTypeStats.get(tid).computeIfAbsent(
                                        resolved.op().getType().name(), k -> new int[3])[1]++;
                            } else {
                                opStats.get(tid)[2]++;
                                opTypeStats.get(tid).computeIfAbsent(
                                        resolved.op().getType().name(), k -> new int[3])[2]++;
                            }
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " " + resolved.op().getType() + " " +
                                    resolved.op().getOutcome());

                            // Apply promotion/demotion from resolved transition ops
                            handleResolvedOp(state, resolved.op(), defMap, day, tid,
                                    transitionStats.get(tid));
                            break;
                        }
                        case PRESENCE_PROMOTED: {
                            var promoted = (TerritoryState.TickResult.PresencePromoted) result;
                            transitionStats.get(tid)[0]++;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": ★ " + name + " PROMOTED " + promoted.oldState() + " → " + promoted.newState());
                            break;
                        }
                        case PRESENCE_DEMOTED: {
                            var demoted = (TerritoryState.TickResult.PresenceDemoted) result;
                            transitionStats.get(tid)[1]++;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": ▼ " + name + " DEMOTED " + demoted.oldState() + " → " + demoted.newState());
                            break;
                        }
                        case ENTANGLEMENT_CREATED: {
                            var created = (TerritoryState.TickResult.EntanglementCreated) result;
                            entanglementStats.computeIfAbsent(tid, k -> new int[3])[0]++;
                            entanglementTypeStats.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                                    .merge(created.entanglement().getType().name(), 1, Integer::sum);
                            System.out.println("[Day " + day + "] " + tid +
                                    ": ⚡ ENTANGLEMENT CREATED: " + created.entanglement());
                            break;
                        }
                        case ENTANGLEMENT_EXPIRED: {
                            var expired = (TerritoryState.TickResult.EntanglementExpired) result;
                            entanglementStats.computeIfAbsent(tid, k -> new int[3])[1]++;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": ○ ENTANGLEMENT EXPIRED: " + expired.entanglement());
                            break;
                        }
                        case ENTANGLEMENT_REPLACED: {
                            var replaced = (TerritoryState.TickResult.EntanglementReplaced) result;
                            entanglementStats.computeIfAbsent(tid, k -> new int[3])[2]++;
                            entanglementTypeStats.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                                    .merge(replaced.newEntanglement().getType().name(), 1, Integer::sum);
                            System.out.println("[Day " + day + "] " + tid +
                                    ": ↔ ENTANGLEMENT REPLACED: " + replaced.replaced()
                                    + " → " + replaced.newEntanglement());
                            break;
                        }
                        case HOSTILITIES_STARTED: {
                            var started = (TerritoryState.TickResult.HostilitiesStarted) result;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": 🔥 HOSTILITIES STARTED: " + started.parentPair());
                            break;
                        }
                        case HOSTILITIES_ENDED: {
                            var ended = (TerritoryState.TickResult.HostilitiesEnded) result;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": 🕊 HOSTILITIES ENDED: " + ended.parentPair());
                            break;
                        }
                        case SUBFACTION_EVICTED:
                            SubfactionDef evictedDef = defMap.get(result.subfactionId());
                            String evictedName = evictedDef != null ? evictedDef.name : result.subfactionId();
                            System.out.println("[Day " + day + "] " + tid +
                                    ": \u2620 " + evictedName + " EVICTED from territory");
                            break;
                        case SUBFACTION_ARRIVED:
                            SubfactionDef arrivedDef = defMap.get(result.subfactionId());
                            String arrivedName = arrivedDef != null ? arrivedDef.name : result.subfactionId();
                            System.out.println("[Day " + day + "] " + tid +
                                    ": \u2708 " + arrivedName + " ARRIVED in territory (SCOUTING)");
                            break;
                    }
                }

                // Daily tracking: state time, balance peaks, desertion counts
                for (SubfactionPresence p : state.getPresences().values()) {
                    String tk = tid + "|" + p.getSubfactionId();
                    SubfactionTracker t = trackers.get(tk);
                    if (t == null) {
                        // New subfaction entered via invasion — create tracker
                        t = new SubfactionTracker(p.getState());
                        trackers.put(tk, t);
                    }
                    t.recordDay(p.getState(), p.getNetBalance());
                    for (PresenceFactor f : p.getFactors()) {
                        if (f.getType() == PresenceFactor.FactorType.DESERTION_QUELLED
                                && f.getDaysRemaining() == 44f) {
                            desertionStats.get(tid)[0]++;
                            desertionStats.get(tid)[1]++;
                        } else if (f.getType() == PresenceFactor.FactorType.DESERTION_SPIRALED
                                && f.getDaysRemaining() == 44f) {
                            desertionStats.get(tid)[0]++;
                            desertionStats.get(tid)[2]++;
                        }
                    }
                }
            }
        }

        // Print final state
        System.out.println();
        System.out.println("── Final State (Day " + days + ") ────────────────────────────");
        for (TerritoryState state : states.values()) {
            System.out.print(state);
            // Print factor ledgers
            for (SubfactionPresence p : state.getPresences().values()) {
                if (p.getFactors().isEmpty()) continue;
                System.out.println("    Factors for " + p.getSubfactionId() + ":");
                for (PresenceFactor f : p.getFactors()) {
                    System.out.println("      " + f);
                }
            }
            // Print active entanglements
            if (!state.getEntanglements().isEmpty()) {
                System.out.println("  Active Entanglements:");
                for (Map.Entry<SubfactionPair, ActiveEntanglement> e : state.getEntanglements().entrySet()) {
                    System.out.println("    " + e.getValue());
                }
            }
        }

        // Summary stats
        System.out.println("── Summary ────────────────────────────────────────────");
        for (TerritoryState state : states.values()) {
            Map<PresenceState, Integer> counts = new LinkedHashMap<>();
            for (PresenceState ps : PresenceState.values()) counts.put(ps, 0);
            for (SubfactionPresence p : state.getPresences().values()) {
                counts.put(p.getState(), counts.get(p.getState()) + 1);
            }
            long occupiedSlots = 0;
            for (BaseSlot slot : state.getBaseSlots()) {
                if (slot.isOccupied()) occupiedSlots++;
            }
            System.out.println(state.getTerritoryId() + ": " + counts +
                    ", slots: " + occupiedSlots + "/" + state.getBaseSlots().size());
        }
        System.out.println();
        System.out.println("── Operations ─────────────────────────────────────────");
        for (Map.Entry<String, int[]> entry : opStats.entrySet()) {
            int[] s = entry.getValue();
            int total = s[1] + s[2];
            float rate = total > 0 ? (float) s[1] / total * 100f : 0f;
            System.out.println(entry.getKey() + ": launched=" + s[0] +
                    ", success=" + s[1] + ", failure=" + s[2] +
                    String.format(" (%.0f%% success rate)", rate));
            // Op type breakdown
            Map<String, int[]> typeMap = opTypeStats.get(entry.getKey());
            if (typeMap != null) {
                for (Map.Entry<String, int[]> te : typeMap.entrySet()) {
                    int[] ts = te.getValue();
                    System.out.println("    " + te.getKey() + ": " + ts[0] + " launched, " +
                            ts[1] + " success, " + ts[2] + " failure");
                }
            }
        }
        System.out.println();
        System.out.println("── Transitions ────────────────────────────────────────");
        for (Map.Entry<String, int[]> entry : transitionStats.entrySet()) {
            int[] t = entry.getValue();
            System.out.println(entry.getKey() + ": promotions=" + t[0] + ", demotions=" + t[1]);
        }
        System.out.println();
        System.out.println("── Desertions ─────────────────────────────────────────");
        for (Map.Entry<String, int[]> entry : desertionStats.entrySet()) {
            int[] d = entry.getValue();
            System.out.println(entry.getKey() + ": resolved=" + d[0] +
                    ", quelled=" + d[1] + ", spiraled=" + d[2]);
        }
        System.out.println();
        System.out.println("── Entanglements ──────────────────────────────────────");
        for (Map.Entry<String, int[]> entry : entanglementStats.entrySet()) {
            int[] e = entry.getValue();
            System.out.println(entry.getKey() + ": created=" + e[0] +
                    ", expired=" + e[1] + ", replaced=" + e[2]);
            Map<String, Integer> typeMap = entanglementTypeStats.get(entry.getKey());
            if (typeMap != null) {
                for (Map.Entry<String, Integer> te : typeMap.entrySet()) {
                    System.out.println("    " + te.getKey() + ": " + te.getValue());
                }
            }
        }
        System.out.println();
        System.out.println("── Subfaction Details ─────────────────────────────────");
        for (TerritoryState state : states.values()) {
            System.out.println(state.getTerritoryId() + ":");
            for (SubfactionPresence p : state.getPresences().values()) {
                String tkey = state.getTerritoryId() + "|" + p.getSubfactionId();
                SubfactionTracker t = trackers.get(tkey);
                if (t == null) continue;
                SubfactionDef def = defMap.get(p.getSubfactionId());
                String name = def != null ? def.name : p.getSubfactionId();

                StringBuilder sb = new StringBuilder();
                sb.append("  ").append(name).append(": ");

                // Time in state
                sb.append("days{");
                boolean first = true;
                for (PresenceState ps : PresenceState.values()) {
                    int d = t.daysInState.getOrDefault(ps, 0);
                    if (d > 0) {
                        if (!first) sb.append(", ");
                        sb.append(ps.name().charAt(0)).append("=").append(d);
                        first = false;
                    }
                }
                sb.append("} ");

                // Longest dominant streak
                if (t.longestDominant > 0) {
                    sb.append("longestDOM=").append(t.longestDominant).append("d ");
                }

                // Peak balance
                sb.append("peak=[").append(t.minBalance).append(",+").append(t.maxBalance).append("] ");

                // State changes
                sb.append("changes=").append(t.stateChanges);

                System.out.println(sb);
            }
        }
    }

    /**
     * Handle a resolved transition op by applying the promotion or demotion.
     */
    private static void handleResolvedOp(TerritoryState state, ActiveOp op,
                                          Map<String, SubfactionDef> defMap,
                                          int day, String tid, int[] transitionStats) {
        switch (op.getType()) {
            case RAID: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS && op.getTargetSubfactionId() != null) {
                    TerritoryState.TickResult.PresenceDemoted demotion = state.applyDemotion(op.getTargetSubfactionId());
                    if (demotion != null) {
                        if (transitionStats != null) transitionStats[1]++;
                        SubfactionDef targetDef = defMap.get(op.getTargetSubfactionId());
                        String targetName = targetDef != null ? targetDef.name : op.getTargetSubfactionId();
                        if (demotion.newState() == PresenceState.NONE) {
                            System.out.println("[Day " + day + "] " + tid +
                                    ": \u2620 " + targetName + " EVICTED (raided out) " +
                                    demotion.oldState() + " \u2192 NONE");
                        } else {
                            System.out.println("[Day " + day + "] " + tid +
                                    ": \u25BC " + targetName + " DEMOTED (raided) " +
                                    demotion.oldState() + " \u2192 " + demotion.newState());
                        }
                    }
                }
                break;
            }
            case EVACUATION: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                    TerritoryState.TickResult.PresenceDemoted demotion = state.applyDemotion(op.getSubfactionId());
                    if (demotion != null) {
                        if (transitionStats != null) transitionStats[1]++;
                        SubfactionDef def = defMap.get(op.getSubfactionId());
                        String name = def != null ? def.name : op.getSubfactionId();
                        System.out.println("[Day " + day + "] " + tid +
                                ": ▼ " + name + " DEMOTED (evacuation) " +
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
                        if (transitionStats != null) transitionStats[0]++;
                        SubfactionDef def = defMap.get(op.getSubfactionId());
                        String name = def != null ? def.name : op.getSubfactionId();
                        System.out.println("[Day " + day + "] " + tid +
                                ": ★ " + name + " PROMOTED " +
                                promotion.oldState() + " → " + promotion.newState());
                    }
                }
                break;
            }            case INVASION: {
                if (op.getOutcome() == ActiveOp.OpOutcome.SUCCESS && op.getTargetSubfactionId() != null) {
                    SubfactionDef invaderDef = defMap.get(op.getSubfactionId());
                    List<TerritoryState.TickResult> invasionResults =
                            state.applyInvasion(op.getSubfactionId(), op.getTargetSubfactionId(), invaderDef);
                    SubfactionDef targetDef = defMap.get(op.getTargetSubfactionId());
                    String targetName = targetDef != null ? targetDef.name : op.getTargetSubfactionId();
                    String invName = invaderDef != null ? invaderDef.name : op.getSubfactionId();
                    System.out.println("[Day " + day + "] " + tid +
                            ": \u2694 " + invName + " INVADED and replaced " + targetName);
                    if (transitionStats != null) transitionStats[1]++;
                }
                break;
            }            default:
                break;
        }
    }

    // ── Config parsing helpers ────────────────────────────────────────────

    /**
     * Extract territory IDs → system IDs from territories.json.
     * Includes both hand-crafted system IDs from the config and synthetic
     * procgen system IDs (since procgen systems are determined at runtime).
     */
    private static Map<String, List<String>> parseTerritorySystemIds(JSONObject root) throws JSONException {
        JSONArray terrArray = root.getJSONArray("territories");
        Map<String, List<String>> territories = new LinkedHashMap<>();
        for (int i = 0; i < terrArray.length(); i++) {
            JSONObject t = terrArray.getJSONObject(i);
            String tid = t.getString("id");
            List<String> systemIds = new ArrayList<>();

            // Hand-crafted systems from config
            JSONArray systems = t.optJSONArray("systems");
            if (systems != null) {
                for (int j = 0; j < systems.length(); j++) {
                    systemIds.add(systems.getJSONObject(j).getString("id"));
                }
            }

            // Synthetic procgen systems (the game generates these at startup)
            for (int p = 1; p <= PROCGEN_SYSTEMS_PER_TERRITORY; p++) {
                systemIds.add(tid + "_procgen_" + p);
            }

            territories.put(tid, systemIds);
        }
        return territories;
    }

    /**
     * Generate base slots from a territory's JSON definition.
     * Creates PLANET_ORBIT slots for each planet and ASTEROID_BELT slots for
     * each belt defined in the config, plus synthetic slots for procgen systems.
     */
    private static List<BaseSlot> generateSlotsFromConfig(JSONObject terrJson) throws JSONException {
        String tid = terrJson.getString("id");
        List<BaseSlot> slots = new ArrayList<>();
        int slotIdx = 0;

        // Slots from hand-crafted systems
        JSONArray systems = terrJson.optJSONArray("systems");
        if (systems != null) {
            for (int i = 0; i < systems.length(); i++) {
                JSONObject sys = systems.getJSONObject(i);
                String sysId = sys.getString("id");

                JSONArray planets = sys.optJSONArray("planets");
                if (planets != null) {
                    for (int j = 0; j < planets.length(); j++) {
                        JSONObject planet = planets.getJSONObject(j);
                        String planetId = planet.getString("id");
                        String planetName = planet.getString("name");
                        float orbitRadius = (float) planet.getDouble("orbitRadius");
                        slots.add(new BaseSlot(
                                tid + "_slot_" + slotIdx++,
                                sysId, planetId, orbitRadius,
                                BaseSlotType.PLANET_ORBIT,
                                "orbit of " + planetName));
                    }
                }

                JSONArray belts = sys.optJSONArray("asteroidBelts");
                if (belts != null) {
                    for (int j = 0; j < belts.length(); j++) {
                        JSONObject belt = belts.getJSONObject(j);
                        float orbitRadius = (float) belt.getDouble("orbitRadius");
                        String beltName = belt.optString("name", sysId + " asteroid belt");
                        slots.add(new BaseSlot(
                                tid + "_slot_" + slotIdx++,
                                sysId, sysId + "_star", orbitRadius,
                                BaseSlotType.ASTEROID_BELT,
                                beltName));
                    }
                }
            }
        }

        // Synthetic slots for procgen systems
        for (int p = 1; p <= PROCGEN_SYSTEMS_PER_TERRITORY; p++) {
            String procSysId = tid + "_procgen_" + p;
            slots.add(new BaseSlot(
                    tid + "_slot_" + slotIdx++,
                    procSysId, procSysId + "_planet_1", 3000f,
                    BaseSlotType.PLANET_ORBIT,
                    "orbit of " + procSysId + " planet 1"));
            slots.add(new BaseSlot(
                    tid + "_slot_" + slotIdx++,
                    procSysId, procSysId + "_star", 1500f,
                    BaseSlotType.ASTEROID_BELT,
                    procSysId + " asteroid belt"));
        }

        return slots;
    }

    // ── Per-subfaction tracker ────────────────────────────────────────────

    private static class SubfactionTracker {
        final Map<PresenceState, Integer> daysInState = new LinkedHashMap<>();
        int longestDominant = 0;
        int currentDominantStreak = 0;
        int maxBalance = 0;
        int minBalance = 0;
        int stateChanges = 0;
        PresenceState lastState;

        SubfactionTracker(PresenceState initial) {
            this.lastState = initial;
        }

        void recordDay(PresenceState state, int netBalance) {
            daysInState.merge(state, 1, Integer::sum);

            if (state != lastState) {
                stateChanges++;
                lastState = state;
                currentDominantStreak = 0;
            }

            if (state == PresenceState.DOMINANT) {
                currentDominantStreak++;
                if (currentDominantStreak > longestDominant) {
                    longestDominant = currentDominantStreak;
                }
            }

            if (netBalance > maxBalance) maxBalance = netBalance;
            if (netBalance < minBalance) minBalance = netBalance;
        }
    }
}
