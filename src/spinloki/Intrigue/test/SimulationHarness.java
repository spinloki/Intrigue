package spinloki.Intrigue.test;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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

        // Run simulation
        for (int day = 1; day <= days; day++) {
            for (TerritoryState state : states.values()) {
                List<TerritoryState.TickResult> results = state.advanceDay(defMap);
                String tid = state.getTerritoryId();
                opStats.putIfAbsent(tid, new int[3]);

                for (TerritoryState.TickResult result : results) {
                    SubfactionDef def = defMap.get(result.subfactionId);
                    String name = def != null ? def.name : result.subfactionId;

                    switch (result.type) {
                        case ESTABLISH_BASE:
                            state.confirmEstablishment(result.subfactionId, result.slotToEstablish);
                            result.slotToEstablish.setStationEntityId("sim_station_" + result.subfactionId);
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " SCOUTING → ESTABLISHED at " +
                                    result.slotToEstablish.getLabel());
                            break;
                        case OP_LAUNCHED:
                            opStats.get(tid)[0]++;
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " launched " + result.op.getType() +
                                    " → " + result.op.getTargetSystemId());
                            break;
                        case OP_RESOLVED:
                            if (result.op.getOutcome() == ActiveOp.OpOutcome.SUCCESS) {
                                opStats.get(tid)[1]++;
                            } else {
                                opStats.get(tid)[2]++;
                            }
                            System.out.println("[Day " + day + "] " + tid +
                                    ": " + name + " " + result.op.getType() + " " +
                                    result.op.getOutcome());
                            break;
                    }
                }
            }
        }

        // Print final state
        System.out.println();
        System.out.println("── Final State (Day " + days + ") ────────────────────────────");
        for (TerritoryState state : states.values()) {
            System.out.print(state);
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
}
