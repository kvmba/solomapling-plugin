#!/usr/bin/env bash
# Usage: scripts/wzaudit/movement_safety_scan.sh [map-id ...]
# Replays stops, walk-offs, crouch drops, rescue hops and boundary physics on WZ maps.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
exec "${MVN:-mvn}" -q -Dtest=MovementSafetyAudit "-Djump.audit.maps=$*" test-compile surefire:test
