#!/usr/bin/env bash
# Usage: scripts/wzaudit/jump_waypoint_scan.sh [map-id ...]
# No arguments scans the host's entire wz/Map.wz tree with the base movement profile.
# Requires Java 21 and Maven. A finding or a map load error gives a nonzero exit status.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
exec "${MVN:-mvn}" -q -Dtest=JumpWaypointAudit "-Djump.audit.maps=$*" test-compile surefire:test
