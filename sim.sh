#!/usr/bin/env bash
set -euo pipefail

# ── Simulation Harness Build & Run ─────────────────────────────────────
# Compiles and runs the standalone simulation harness with only pure-Java
# classes (no Starsector dependencies). Use this to rapidly validate
# territorial conflict dynamics without launching the game.
#
# Usage:
#   ./sim.sh                    # Run with defaults (100 days, seed 42)
#   ./sim.sh --days 500         # Run 500 simulated days
#   ./sim.sh --seed 123         # Different random seed
#   ./sim.sh --days 1000 --seed 7

JAVAC="$HOME/.jdks/azul-17.0.18/bin/javac"
JAVA="$HOME/.jdks/azul-17.0.18/bin/java"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIM_OUT="$PROJECT_DIR/out/sim"

# Starsector install — needed for org.json (json.jar)
SS="${SS:-$HOME/games/starsector}"
JSON_JAR="$SS/json.jar"

if [[ ! -f "$JSON_JAR" ]]; then
    echo "ERROR: Cannot find json.jar at $JSON_JAR"
    echo "Set the SS environment variable to your Starsector install directory."
    exit 1
fi

# Pure-Java source files (no Starsector imports, only org.json)
SIM_SOURCES=(
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/PresenceState.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/PresenceFactor.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/BaseSlotType.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/BaseSlot.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/SubfactionPresence.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/ActiveOp.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/EntanglementType.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/SubfactionPair.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/ActiveEntanglement.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/HostilityResolver.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/OpEngine.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/EntanglementEngine.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/territory/TerritoryState.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/config/IntrigueSettings.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/subfaction/SubfactionDef.java"
    "$PROJECT_DIR/src/spinloki/Intrigue/test/SimulationHarness.java"
)

rm -rf "$SIM_OUT"
mkdir -p "$SIM_OUT"

echo "Compiling simulation harness (org.json only, no Starsector API)..."
"$JAVAC" --release 17 -cp "$JSON_JAR" -d "$SIM_OUT" "${SIM_SOURCES[@]}"

echo "Running simulation..."
echo
"$JAVA" -cp "$SIM_OUT:$JSON_JAR" -Dproject.dir="$PROJECT_DIR" \
    spinloki.Intrigue.test.SimulationHarness "$@"
