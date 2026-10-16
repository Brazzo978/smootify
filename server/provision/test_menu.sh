#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source "$HERE/setup.sh"
TEST_DIR=$(mktemp -d)
trap 'command rm -rf "$TEST_DIR"' EXIT
LOG=$TEST_DIR/calls
: > "$LOG"
installed() { return 0; }
backend() {
    printf '%s\n' "$*" >> "$LOG"
    [[ $1 != list ]] || printf 'alice\tmanaged\nbob\tmanaged\nexternal\texternal\n'
}
# A dedicated list action, add, revoke and numbered QR selection.
printf '1\n\n2\nbob\n\n3\nalice\ny\n\n4\n2\n\n0\n' | menu > "$TEST_DIR/out" 2> "$TEST_DIR/err"
grep -q '1) List clients' "$TEST_DIR/out"
grep -q 'alice[[:space:]]*managed' "$TEST_DIR/out"
grep -q '^add bob --show-qr$' "$LOG"
grep -q '^revoke alice$' "$LOG"
grep -q '^export bob --show-qr$' "$LOG"
grep -q '1) alice' "$TEST_DIR/out"
grep -q '2) bob' "$TEST_DIR/out"
! grep -q '3) external' "$TEST_DIR/out"
! grep -q 'keep config' "$TEST_DIR/out"
! grep -q '6)' "$TEST_DIR/out"
# Revocation cancellation must not invoke revoke.
: > "$LOG"
printf '3\nalice\nn\n\n0\n' | menu > /dev/null
! grep -q '^revoke ' "$LOG"
# QR cancellation and out-of-range input never export credentials.
: > "$LOG"
printf '4\n0\n\n0\n' | menu > /dev/null
! grep -q '^export ' "$LOG"
printf '4\n99999999999999999999999\n\n0\n' | menu > "$TEST_DIR/out" 2> "$TEST_DIR/err"
grep -q 'Select a client number' "$TEST_DIR/err"
! grep -q '^export ' "$LOG"
# An unavailable client listing must fail before asking for a selection.
backend() { [[ $1 != list ]]; }
if show_client_qr > "$TEST_DIR/out" 2> "$TEST_DIR/err"; then exit 1; fi
! grep -q '0) Cancel' "$TEST_DIR/out"
# Empty/external-only lists have no selectable profiles.
backend() { printf 'external\texternal\n'; }
show_client_qr > "$TEST_DIR/out"
grep -q 'No managed client profiles' "$TEST_DIR/out"
# A backend failure stays in the menu, reports failure, and accepts Exit.
backend() { [[ $1 != add ]]; }
printf '2\nbob\n\n0\n' | menu > "$TEST_DIR/out" 2> "$TEST_DIR/err"
grep -q 'Client operation failed' "$TEST_DIR/err"
grep -q 'Smootify VPS setup' "$TEST_DIR/out"
# The absent-server menu contains installation only; wizard keeps TLS arguments.
installed() { return 1; }
printf '0\n' | menu > "$TEST_DIR/out"
grep -q '1) Install server' "$TEST_DIR/out"
! grep -q 'Add client' "$TEST_DIR/out"
prerequisites() { return 0; }
backend() { printf '%s\n' "$*" >> "$LOG"; }
persist_assets() { return 0; }
install_server() { return 0; }
install_companions() { return 0; }
: > "$LOG"
printf 'vpn.example.com\nalice\n1\nvpn.example.com\ny\n' | wizard > /dev/null
grep -q '^preflight --name alice --server-address vpn.example.com --tls-server-name vpn.example.com --show-qr$' "$LOG"
grep -q '^init --name alice --server-address vpn.example.com --tls-server-name vpn.example.com --show-qr$' "$LOG"
# One uninstall always purges. Mock fixed-path commands so no live file is touched.
UPSTREAM=$TEST_DIR/upstream
touch "$UPSTREAM"
bash() { printf 'upstream %s\n' "$*" >> "$LOG"; }
rm() { printf 'rm %s\n' "$*" >> "$LOG"; }
systemctl() { printf 'systemctl %s\n' "$*" >> "$LOG"; }
: > "$LOG"
printf 'n\n' | uninstall_server > /dev/null
[[ ! -s $LOG ]]
printf 'y\n' | uninstall_server > /dev/null
grep -q '^upstream .* --purge$' "$LOG"
! grep -q -- '--uninstall$' "$LOG"
grep -q '^rm -rf -- /var/lib/mqvpn-management$' "$LOG"
grep -q '^rm -f -- /usr/local/sbin/smootify-setup /run/lock/mqvpn-provision.lock$' "$LOG"
grep -q '^rm -rf -- /usr/local/lib/smootify$' "$LOG"
# A successful uninstall exits the menu instead of reusing deleted assets.
installed() { return 0; }
printf '5\ny\n' | menu > "$TEST_DIR/out"
grep -q 'Smootify removed' "$TEST_DIR/out"
[[ $(grep -c 'Smootify VPS setup' "$TEST_DIR/out") == 1 ]]
main --help > "$TEST_DIR/help"
grep -q 'Usage: bash setup.sh' "$TEST_DIR/help"
printf 'menu tests passed\n'
