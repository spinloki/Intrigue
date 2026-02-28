#!/bin/sh
# Run the Intrigue DI integration tests.
# No Starsector installation required — these are pure-logic tests.
#
# Must compile from src/ to avoid javac picking up the com/ Starsector API stubs.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="/tmp/intrigue_balance_test"
JAVAC=""
JAVA=""

# Find javac/java
for jdk_dir in "$HOME"/.jdks/*/bin; do
    if [ -x "$jdk_dir/javac" ]; then
        JAVAC="$jdk_dir/javac"
        JAVA="$jdk_dir/java"
        break
    fi
done

if [ -z "$JAVAC" ]; then
    JAVAC="$(command -v javac 2>/dev/null || true)"
    JAVA="$(command -v java 2>/dev/null || true)"
fi

if [ -z "$JAVAC" ] || [ -z "$JAVA" ]; then
    echo "ERROR: No JDK found. Install a JDK or set JAVA_HOME."
    exit 1
fi

echo "Using JDK: $JAVAC"
echo ""

# Clean and compile from src/ (avoids com/ Starsector API stubs)
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cd "$SRC_DIR"
"$JAVAC" -encoding UTF-8 -d "$OUT_DIR" \
  spinloki/Intrigue/IntrigueTraits.java \
  spinloki/Intrigue/campaign/IntriguePerson.java \
  spinloki/Intrigue/campaign/IntrigueSubfaction.java \
  spinloki/Intrigue/config/TerritoryConfig.java \
  spinloki/Intrigue/campaign/IntrigueTerritory.java \
  spinloki/Intrigue/campaign/spi/IntrigueClock.java \
  spinloki/Intrigue/campaign/spi/IntriguePeopleAccess.java \
  spinloki/Intrigue/campaign/spi/IntrigueSubfactionAccess.java \
  spinloki/Intrigue/campaign/spi/IntrigueTerritoryAccess.java \
  spinloki/Intrigue/campaign/spi/IntrigueOpRunner.java \
  spinloki/Intrigue/campaign/spi/IntrigueServices.java \
  spinloki/Intrigue/campaign/spi/FactionHostilityChecker.java \
  spinloki/Intrigue/campaign/ops/OpPhase.java \
  spinloki/Intrigue/campaign/ops/OpOutcome.java \
  spinloki/Intrigue/campaign/ops/IntrigueOp.java \
  spinloki/Intrigue/campaign/ops/OpFactory.java \
  spinloki/Intrigue/campaign/ops/OpEvaluator.java \
  spinloki/Intrigue/campaign/ops/AssemblePhase.java \
  spinloki/Intrigue/campaign/ops/ReturnPhase.java \
  spinloki/Intrigue/campaign/ops/ScoutTerritoryPhase.java \
  spinloki/Intrigue/campaign/ops/sim/SimClock.java \
  spinloki/Intrigue/campaign/ops/sim/SimPeopleAccess.java \
  spinloki/Intrigue/campaign/ops/sim/SimSubfactionAccess.java \
  spinloki/Intrigue/campaign/ops/sim/SimTerritoryAccess.java \
  spinloki/Intrigue/campaign/ops/sim/SimOpRunner.java \
  spinloki/Intrigue/campaign/ops/sim/SimConfig.java \
  spinloki/Intrigue/campaign/ops/sim/OpOutcomeResolver.java \
  spinloki/Intrigue/campaign/ops/sim/DefaultOutcomeResolver.java \
  spinloki/Intrigue/campaign/ops/sim/SimOpFactory.java \
  spinloki/Intrigue/config/SubfactionConfig.java \
  spinloki/Intrigue/campaign/ops/sim/SimIntegrationTest.java

echo ""
echo "=== DI Integration Tests ==="
"$JAVA" -cp "$OUT_DIR" spinloki.Intrigue.campaign.ops.sim.SimIntegrationTest