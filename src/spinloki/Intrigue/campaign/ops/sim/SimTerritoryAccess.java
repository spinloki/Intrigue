package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.*;

/**
 * Sim-side territory registry. No Starsector API dependency.
 */
public class SimTerritoryAccess implements IntrigueTerritoryAccess {

    private final Map<String, IntrigueTerritory> territories = new LinkedHashMap<>();
    private int decayPerTick = 1;

    public void addTerritory(IntrigueTerritory territory) {
        territories.put(territory.getTerritoryId(), territory);
    }

    public void setDecayPerTick(int decayPerTick) {
        this.decayPerTick = decayPerTick;
    }

    @Override
    public IntrigueTerritory getById(String territoryId) {
        return territories.get(territoryId);
    }

    @Override
    public Collection<IntrigueTerritory> getAll() {
        return Collections.unmodifiableCollection(territories.values());
    }

    @Override
    public IntrigueTerritory getTerritoryContaining(String constellationName) {
        if (constellationName == null) return null;
        for (IntrigueTerritory t : territories.values()) {
            if (t.getConstellationNames().contains(constellationName)) return t;
        }
        return null;
    }

    @Override
    public Collection<IntrigueTerritory> getTerritoriesForSubfaction(String subfactionId) {
        if (subfactionId == null) return Collections.emptyList();
        List<IntrigueTerritory> result = new ArrayList<>();
        for (IntrigueTerritory t : territories.values()) {
            if (t.hasPresence(subfactionId)) result.add(t);
        }
        return result;
    }

    @Override
    public int getDecayPerTick() {
        return decayPerTick;
    }
}

