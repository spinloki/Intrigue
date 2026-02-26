package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.spi.IntriguePeopleAccess;

import java.util.*;

/**
 * Sim-side people registry. Holds IntriguePerson objects directly
 * with no Starsector API dependency. syncMemory is a no-op.
 */
public class SimPeopleAccess implements IntriguePeopleAccess {

    private final Map<String, IntriguePerson> people = new LinkedHashMap<>();

    public void addPerson(IntriguePerson person) {
        people.put(person.getPersonId(), person);
    }

    @Override
    public IntriguePerson getById(String personId) {
        return people.get(personId);
    }

    @Override
    public Collection<IntriguePerson> getAll() {
        return Collections.unmodifiableCollection(people.values());
    }

    @Override
    public void checkoutToMarket(String personId, String marketId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setAtMarket(marketId);
    }

    @Override
    public void checkoutToFleet(String personId, String fleetId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.setOnFleet(fleetId);
    }

    @Override
    public void returnHome(String personId) {
        IntriguePerson ip = people.get(personId);
        if (ip == null) return;
        ip.returnHome();
    }

    @Override
    public void syncMemory(String personId) {
        // No-op in sim mode — no PersonAPI memory to sync.
    }
}