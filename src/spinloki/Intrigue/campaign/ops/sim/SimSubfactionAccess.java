package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.spi.IntrigueSubfactionAccess;

import java.util.*;

/**
 * Sim-side subfaction registry. No Starsector API dependency.
 */
public class SimSubfactionAccess implements IntrigueSubfactionAccess {

    private final Map<String, IntrigueSubfaction> subfactions = new LinkedHashMap<>();

    public void addSubfaction(IntrigueSubfaction subfaction) {
        subfactions.put(subfaction.getSubfactionId(), subfaction);
    }

    @Override
    public IntrigueSubfaction getById(String subfactionId) {
        return subfactions.get(subfactionId);
    }

    @Override
    public Collection<IntrigueSubfaction> getAll() {
        return Collections.unmodifiableCollection(subfactions.values());
    }

    @Override
    public Collection<IntrigueSubfaction> getByFaction(String factionId) {
        if (factionId == null) return Collections.emptyList();
        List<IntrigueSubfaction> result = new ArrayList<>();
        for (IntrigueSubfaction sf : subfactions.values()) {
            if (factionId.equals(sf.getFactionId())) {
                result.add(sf);
            }
        }
        return result;
    }

    @Override
    public void setRelationship(String aId, String bId, int value) {
        if (aId == null || bId == null || aId.equals(bId)) return;
        IntrigueSubfaction a = subfactions.get(aId);
        IntrigueSubfaction b = subfactions.get(bId);
        if (a == null || b == null) return;
        int v = Math.max(-100, Math.min(100, value));
        a.setRelToInternal(bId, v);
        b.setRelToInternal(aId, v);
    }

    @Override
    public IntrigueSubfaction getSubfactionOf(String personId) {
        if (personId == null) return null;
        for (IntrigueSubfaction sf : subfactions.values()) {
            if (sf.containsPerson(personId)) return sf;
        }
        return null;
    }
}
