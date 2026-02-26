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

    /**
     * A lightweight RaidOp that uses abstract combat resolution.
     */
    static class SimRaidOp extends IntrigueOp {

        private final int attackerPower;
        private final int defenderPower;
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
            this.attackerPower = attacker.getPower();
            this.defenderPower = target.getPower();
            this.rng = rng;
            this.config = config;

            // Check leader traits
            IntriguePerson leader = IntrigueServices.people().getById(attacker.getLeaderId());
            this.attackerMerciless = leader != null && leader.getTraits().contains(IntrigueTraits.MERCILESS);

            // Phases: assemble, simulated combat, return
            phases.add(new AssemblePhase(attackerPower));
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
                int powerGain = config.basePowerShift;
                if (attackerMerciless) powerGain += config.mercilessBonusPower;

                // Diminishing returns
                if (attacker != null) {
                    float gainMult = 1f - (attacker.getPower() / 150f);
                    int actual = Math.max(1, Math.round(powerGain * Math.max(0.2f, gainMult)));
                    attacker.setPower(attacker.getPower() + actual);
                }
                if (defender != null) {
                    float lossMult = defender.getPower() / 100f;
                    int actual = Math.max(1, Math.round(powerGain * Math.max(0.2f, lossMult)));
                    defender.setPower(defender.getPower() - actual);
                }

                if (attacker != null && defender != null) {
                    int rel = getSubfactionRelOrZero(attacker, defender.getSubfactionId());
                    subfactions.setRelationship(attacker.getSubfactionId(), defender.getSubfactionId(),
                            rel + config.relDropOnRaid);
                }
            } else {
                int powerLoss = config.basePowerShift / 2;
                if (attacker != null) {
                    float lossMult = attacker.getPower() / 100f;
                    int actual = Math.max(1, Math.round(powerLoss * Math.max(0.2f, lossMult)));
                    attacker.setPower(attacker.getPower() - actual);
                }
                if (defender != null) {
                    float gainMult = 1f - (defender.getPower() / 150f);
                    int actual = Math.max(1, Math.round((powerLoss / 2) * Math.max(0.2f, gainMult)));
                    defender.setPower(defender.getPower() + actual);
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
                int attackerFP = config.baseFP + (int)(attackerPower * config.fpPerPower);
                int defenderFP = config.defenderBaseFP + (int)(defenderPower * config.defenderFpPerPower);

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
}