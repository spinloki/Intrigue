package spinloki.Intrigue.campaign.ops.sim;

import spinloki.Intrigue.IntrigueTraits;
import spinloki.Intrigue.campaign.IntriguePerson;
import spinloki.Intrigue.campaign.ops.*;
import spinloki.Intrigue.campaign.spi.IntrigueServices;

import java.util.Random;

/**
 * Sim-side OpFactory that creates lightweight raid ops with no game dependencies.
 * Instead of TravelAndFightPhase (which spawns real fleets), this uses a
 * SimCombatPhase that resolves combat probabilistically.
 */
public class SimOpFactory implements OpFactory {

    private final Random rng;
    private final SimConfig config;

    public SimOpFactory(Random rng, SimConfig config) {
        this.rng = rng;
        this.config = config;
    }

    @Override
    public IntrigueOp createRaidOp(String opId, IntriguePerson initiator, IntriguePerson target) {
        return new SimRaidOp(opId, initiator, target, rng, config);
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
        private boolean combatResolved = false;
        private boolean attackerWon = false;

        SimRaidOp(String opId, IntriguePerson initiator, IntriguePerson target,
                  Random rng, SimConfig config) {
            super(opId, initiator.getPersonId(), target.getPersonId());
            this.attackerPower = initiator.getPower();
            this.defenderPower = target.getPower();
            this.attackerMerciless = initiator.getTraits().contains(IntrigueTraits.MERCILESS);
            this.rng = rng;
            this.config = config;

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
            IntrigueServices.people().checkoutToMarket(
                    getInitiatorId(),
                    IntrigueServices.people().getById(getTargetId()).getHomeMarketId()
            );
        }

        @Override
        protected OpOutcome determineOutcome() {
            return attackerWon ? OpOutcome.SUCCESS : OpOutcome.FAILURE;
        }

        @Override
        protected void applyOutcome() {
            var people = IntrigueServices.people();
            var attacker = people.getById(getInitiatorId());
            var defender = people.getById(getTargetId());

            if (attacker != null) {
                people.returnHome(attacker.getPersonId());
                attacker.setLastOpTimestamp(IntrigueServices.clock().getTimestamp());
            }

            if (getOutcome() == OpOutcome.SUCCESS) {
                int powerGain = config.basePowerShift;
                if (attackerMerciless) powerGain += config.mercilessBonusPower;

                // Diminishing returns
                if (attacker != null) {
                    float gainMult = 1f - (attacker.getPower() / 150f);
                    int actual = Math.max(1, Math.round(powerGain * Math.max(0.2f, gainMult)));
                    attacker.setPower(clamp(attacker.getPower() + actual, 0, 100));
                }
                if (defender != null) {
                    float lossMult = defender.getPower() / 100f;
                    int actual = Math.max(1, Math.round(powerGain * Math.max(0.2f, lossMult)));
                    defender.setPower(clamp(defender.getPower() - actual, 0, 100));
                }

                if (attacker != null && defender != null) {
                    int rel = getRelOrZero(attacker, defender.getPersonId());
                    people.setRelationship(attacker.getPersonId(), defender.getPersonId(),
                            rel + config.relDropOnRaid);
                }
            } else {
                int powerLoss = config.basePowerShift / 2;
                if (attacker != null) {
                    float lossMult = attacker.getPower() / 100f;
                    int actual = Math.max(1, Math.round(powerLoss * Math.max(0.2f, lossMult)));
                    attacker.setPower(clamp(attacker.getPower() - actual, 0, 100));
                }
                if (defender != null) {
                    float gainMult = 1f - (defender.getPower() / 150f);
                    int actual = Math.max(1, Math.round((powerLoss / 2) * Math.max(0.2f, gainMult)));
                    defender.setPower(clamp(defender.getPower() + actual, 0, 100));
                }

                if (attacker != null && defender != null) {
                    int rel = getRelOrZero(attacker, defender.getPersonId());
                    people.setRelationship(attacker.getPersonId(), defender.getPersonId(),
                            rel + config.relDropOnRaid / 2);
                }
            }

            if (attacker != null) people.syncMemory(attacker.getPersonId());
            if (defender != null) people.syncMemory(defender.getPersonId());
        }

        private int getRelOrZero(IntriguePerson person, String otherId) {
            Integer rel = person.getRelTo(otherId);
            return rel != null ? rel : 0;
        }

        private int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        /**
         * Phase that resolves combat abstractly using a sigmoid probability.
         */
        class SimCombatPhase implements OpPhase {
            private boolean done = false;

            @Override
            public void advance(float days) {
                if (done) return;
                // Resolve immediately on first advance
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
                combatResolved = true;
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

