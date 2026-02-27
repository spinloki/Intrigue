package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.IntrigueSubfaction;
import spinloki.Intrigue.campaign.ops.*;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.Random;

/**
 * Sim-side OpFactory that creates lightweight raid ops with no game dependencies.
 * Uses a SimCombatPhase that resolves combat probabilistically.
 */
public class SimOpFactory implements OpFactory {

    private final Random rng;
    private final SimConfig config;

    public SimOpFactory(Random rng, SimConfig config) {
        this.rng = rng;
        this.config = config;
    }

    @Override
    public IntrigueOp createRaidOp(String opId, IntrigueSubfaction attackerSubfaction, IntrigueSubfaction targetSubfaction) {
        return new SimRaidOp(opId, attackerSubfaction, targetSubfaction, rng, config);
    }

    @Override
    public IntrigueOp createEstablishBaseOp(String opId, IntrigueSubfaction subfaction) {
        return new SimEstablishBaseOp(opId, subfaction);
    }

    /**
     * A lightweight RaidOp that uses abstract combat resolution.
     */
    static class SimRaidOp extends IntrigueOp {

        private final int attackerCohesion;
        private final int defenderCohesion;
        private final boolean attackerMerciless;
        private final Random rng;
        private final SimConfig config;
        private boolean attackerWon = false;

        SimRaidOp(String opId, IntrigueSubfaction attacker, IntrigueSubfaction target,
                  Random rng, SimConfig config) {
            super(opId,
                  attacker.getLeaderId(),
                  target.getLeaderId(),
                  attacker.getSubfactionId(),
                  target.getSubfactionId());
            this.attackerCohesion = attacker.getCohesion();
            this.defenderCohesion = target.getCohesion();
            this.rng = rng;
            this.config = config;

            // Check leader traits
            IntriguePerson leader = IntrigueServices.people().getById(attacker.getLeaderId());
            this.attackerMerciless = leader != null && leader.getTraits().contains(IntrigueTraits.MERCILESS);

            // Phases: assemble, simulated combat, return
            phases.add(new AssemblePhase(attackerCohesion));
            phases.add(new SimCombatPhase());
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
            return attackerWon ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
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

                // Attacker gains cohesion, some legitimacy (diminishing returns)
                if (attacker != null) {
                    float gainMult = 1f - (attacker.getCohesion() / 150f);
                    int actualCoh = Math.max(1, Math.round(cohesionGain * Math.max(0.2f, gainMult)));
                    attacker.setCohesion(attacker.getCohesion() + actualCoh);
                    attacker.setLegitimacy(attacker.getLegitimacy() + legitimacyShift / 2);
                }
                // Defender loses legitimacy primarily, some cohesion
                if (defender != null) {
                    float lossMult = defender.getLegitimacy() / 100f;
                    int actualLeg = Math.max(1, Math.round(legitimacyShift * Math.max(0.2f, lossMult)));
                    defender.setLegitimacy(defender.getLegitimacy() - actualLeg);
                    defender.setCohesion(defender.getCohesion() - cohesionGain / 2);
                }

                if (attacker != null && defender != null) {
                    int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
                    subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                            rel + config.relDropOnRaid);
                }
            } else {
                int cohesionLoss = config.basePowerShift / 2;
                int legitimacyGain = config.basePowerShift / 3;

                // Attacker loses cohesion (failed operation)
                if (attacker != null) {
                    float lossMult = attacker.getCohesion() / 100f;
                    int actual = Math.max(1, Math.round(cohesionLoss * Math.max(0.2f, lossMult)));
                    attacker.setCohesion(attacker.getCohesion() - actual);
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

        /**
         * Phase that resolves combat abstractly using a sigmoid probability.
         */
        class SimCombatPhase implements OpPhase {
            private boolean done = false;

            @Override
            public void advance(float days) {
                if (done) return;
                int attackerFP = config.baseFP + (int)(attackerCohesion * config.fpPerPower);
                int defenderFP = config.defenderBaseFP + (int)(defenderCohesion * config.defenderFpPerPower);

                // Underdog bonus
                int fpGap = attackerFP - defenderFP;
                if (fpGap > 0) {
                    defenderFP += (int)(fpGap * config.underdogFpBonus);
                }

                double diff = attackerFP - defenderFP;
                double prob = 1.0 / (1.0 + Math.exp(-config.combatSteepness * diff));
                attackerWon = rng.nextDouble() < prob;
                done = true;
            }

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public String getStatus() {
                if (!done) return "Engaging";
                return attackerWon ? "Victory" : "Defeat";
            }
        }
    }

    /**
     * Lightweight EstablishBaseOp for sim mode — auto-succeeds after a short delay,
     * assigns a synthetic market ID as the subfaction's home.
     */
    static class SimEstablishBaseOp extends IntrigueOp {

        private final String subfactionId;

        SimEstablishBaseOp(String opId, IntrigueSubfaction subfaction) {
            super(opId,
                  subfaction.getLeaderId(),
                  null,
                  subfaction.getSubfactionId(),
                  null);
            this.subfactionId = subfaction.getSubfactionId();

            // Single timed phase representing scouting
            phases.add(new SimScoutPhase());
        }

        @Override
        public String getOpTypeName() {
            return "Establish Base";
        }

        @Override
        protected void onStarted() {
            // nothing needed in sim
        }

        @Override
        protected boolean shouldAbort() {
            if (getInitiator() == null) return true;
            IntrigueSubfaction sf = getInitiatorSubfaction();
            return sf != null && sf.hasHomeMarket();
        }

        @Override
        protected OpOutcome determineOutcome() {
            return OpOutcome.SUCCESS;
        }

        @Override
        protected void applyOutcome() {
            IntrigueSubfaction sf = getInitiatorSubfaction();
            if (sf != null && getOutcome() == OpOutcome.SUCCESS) {
                String fakeMarketId = "sim_base_" + subfactionId;
                sf.setHomeMarketId(fakeMarketId);
                sf.setCohesion(sf.getCohesion() + 10);
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
}