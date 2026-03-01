package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.IntrigueTerritory;
import spinloki.Intrigue.campaign.ops.AssemblePhase;
import spinloki.Intrigue.campaign.ops.IntrigueOp;
import spinloki.Intrigue.campaign.ops.OpFactory;
import spinloki.Intrigue.campaign.ops.OpOutcome;
import spinloki.Intrigue.campaign.ops.OpPhase;
import spinloki.Intrigue.campaign.ops.ReturnPhase;
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

    /** Get the outcome resolver (for applying player modifiers, etc.). */
    public OpOutcomeResolver getResolver() {
        return resolver;
    }

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        return new SimRaidOp(opId, attackerSubfaction, targetSubfaction, resolver, config);
    }

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction,
                                   IntrigueSubfaction targetSubfaction, String targetMarketId) {
        // In sim mode, target market doesn't affect outcome resolution - same SimRaidOp
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

    @Override
    public IntrigueOp createSendSuppliesOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimSendSuppliesOp(opId, subfaction, territoryId, resolver, config);
    }

    @Override
    public IntrigueOp createRallyOp(String opId, IntrigueSubfaction subfaction) {
        return new SimRallyOp(opId, subfaction, resolver, config);
    }

    @Override
    public IntrigueOp createInfightingOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimInfightingOp(opId, subfaction, territoryId, config);
    }

    @Override
    public IntrigueOp createExpulsionOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimExpulsionOp(opId, subfaction, territoryId, config);
    }

    @Override
    public IntrigueOp createCivilWarOp(String opId, IntrigueSubfaction subfaction) {
        return new SimCivilWarOp(opId, subfaction);
    }

    @Override
    public IntrigueOp createMischiefOp(String opId, IntrigueSubfaction initiator,
                                       IntrigueSubfaction victim, String territoryId,
                                       IntrigueOp targetOp) {
        return new SimMischiefOp(opId, initiator, victim, territoryId,
                targetOp != null ? targetOp.getOpId() : null, resolver, config);
    }

    @Override
    public IntrigueOp createAssaultTerritoryBaseOp(String opId, IntrigueSubfaction attacker,
                                                   IntrigueSubfaction defender, String territoryId) {
        return new SimAssaultTerritoryBaseOp(opId, attacker, defender, territoryId, resolver);
    }

    @Override
    public IntrigueOp createUpgradePresenceOp(String opId, IntrigueSubfaction subfaction, String territoryId) {
        return new SimUpgradePresenceOp(opId, subfaction, territoryId, resolver);
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
            this.attackerCohesion = attacker.getHomeCohesion();
            this.defenderCohesion = target.getHomeCohesion();
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

            // Set cooldown on attacking subfaction (skip if no-cost op)
            if (attacker != null && !isNoCost()) {
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

                // Attacker loses home cohesion (failed operation) - skip if no-cost
                if (attacker != null && !isNoCost()) {
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
     * Lightweight EstablishBaseOp for sim mode - auto-succeeds after a short delay,
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
        private final String factionId;
        private final String tId;
        private final OpOutcomeResolver resolver;

        SimScoutTerritoryOp(String opId, IntrigueSubfaction subfaction, String territoryId, OpOutcomeResolver resolver) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.factionId = subfaction.getFactionId();
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
            // Mirror the game-side hostile-cohesion check:
            // fail if combined cohesion of all hostile established subfactions exceeds 100
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory territory = territories.getById(tId);
                if (territory != null) {
                    int combinedHostileCohesion = 0;
                    for (String otherSfId : territory.getActiveSubfactionIds()) {
                        if (otherSfId.equals(subfactionId)) continue;
                        if (!territory.getPresence(otherSfId).isEstablishedOrHigher()) continue;
                        IntrigueSubfaction otherSf = IntrigueServices.subfactions().getById(otherSfId);
                        if (otherSf == null) continue;
                        if (!IntrigueServices.hostility().areHostile(factionId, otherSf.getFactionId())) continue;
                        combinedHostileCohesion += territory.getCohesion(otherSfId);
                    }
                    if (combinedHostileCohesion > 100) {
                        return OpOutcome.FAILURE;
                    }
                }
            }
            return OpOutcome.SUCCESS;
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

            // Claim a free slot immediately so concurrent evaluations see it as taken
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory t = territories.getById(territoryId);
                if (t != null) {
                    java.util.List<IntrigueTerritory.BaseSlot> freeSlots = t.getFreeSlots();
                    if (!freeSlots.isEmpty()) {
                        t.claimSlot(freeSlots.get(0), subfaction.getSubfactionId());
                    }
                }
            }
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
                        // Slot was already claimed in constructor
                        t.setPresence(subfactionId, IntrigueTerritory.Presence.ESTABLISHED);
                        t.setCohesion(subfactionId, 50);
                        t.setBaseMarketId(subfactionId, "sim_terrbase_" + subfactionId + "_" + tId);
                    }
                }
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 8);
                    sf.setLegitimacy(sf.getLegitimacy() + 5);
                }
            } else {
                // Revert presence to NONE on failure, release any claimed slot
                IntrigueTerritoryAccess territories = IntrigueServices.territories();
                if (territories != null) {
                    IntrigueTerritory t = territories.getById(tId);
                    if (t != null) {
                        t.setPresence(subfactionId, IntrigueTerritory.Presence.NONE);
                        t.releaseSlot(subfactionId);
                    }
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

        @Override public boolean canBeTargetedByMischief() { return !isResolved(); }
        @Override public String describeMischiefEffect() { return "Broadcasting confusing signals to disrupt patrols"; }
        @Override public void applyMischiefSabotage() { addMischiefPenalty(0.15f); }

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

    /**
     * Sim-side SendSuppliesOp: completes instantly.
     * Success → territory cohesion + gain. Failure → territory cohesion - loss.
     */
    static class SimSendSuppliesOp extends IntrigueOp {

        private final String subfactionId;
        private final String tId;
        private final OpOutcomeResolver resolver;
        private final SimConfig config;

        SimSendSuppliesOp(String opId, IntrigueSubfaction subfaction, String territoryId,
                          OpOutcomeResolver resolver, SimConfig config) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            this.config = config;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Sending supplies"));
        }

        @Override public String getOpTypeName() { return "Send Supplies"; }
        @Override protected void onStarted() {}

        @Override public boolean canBeTargetedByMischief() { return !isResolved(); }
        @Override public String describeMischiefEffect() { return "Interdicting supply convoys to disrupt deliveries"; }
        @Override public void applyMischiefSabotage() { addMischiefPenalty(0.15f); }

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolveSendSupplies(getInitiatorSubfaction(), tId);
        }

        @Override
        protected void applyOutcome() {
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory t = territories != null ? territories.getById(tId) : null;

            if (t != null) {
                int current = t.getCohesion(subfactionId);
                if (getOutcome() == OpOutcome.SUCCESS) {
                    t.setCohesion(subfactionId, current + config.sendSuppliesCohesionGain);
                } else {
                    t.setCohesion(subfactionId, current - config.sendSuppliesCohesionLoss);
                }
            }

            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /**
     * Sim-side RallyOp: consolidate home base.
     * Success → home cohesion + gain. Failure → no change (wastes time).
     */
    static class SimRallyOp extends IntrigueOp {

        private final OpOutcomeResolver resolver;
        private final SimConfig config;

        SimRallyOp(String opId, IntrigueSubfaction subfaction,
                   OpOutcomeResolver resolver, SimConfig config) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.resolver = resolver;
            this.config = config;
            phases.add(new InstantPhase("Rallying"));
        }

        @Override public String getOpTypeName() { return "Rally"; }
        @Override protected void onStarted() {}

        @Override public boolean canBeTargetedByMischief() { return !isResolved(); }
        @Override public String describeMischiefEffect() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            String name = sf != null ? sf.getName() : getInitiatorSubfactionId();
            return "Disrupting " + name + "'s rally";
        }
        @Override public void applyMischiefSabotage() { addMischiefPenalty(0.15f); }

        @Override
        protected OpOutcome determineOutcome() {
            return resolver.resolveRally(getInitiatorSubfaction());
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                if (getOutcome() == OpOutcome.SUCCESS) {
                    sf.setHomeCohesion(sf.getHomeCohesion() + config.rallyCohesionGain);
                }
                // Failure: no change - a failed rally just wastes time
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /**
     * Sim-side InfightingOp: resolves instantly, costs legitimacy.
     */
    static class SimInfightingOp extends IntrigueOp {
        private final SimConfig config;

        SimInfightingOp(String opId, IntrigueSubfaction subfaction, String territoryId, SimConfig config) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.config = config;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Infighting"));
        }

        @Override public String getOpTypeName() { return "Infighting"; }
        @Override protected void onStarted() {}
        @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLegitimacy(sf.getLegitimacy() - config.infightingLegitimacyLoss);
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /**
     * Sim-side ExpulsionOp: resolves instantly, removes subfaction from territory,
     * heavy legitimacy loss.
     */
    static class SimExpulsionOp extends IntrigueOp {
        private final String subfactionId;
        private final String tId;
        private final SimConfig config;

        SimExpulsionOp(String opId, IntrigueSubfaction subfaction, String territoryId, SimConfig config) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.config = config;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Expelled"));
        }

        @Override public String getOpTypeName() { return "Expulsion"; }
        @Override protected void onStarted() {}
        @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

        @Override
        protected void applyOutcome() {
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory t = territories.getById(tId);
                if (t != null) t.demotePresence(subfactionId);
            }
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLegitimacy(sf.getLegitimacy() - config.expulsionLegitimacyLoss);
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /**
     * Sim-side CivilWarOp: resolves instantly, resets home cohesion and legitimacy to 50.
     */
    static class SimCivilWarOp extends IntrigueOp {

        SimCivilWarOp(String opId, IntrigueSubfaction subfaction) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            phases.add(new InstantPhase("Civil War"));
        }

        @Override public String getOpTypeName() { return "Civil War"; }
        @Override protected void onStarted() {}
        @Override protected OpOutcome determineOutcome() { return OpOutcome.FAILURE; }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setHomeCohesion(50);
                sf.setLegitimacy(50);
                sf.resetLowHomeCohesionTicks();
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }
        }
    }

    /**
     * Sim-side MischiefOp: sabotage another subfaction's op in a shared territory.
     * Success penalizes the victim's territory cohesion and legitimacy.
     */
    static class SimMischiefOp extends IntrigueOp {

        private final String victimSubfactionId;
        private final String tId;
        private final String targetOpId;
        private final OpOutcomeResolver resolver;
        private final SimConfig config;
        /** Initiator's directed friction toward the victim at creation time. */
        private final int initiatorFrictionAtCreation;

        SimMischiefOp(String opId, IntrigueSubfaction initiator, IntrigueSubfaction victim,
                      String territoryId, String targetOpId,
                      OpOutcomeResolver resolver, SimConfig config) {
            super(opId, initiator.getLeaderId(), victim.getLeaderId(),
                  initiator.getSubfactionId(), victim.getSubfactionId());
            this.victimSubfactionId = victim.getSubfactionId();
            this.tId = territoryId;
            this.targetOpId = targetOpId;
            this.resolver = resolver;
            this.config = config;
            setTerritoryId(territoryId);

            // Capture the initiator's friction before OpEvaluator resets it
            int capturedFriction = 0;
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            if (territories != null) {
                IntrigueTerritory t = territories.getById(territoryId);
                if (t != null) {
                    capturedFriction = t.getFriction(initiator.getSubfactionId(), victim.getSubfactionId());
                }
            }
            this.initiatorFrictionAtCreation = capturedFriction;

            phases.add(new InstantPhase("Mischief"));
        }

        @Override public String getOpTypeName() { return "Mischief"; }
        @Override protected void onStarted() {}

        @Override
        protected OpOutcome determineOutcome() {
            IntrigueSubfaction initiator = getInitiatorSubfaction();
            IntrigueSubfaction victim = IntrigueServices.subfactions().getById(victimSubfactionId);
            return resolver.resolveMischief(initiator, victim);
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) {
                sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }

            if (getOutcome() == OpOutcome.SUCCESS) {
                // Penalize the victim's territory cohesion and legitimacy
                IntrigueSubfaction victim = IntrigueServices.subfactions().getById(victimSubfactionId);
                if (victim != null) {
                    victim.setLegitimacy(victim.getLegitimacy() - config.mischiefLegitimacyPenalty);
                }
                IntrigueTerritoryAccess territories = IntrigueServices.territories();
                if (territories != null) {
                    IntrigueTerritory t = territories.getById(tId);
                    if (t != null) {
                        int current = t.getCohesion(victimSubfactionId);
                        t.setCohesion(victimSubfactionId, current - config.mischiefCohesionPenalty);

                        // Friction transfer: half the initiator's pre-reset friction
                        // is applied to the victim's friction toward the initiator.
                        int transfer = initiatorFrictionAtCreation / 2;
                        if (transfer > 0) {
                            int victimFriction = t.getFriction(victimSubfactionId, getInitiatorSubfactionId());
                            t.setFriction(victimSubfactionId, getInitiatorSubfactionId(),
                                    victimFriction + transfer);
                        }
                    }
                }
            }
            // On failure: no effect - mischief fizzled
        }
    }

    /**
     * Sim-side UpgradePresenceOp: advances territory presence tier on success.
     * ESTABLISHED → FORTIFIED (costs 25 terrCoh, threshold 75)
     * FORTIFIED → DOMINANT (costs 40 terrCoh, threshold 90)
     */
    static class SimUpgradePresenceOp extends IntrigueOp {

        private final String subfactionId;
        private final String tId;
        private final IntrigueTerritory.Presence currentPresence;
        private final IntrigueTerritory.Presence targetPresence;
        private final OpOutcomeResolver resolver;

        SimUpgradePresenceOp(String opId, IntrigueSubfaction subfaction,
                             String territoryId, OpOutcomeResolver resolver) {
            super(opId, subfaction.getLeaderId(), null, subfaction.getSubfactionId(), null);
            this.subfactionId = subfaction.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            setTerritoryId(territoryId);

            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory t = territories != null ? territories.getById(territoryId) : null;
            this.currentPresence = t != null ? t.getPresence(subfactionId) : IntrigueTerritory.Presence.ESTABLISHED;
            this.targetPresence = currentPresence.next();
            phases.add(new InstantPhase("Upgrading presence"));
        }

        @Override public String getOpTypeName() { return "Upgrade Presence"; }
        @Override protected void onStarted() {}

        @Override
        protected OpOutcome determineOutcome() {
            // High success rate (75%) since the subfaction already invested heavily in cohesion
            return Math.random() < 0.75 ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null) sf.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());

            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory territory = territories != null ? territories.getById(tId) : null;

            if (getOutcome() == OpOutcome.SUCCESS && territory != null && targetPresence != null) {
                territory.setPresence(subfactionId, targetPresence);
                int terrCost = (targetPresence == IntrigueTerritory.Presence.FORTIFIED) ? 25 : 40;
                territory.setCohesion(subfactionId,
                        territory.getCohesion(subfactionId) - terrCost);
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 10);
                    sf.setLegitimacy(sf.getLegitimacy() + 5);
                }
            } else {
                if (territory != null) {
                    territory.setCohesion(subfactionId,
                            territory.getCohesion(subfactionId) - 10);
                }
                if (sf != null) {
                    sf.setHomeCohesion(sf.getHomeCohesion() - 5);
                }
            }
        }
    }

    /**
     * Sim-side AssaultTerritoryBaseOp: attacks a hostile subfaction's base to take it over.
     * On success: defender is expelled, attacker takes the slot.
     * On failure: attacker's presence reverts to NONE.
     */
    static class SimAssaultTerritoryBaseOp extends IntrigueOp {

        private final String attackerSfId;
        private final String defenderSfId;
        private final String tId;
        private final OpOutcomeResolver resolver;

        SimAssaultTerritoryBaseOp(String opId, IntrigueSubfaction attacker,
                                  IntrigueSubfaction defender, String territoryId,
                                  OpOutcomeResolver resolver) {
            super(opId, attacker.getLeaderId(), defender.getLeaderId(),
                  attacker.getSubfactionId(), defender.getSubfactionId());
            this.attackerSfId = attacker.getSubfactionId();
            this.defenderSfId = defender.getSubfactionId();
            this.tId = territoryId;
            this.resolver = resolver;
            setTerritoryId(territoryId);
            phases.add(new InstantPhase("Assaulting territory base"));
        }

        @Override public String getOpTypeName() { return "Assault Territory Base"; }
        @Override protected void onStarted() {}

        @Override
        protected OpOutcome determineOutcome() {
            // 50% base chance, modified by relative cohesion
            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory territory = territories != null ? territories.getById(tId) : null;
            int defCoh = territory != null ? territory.getCohesion(defenderSfId) : 50;
            IntrigueSubfaction atk = getInitiatorSubfaction();
            int atkCoh = atk != null ? atk.getHomeCohesion() : 50;
            float chance = 0.50f + (atkCoh - defCoh) * 0.003f;
            chance = Math.max(0.2f, Math.min(0.8f, chance));
            return Math.random() < chance ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction attacker = getInitiatorSubfaction();
            IntrigueSubfaction defender = IntrigueServices.subfactions().getById(defenderSfId);
            if (attacker != null) {
                attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }

            IntrigueTerritoryAccess territories = IntrigueServices.territories();
            IntrigueTerritory territory = territories != null ? territories.getById(tId) : null;

            if (getOutcome() == OpOutcome.SUCCESS && territory != null) {
                // Demote defender by one tier
                IntrigueTerritory.Presence defenderAfter = territory.demotePresence(defenderSfId);
                if (defender != null) {
                    defender.setLegitimacy(defender.getLegitimacy() - 15);
                }
                // Only claim slot if the defender was fully removed (demoted to NONE)
                if (defenderAfter == IntrigueTerritory.Presence.NONE) {
                    java.util.List<IntrigueTerritory.BaseSlot> freeSlots = territory.getFreeSlots();
                    if (!freeSlots.isEmpty()) {
                        territory.claimSlot(freeSlots.get(0), attackerSfId);
                    }
                    territory.setPresence(attackerSfId, IntrigueTerritory.Presence.ESTABLISHED);
                    territory.setCohesion(attackerSfId, 40);
                    territory.setBaseMarketId(attackerSfId, "sim_assault_" + attackerSfId + "_" + tId);
                }
                if (attacker != null) {
                    attacker.setHomeCohesion(attacker.getHomeCohesion() - 12);
                    attacker.setLegitimacy(attacker.getLegitimacy() + 8);
                }
            } else {
                // Failed — revert attacker's presence
                if (territory != null) {
                    territory.setPresence(attackerSfId, IntrigueTerritory.Presence.NONE);
                    territory.releaseSlot(attackerSfId);
                }
                if (attacker != null) {
                    attacker.setHomeCohesion(attacker.getHomeCohesion() - 6);
                    attacker.setLegitimacy(attacker.getLegitimacy() - 6);
                }
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