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
    [[ $1 != list ]] || printf 'alice\tmanaged\n'
}
# Menu lists configured names and dispatches add, revoke and QR export.
printf '2\nbob\n\n3\nalice\ny\n\n4\nalice\n\n0\n' | menu > "$TEST_DIR/out" 2> "$TEST_DIR/err"
grep -q 'Smootify VPS setup' "$TEST_DIR/out"
grep -q 'alice[[:space:]]*managed' "$TEST_DIR/out"
grep -q '^add bob --show-qr$' "$LOG"
grep -q '^revoke alice$' "$LOG"
grep -q '^export alice --show-qr$' "$LOG"
# Cancellation must not invoke revoke.
: > "$LOG"
printf '3\nalice\nn\n\n0\n' | menu > /dev/null
! grep -q '^revoke ' "$LOG"
# A backend failure stays in the menu, reports failure, and accepts Exit.
backend() { [[ $1 != add ]]; }
printf '2\nbob\n\n0\n' | menu > "$TEST_DIR/out" 2> "$TEST_DIR/err"
grep -q 'Client operation failed' "$TEST_DIR/err"
grep -q 'Smootify VPS setup' "$TEST_DIR/out"
# Wizard keeps explicit TLS choice and argument boundaries.
installed() { return 1; }
prerequisites() { return 0; }
backend() { printf '%s\n' "$*" >> "$LOG"; }
persist_assets() { return 0; }
install_server() { return 0; }
install_companions() { return 0; }
: > "$LOG"
printf 'vpn.example.com\nalice\n1\nvpn.example.com\ny\n' | wizard > /dev/null
grep -q '^preflight --name alice --server-address vpn.example.com --tls-server-name vpn.example.com --show-qr$' "$LOG"
grep -q '^init --name alice --server-address vpn.example.com --tls-server-name vpn.example.com --show-qr$' "$LOG"
# Uninstall cancellation never calls upstream. Confirmed path is tested with
# mocked fixed-path commands, so this test never touches live system files.
UPSTREAM=$TEST_DIR/upstream
touch "$UPSTREAM"
bash() { printf 'upstream %s\n' "$*" >> "$LOG"; }
rm() { printf 'rm %s\n' "$*" >> "$LOG"; }
systemctl() { printf 'systemctl %s\n' "$*" >> "$LOG"; }
: > "$LOG"
printf 'n\n' | uninstall_server no > /dev/null
[[ ! -s $LOG ]]
printf 'y\n' | uninstall_server no > /dev/null
grep -q '^upstream .* --uninstall$' "$LOG"
! grep -q -- '--purge' "$LOG"
: > "$LOG"
printf 'y\nn\n' | uninstall_server yes > /dev/null
[[ ! -s $LOG ]]
printf 'y\ny\n' | uninstall_server yes > /dev/null
grep -q '^upstream .* --purge$' "$LOG"
grep -q '^rm -rf -- /var/lib/mqvpn-management$' "$LOG"
main --help > "$TEST_DIR/help"
grep -q 'Usage: bash setup.sh' "$TEST_DIR/help"
printf 'menu tests passed\n'
