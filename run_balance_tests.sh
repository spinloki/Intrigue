#!/bin/sh
# Run the Intrigue DI integration tests.
# No Starsector installation required — these are pure-logic tests.
#
# Must compile from src/ to avoid javac picking up the com/ Starsector API stubs.
#
# Usage:
#   ./run_balance_tests.sh                          # uses config files (default)
#   ./run_balance_tests.sh --no-config              # hardcoded 3-subfaction mode
#   ./run_balance_tests.sh -v                       # verbose mode (per-tick op log)
#   ./run_balance_tests.sh --player                 # player randomly helps OR hurts factions
#   ./run_balance_tests.sh --player-help            # player only helps factions
#   ./run_balance_tests.sh --player-hurt            # player only hurts factions
#   ./run_balance_tests.sh --player-interval=20     # player reconsiders every 20 ticks (default 10)
#   ./run_balance_tests.sh --ticks=500              # run for 500 ticks (default 200)
#   ./run_balance_tests.sh --config=/path/to/file   # use custom config file
#   Flags combine: ./run_balance_tests.sh -v --player-help --ticks=400

set -e

show_help() {
    cat <<'EOF'
Intrigue Balance Test Runner
=============================

Runs the Intrigue DI integration tests and simulation.
No Starsector installation required — these are pure-logic tests.

Usage:
  ./run_balance_tests.sh [OPTIONS]

Options:
  -h, --help                  Show this help message and exit
  -v, --verbose               Verbose mode: print per-tick op log with
                              probability details
  --ticks=N                   Run the simulation for N ticks (default: 200)
  --no-config                 Use hardcoded 3-subfaction mode instead of
                              loading from config files
  --config=/path/to/file      Load subfactions from a custom config file
                              (territories loaded from intrigue_territories.json
                              in the same directory, if present)

Player Simulation:
  --player                    Enable player intervention (randomly helps OR
                              hurts one faction at a time)
  --player-help               Player only helps factions
  --player-hurt               Player only hurts factions
  --player-interval=N         Player reconsiders target every N ticks
                              (default: 10)

Examples:
  ./run_balance_tests.sh                              # config mode (default)
  ./run_balance_tests.sh --no-config                  # hardcoded 3-subfaction mode
  ./run_balance_tests.sh -v                           # verbose per-tick log
  ./run_balance_tests.sh --ticks=400                  # 400 ticks with config
  ./run_balance_tests.sh -v --player-hurt             # verbose, config, player hurts
  ./run_balance_tests.sh --player --player-interval=5 # player acts every 5 ticks
EOF
    exit 0
}

VERBOSE=false
PLAYER_MODE=""
PLAYER_INTERVAL=""
SIM_TICKS=""
CONFIG_PATH="__default__"
for arg in "$@"; do
    case "$arg" in
        -h|--help) show_help ;;
        -v|--verbose) VERBOSE=true ;;
        --player) PLAYER_MODE=both ;;
        --player-help) PLAYER_MODE=help ;;
        --player-hurt) PLAYER_MODE=hurt ;;
        --player-interval=*) PLAYER_INTERVAL="${arg#--player-interval=}" ;;
        --ticks=*) SIM_TICKS="${arg#--ticks=}" ;;
        --config=*) CONFIG_PATH="${arg#--config=}" ;;
        --config) CONFIG_PATH="__default__" ;;
        --no-config) CONFIG_PATH="" ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"

# Resolve default config path now that SCRIPT_DIR is set
if [ "$CONFIG_PATH" = "__default__" ]; then
    CONFIG_PATH="$SCRIPT_DIR/data/config/intrigue_subfactions.json"
fi
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

JAVA_FLAGS=""
if [ "$VERBOSE" = "true" ]; then
    JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.verbose=true"
    echo "(verbose mode -- showing per-tick op log with probability details)"
fi
if [ -n "$PLAYER_MODE" ]; then
    JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.player=$PLAYER_MODE"
    echo "(player mode: $PLAYER_MODE)"
fi
if [ -n "$PLAYER_INTERVAL" ]; then
    JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.player.interval=$PLAYER_INTERVAL"
fi
if [ -n "$SIM_TICKS" ]; then
    JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.ticks=$SIM_TICKS"
fi
if [ -n "$CONFIG_PATH" ]; then
    JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.config=$CONFIG_PATH"
    # Derive the territories config path from the same directory
    CONFIG_DIR=$(dirname "$CONFIG_PATH")
    TERRITORIES_PATH="$CONFIG_DIR/intrigue_territories.json"
    if [ -f "$TERRITORIES_PATH" ]; then
        JAVA_FLAGS="$JAVA_FLAGS -Dintrigue.territories=$TERRITORIES_PATH"
        echo "(config: $CONFIG_PATH + $TERRITORIES_PATH)"
    else
        echo "(config: $CONFIG_PATH -- no territories file at $TERRITORIES_PATH)"
    fi
fi
"$JAVA" $JAVA_FLAGS -cp "$OUT_DIR" spinloki.Intrigue.campaign.ops.sim.SimIntegrationTest 2>/dev/null

