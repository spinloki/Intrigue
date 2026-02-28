package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.ops.*;
import spinloki.Intrigue.campaign.spi.IntrigueServices;
import spinloki.Intrigue.campaign.spi.IntrigueTerritoryAccess;

import java.util.Random;

/**
 * Sim-side OpFactory that creates lightweight ops with no game dependencies.
 * Outcome determination is delegated to an {@link OpOutcomeResolver}, which
 * can be swapped out or configured via {@link SimConfig} probabilities.
 */
public class SimOpFactory implements OpFactory {

    private final Random rng;
    private final SimConfig config;
    private final OpOutcomeResolver resolver;

    public SimOpFactory(Random rng, SimConfig config) {
        this(rng, config, new DefaultOutcomeResolver(rng, config));
    }

    public SimOpFactory(Random rng, SimConfig config, OpOutcomeResolver resolver) {
        this.rng = rng;
        this.config = config;
        this.resolver = resolver;
    }

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        return new SimRaidOp(opId, attackerSubfaction, targetSubfaction, resolver, config);
    }

    @Override
    public IntrigueOp createEstablishBaseOp(String opId, IntrigueSubfaction subfaction) {
        return new SimEstablishBaseOp(opId, subfaction, resolver);
    }

    @Override
    public IntrigueOp createScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimScoutTerritoryOp(opId, subfaction, territoryId, resolver);
    }

    @Override
    public IntrigueOp createEstablishTerritoryBaseOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimEstablishTerritoryBaseOp(opId, subfaction, territoryId, resolver);
    }

    @Override
    public IntrigueOp createPatrolOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimPatrolOp(opId, subfaction, territoryId, resolver);
    }

    /**
     * A lightweight RaidOp that uses abstract combat resolution.
     */
    static class SimRaidOp extends IntrigueOp {

        private final int attackerCohesion;
        private final int defenderCohesion;
        private final boolean attackerMerciless;
        private final OpOutcomeResolver resolver;
        private final SimConfig config;
        private boolean attackerWon = false;

        SimRaidOp(String opId, IntrigueSubfaction attacker, IntrigueSubfaction target,
                  OpOutcomeResolver resolver, SimConfig config) {
            super(opId,
                  attacker.getLeaderId(),
                  target.getLeaderId(),
                  attacker.getSubfactionId(),
                  target.getSubfactionId());
            this.attackerCohesion = attacker.getCohesion();
            this.defenderCohesion = target.getCohesion();
            this.resolver = resolver;
            this.config = config;

            IntriguePerson leader = IntrigueServices.people().getById(attacker.getLeaderId());
            this.attackerMerciless = leader != null && leader.getTraits().contains(IntrigueTraits.MERCILESS);

            phases.add(new AssemblePhase(attackerCohesion));
            phases.add(new InstantPhase("Combat"));
            phases.add(new ReturnPhase(3f));
        }

        @Override
        public String getOpTypeName() {
            return "Raid";
        }

        @Override
        protected void onStarted() {
            IntrigueSubfaction target = getTargetSubfaction();
            if (target != null && getInitiatorId() != null) {
                IntrigueServices.people().checkoutToMarket(getInitiatorId(), target.getHomeMarketId());
            }
        }

        @Override
        protected OpOutcome determineOutcome() {
            OpOutcome result = resolver.resolveRaid(attackerCohesion, defenderCohesion, attackerMerciless);
            attackerWon = result == OpOutcome.SUCCESS;
            return result;
        }

        @Override
        protected void applyOutcome() {
            var people = IntrigueServices.people();
            var subfactions = IntrigueServices.subfactions();
            var attacker = getInitiatorSubfaction();
            var defender = getTargetSubfaction();

            // Return leader home
            if (getInitiatorId() != null) {
                people.returnHome(getInitiatorId());
            }

            // Set cooldown on attacking subfaction
            if (attacker != null) {
                attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }

            if (getOutcome() == OpOutcome.SUCCESS) {
                int cohesionGain = config.basePowerShift;
                int legitimacyShift = config.basePowerShift * 3 / 4;
                if (attackerMerciless) {
                    cohesionGain += config.mercilessBonusPower;
                    legitimacyShift += config.mercilessBonusPower / 2;
                }

                // Attacker gains home cohesion, some legitimacy (diminishing returns)
                if (attacker != null) {
                    float gainMult = 1f - (attacker.getHomeCohesion() / 150f);
                    int actualCoh = Math.max(1, Math.round(cohesionGain * Math.max(0.2f, gainMult)));
                    attacker.setHomeCohesion(attacker.getHomeCohesion() + actualCoh);
                    attacker.setLegitimacy(attacker.getLegitimacy() + legitimacyShift / 2);
                }
                // Defender loses legitimacy primarily, some home cohesion
                if (defender != null) {
                    float lossMult = defender.getLegitimacy() / 100f;
                    int actualLeg = Math.max(1, Math.round(legitimacyShift * Math.max(0.2f, lossMult)));
                    defender.setLegitimacy(defender.getLegitimacy() - actualLeg);
                    defender.setHomeCohesion(defender.getHomeCohesion() - cohesionGain / 2);
                }

                if (attacker != null && defender != null) {
                    int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
                    subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                            rel + config.relDropOnRaid);
                }
            } else {
                int cohesionLoss = config.basePowerShift / 2;
                int legitimacyGain = config.basePowerShift / 3;

                // Attacker loses home cohesion (failed operation)
                if (attacker != null) {
                    float lossMult = attacker.getHomeCohesion() / 100f;
                    int actual = Math.max(1, Math.round(cohesionLoss * Math.max(0.2f, lossMult)));
                    attacker.setHomeCohesion(attacker.getHomeCohesion() - actual);
                }
                // Defender gains legitimacy (repelled attack)
                if (defender != null) {
                    float gainMult = 1f - (defender.getLegitimacy() / 150f);
                    int actual = Math.max(1, Math.round(legitimacyGain * Math.max(0.2f, gainMult)));
                    defender.setLegitimacy(defender.getLegitimacy() + actual);
                }

                if (attacker != null && defender != null) {
                    int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
                    subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                            rel + config.relDropOnRaid / 2);
                }
            }

            if (getInitiatorId() != null) people.syncMemory(getInitiatorId());
            if (getTargetId() != null) people.syncMemory(getTargetId());
        }

        private int getSubfactionRelOrZero(IntrigueSubfaction sf, String otherId) {
            Integer rel = sf.getRelTo(otherId);
            return rel != null ? rel : 0;
        }
    }

    /**
     * Lightweight EstablishBaseOp for sim mode — auto-succeeds after a short delay,
     * assigns a synthetic market ID as the subfaction's home.
     */
    static class SimEstablishBaseOp extends IntrigueOp {

        private final String subfactionId;
        private final OpOutcomeResolver resolver;

        SimEstablishBaseOp(String opId, IntrigueSubfaction subfaction, OpOutcomeResolver resolver) {
            super(opId,
                  subfaction.getLeaderId(),
                  null,
                  subfaction.getSubfactionId(),
                  null);
            this.subfactionId = subfaction.getSubfactionId();
            this.resolver = resolver;

            phases.add(new SimScoutPhase());
        }

        @Override
        public String getOpTypeName() {
            return "Establish Base";
        }

        @Override
        protected void onStarted() {}

        @Override
        protected boolean shouldAbort() {
            if (getInitiator() == null) return true;
            IntrigueSubfaction sf = getInitiatorSubfaction();
            return sf != null && sf.hasHomeMarket();
        }

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolveEstablishBase(getInitiatorSubfaction());
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null && getOutcome() == OpOutcome.SUCCESS) {
                String fakeMarketId = "sim_base_" + subfactionId;
                sf.setHomeMarketId(fakeMarketId);
                sf.setHomeCohesion(sf.getHomeCohesion() + 10);
                sf.setLegitimacy(sf.getLegitimacy() + 5);
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }

        class SimScoutPhase implements OpPhase {
            private boolean done = false;

            @Override
            public void advance(float days) {
                // Completes instantly in sim
                done = true;
            }

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public String getStatus() {
                return done ? "Base established" : "Scouting";
            }
        }
    }

    /**
     * Sim-side ScoutTerritoryOp: sets presence to SCOUTING on start,
     * completes instantly.
     */
    static class SimScoutTerritoryOp extends IntrigueOp {

        private final String subfactionId;
        private final String tId;
        private final OpOutcomeResolver resolver;

        SimScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId, OpOutcomeResolver resolver) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Scouting territory"));
        }

        @Override public String getOpTypeName() { return "Scout Territory"; }

        @Override
        protected void onStarted() {
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory t = territories.getById(tId);
                if (t != null) t.setPresence(subfactionId, IntrigueTerritory.Presence.SCOUTING);
            }
        }

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolveScoutTerritory(getInitiatorSubfaction(), tId);
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
            if (getOutcome() == OpOutcome.SUCCESS) {
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 5);
                    sf.setLegitimacy(sf.getLegitimacy() + 3);
                }
            } else {
                // Revert presence to NONE on failure
                IntrigueTerritoryAccess territories = IntrigueServices.territories();
                if (territories != null) {
                    IntrigueTerritory t = territories.getById(tId);
                    if (t != null) t.setPresence(subfactionId, IntrigueTerritory.Presence.NONE);
                }
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 3);
                }
            }
        }
    }

    /**
     * Sim-side EstablishTerritoryBaseOp: sets presence to ESTABLISHED on success,
     * completes instantly.
     */
    static class SimEstablishTerritoryBaseOp extends IntrigueOp {

        private final String subfactionId;
        private final String tId;
        private final OpOutcomeResolver resolver;

        SimEstablishTerritoryBaseOp(String opId, IntrigueSubfaction subfaction, String territoryId, OpOutcomeResolver resolver) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Establishing base"));
        }

        @Override public String getOpTypeName() { return "Establish Territory Base"; }
        @Override protected void onStarted() {}

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolveEstablishTerritoryBase(getInitiatorSubfaction(), tId);
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
            if (getOutcome() == OpOutcome.SUCCESS) {
                IntrigueTerritoryAccess territories = IntrigueServices.territories();
                if (territories != null) {
                    IntrigueTerritory t = territories.getById(tId);
                    if (t != null) {
                        t.setPresence(subfactionId, IntrigueTerritory.Presence.ESTABLISHED);
                        t.setCohesion(subfactionId, 50);
                    }
                }
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 8);
                    sf.setLegitimacy(sf.getLegitimacy() + 5);
                }
            } else {
                // Revert presence to NONE on failure
                IntrigueTerritoryAccess territories = IntrigueServices.territories();
                if (territories != null) {
                    IntrigueTerritory t = territories.getById(tId);
                    if (t != null) t.setPresence(subfactionId, IntrigueTerritory.Presence.NONE);
                }
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 4);
                }
            }
        }
    }

    /**
     * Sim-side PatrolOp: completes instantly with a random success/failure outcome.
     * Success → legitimacy +3. Failure (destroyed) → legitimacy -4.
     */
    static class SimPatrolOp extends IntrigueOp {

        private final String subfactionId;
        private final String tId;
        private final OpOutcomeResolver resolver;

        SimPatrolOp(String opId, IntrigueSubfaction subfaction, String territoryId, OpOutcomeResolver resolver) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Patrolling"));
        }

        @Override public String getOpTypeName() { return "Patrol"; }
        @Override protected void onStarted() {}

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolvePatrol(getInitiatorSubfaction(), tId);
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                if (getOutcome() == OpOutcome.SUCCESS) {
                    sf.setLegitimacy(sf.getLegitimacy() + 3);
                } else {
                    sf.setLegitimacy(sf.getLegitimacy() - 4);
                }
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /** An OpPhase that completes instantly. */
    static class InstantPhase implements OpPhase {
        private final String label;
        private boolean done = false;

        InstantPhase(String label) { this.label = label; }

        @Override public void advance(float days) { done = true; }
        @Override public boolean isDone() { return done; }
        @Override public String getStatus() { return done ? label + " complete" : label; }
    }
}