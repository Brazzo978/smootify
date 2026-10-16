#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Smootify VPS setup; sourceable for isolated tests.
set -u
umask 077
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_SERVER=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ASSET_DIR=/usr/local/lib/smootify/server
LAUNCHER=/usr/local/sbin/smootify-setup
PROVISION=$SCRIPT_DIR/mqvpn_provision.py
UPSTREAM=$PROJECT_SERVER/install-mqvpn-0.17.0-poc.sh
CONFIG=/etc/mqvpn/server.conf
BIN=/usr/local/bin/mqvpn
error() { printf 'Error: %s\n' "$*" >&2; }
installed() { [[ -x $BIN && -f $CONFIG ]]; }
backend() { python3 "$PROVISION" "$@"; }
require_host() {
    [[ $(id -u) == 0 ]] || { error 'Run as root.'; return 1; }
    [[ -f /etc/os-release ]] || { error 'Unsupported OS.'; return 1; }
    local distro
    distro=$(. /etc/os-release; printf '%s' "$ID")
    case "$distro" in debian|ubuntu) ;; *) error 'Only Debian and Ubuntu are supported.'; return 1 ;; esac
}
missing_packages() {
    local pair cmd
    for pair in bash:bash python3:python3 systemctl:systemd openssl:openssl qrencode:qrencode iperf3:iperf3 curl:curl iptables:iptables ip6tables:iptables ip:iproute2 sysctl:procps tar:tar sha256sum:coreutils; do
        cmd=${pair%%:*}
        command -v "$cmd" >/dev/null 2>&1 || printf '%s\n' "${pair#*:}"
    done | sort -u
    [[ -f /etc/ssl/certs/ca-certificates.crt ]] || printf '%s\n' ca-certificates
}
confirm() { local answer; read -r -p "$1 [y/N] " answer || return 1; [[ $answer == y || $answer == Y || $answer == yes || $answer == YES ]]; }
prerequisites() {
    local mode=$1 missing
    missing=$(missing_packages) || return 1
    [[ -z $missing ]] && return 0
    if [[ $mode != wizard ]]; then error "Missing prerequisites: $(tr '\n' ' ' <<< "$missing")"; return 1; fi
    printf 'Missing distro packages:\n%s\n' "$missing"
    confirm 'Install these packages with apt-get now?' || return 1
    apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y $missing
}
persist_assets() {
    [[ -f $UPSTREAM && -f $PROVISION ]] || { error 'Installer assets incomplete.'; return 1; }
    if [[ $PROJECT_SERVER != "$ASSET_DIR" ]]; then
        install -d -m 0755 "$ASSET_DIR/provision" "$ASSET_DIR/management" "$ASSET_DIR/iperf" || return 1
        install -m 0755 "$UPSTREAM" "$ASSET_DIR/install-mqvpn-0.17.0-poc.sh" || return 1
        install -m 0755 "$SCRIPT_DIR/setup.sh" "$ASSET_DIR/provision/setup.sh" || return 1
        install -m 0755 "$PROVISION" "$ASSET_DIR/provision/mqvpn_provision.py" || return 1
        install -m 0755 "$PROJECT_SERVER/management/install.sh" "$PROJECT_SERVER/management/mqvpn_management.py" "$ASSET_DIR/management/" || return 1
        install -m 0644 "$PROJECT_SERVER/management/mqvpn-management.service" "$ASSET_DIR/management/" || return 1
        install -m 0755 "$PROJECT_SERVER/iperf/install.sh" "$ASSET_DIR/iperf/" || return 1
        install -m 0644 "$PROJECT_SERVER/iperf/mqvpn-iperf.service" "$ASSET_DIR/iperf/" || return 1
    fi
    install -d -m 0755 /usr/local/sbin || return 1
    printf '%s\n' '#!/bin/sh' 'exec /bin/bash /usr/local/lib/smootify/server/provision/setup.sh "$@"' > "$LAUNCHER" || return 1
    chmod 0755 "$LAUNCHER" || return 1
    PROJECT_SERVER=$ASSET_DIR; SCRIPT_DIR=$ASSET_DIR/provision
    PROVISION=$SCRIPT_DIR/mqvpn_provision.py; UPSTREAM=$ASSET_DIR/install-mqvpn-0.17.0-poc.sh
}
install_server() {
    local output
    installed && return 0
    if ! output=$(bash "$UPSTREAM" --subnet 10.88.0.0/24 --enable-control 9090 --start 2>&1); then
        unset output; error 'Pinned MQVPN install failed; output withheld because it may contain the admin key.'; return 1
    fi
    unset output
    installed || { error 'Installer finished without expected server files.'; return 1; }
}
install_companions() {
    (cd "$PROJECT_SERVER/management" && sh ./install.sh >/dev/null) || { error 'Management install failed.'; return 1; }
    (cd "$PROJECT_SERVER/iperf" && sh ./install.sh >/dev/null) || { error 'iperf install failed.'; return 1; }
}
provision_first() {
    local mode=$1; shift
    prerequisites "$mode" || return 1
    backend preflight "$@" || return 1
    persist_assets || return 1
    install_server || return 1
    install_companions || return 1
    backend init "$@" || return 1
    printf 'Provisioning complete. Scan the QR privately, then close this terminal.\n'
}
prompt_name() {
    local value
    read -r -p 'Client name ([a-z][a-z0-9_-]{0,31}): ' value || return 1
    [[ $value =~ ^[a-z][a-z0-9_-]{0,31}$ ]] || { error 'Invalid client name.'; return 1; }
    REPLY=$value
}
wizard() {
    local address name tls choice
    read -r -p 'Public server IP or DNS name: ' address || return 1
    prompt_name || return 1; name=$REPLY
    printf 'TLS: 1) Verify CA-trusted certificate  2) Allow insecure PoC connection\n'
    read -r -p 'Choose 1 or 2: ' choice || return 1
    local -a args=(--name "$name" --server-address "$address")
    case "$choice" in
        1) read -r -p 'TLS server name: ' tls || return 1; args+=(--tls-server-name "$tls") ;;
        2) confirm 'Allow insecure TLS verification for this PoC?' || return 1; args+=(--insecure) ;;
        *) error 'Select 1 or 2.'; return 1 ;;
    esac
    confirm 'Install Smootify server and create this client?' || return 1
    provision_first wizard "${args[@]}" --show-qr
}
list_clients() { if ! installed; then printf 'Server is not installed.\n'; return 0; fi; printf 'Configured clients (names only):\n'; backend list; }
add_client() { installed || { error 'Install server first.'; return 1; }; prompt_name || return 1; backend add "$REPLY" --show-qr; }
remove_client() {
    installed || { error 'Install server first.'; return 1; }
    prompt_name || return 1; local name=$REPLY
    confirm "Revoke $name? This briefly restarts the server for all clients." || return 0
    backend revoke "$name"
}
show_client_qr() {
    installed || { error 'Install server first.'; return 1; }
    local listing name state choice index=0 selected=''
    local -a clients=()
    listing=$(backend list) || return 1
    printf 'Select a client to show its QR:\n'
    while IFS=$'\t' read -r name state; do
        [[ -n $name ]] || continue
        # External users have no managed profile and cannot be exported.
        [[ $state == managed ]] || continue
        [[ $name =~ ^[a-z][a-z0-9_-]{0,31}$ ]] || { error 'Invalid client list.'; return 1; }
        clients+=("$name")
        index=$((index + 1))
        printf '%s) %s\n' "$index" "$name"
    done <<< "$listing"
    ((${#clients[@]})) || { printf 'No managed client profiles are available.\n'; return 0; }
    printf '0) Cancel\n'
    read -r -p 'Client number: ' choice || return 0
    [[ $choice != 0 ]] || return 0
    index=0
    for name in "${clients[@]}"; do
        index=$((index + 1))
        [[ $choice != "$index" ]] || selected=$name
    done
    [[ -n $selected ]] || { error 'Select a client number from the list.'; return 1; }
    backend export "$selected" --show-qr
}
uninstall_server() {
    local output unit
    UNINSTALL_COMPLETED=no
    confirm 'Uninstall Smootify, disconnect all clients and permanently delete configuration, keys and profiles?' || return 0
    [[ -f $UPSTREAM ]] || { error 'Pinned upstream installer unavailable.'; return 1; }
    for unit in mqvpn-management.service mqvpn-iperf.service; do
        if [[ -f /etc/systemd/system/$unit ]]; then
            systemctl disable --now "$unit" >/dev/null || { error "Could not stop $unit."; return 1; }
        fi
    done
    if ! output=$(bash "$UPSTREAM" --purge 2>&1); then unset output; error 'Pinned purge failed; output withheld.'; return 1; fi
    unset output
    rm -rf -- /var/lib/mqvpn-management || return 1
    rm -f /etc/systemd/system/mqvpn-management.service /etc/systemd/system/mqvpn-iperf.service /usr/local/libexec/mqvpn-management.py || return 1
    systemctl daemon-reload || return 1
    rm -f -- "$LAUNCHER" /run/lock/mqvpn-provision.lock || return 1
    rm -rf -- /usr/local/lib/smootify || return 1
    UNINSTALL_COMPLETED=yes
    printf 'Smootify removed, including configuration, client profiles and the management command.\n'
}
menu() {
    local choice server_installed
    while :; do
        printf '\nSmootify VPS setup\n'
        if installed; then
            server_installed=yes
            printf 'Server is installed.\n'
            printf '\n1) List clients\n2) Add client\n3) Revoke client\n4) Show client QR\n5) Uninstall Smootify (remove everything)\n0) Exit\n'
        else
            server_installed=no
            printf 'Server is not installed.\n'
            printf '\n1) Install server / create first client\n0) Exit\n'
        fi
        read -r -p 'Choose: ' choice || return 0
        if [[ $choice == 0 ]]; then return 0; fi
        if [[ $server_installed == no ]]; then
            case "$choice" in
                1) wizard || error 'Installation cancelled or failed.' ;;
                *) error 'Unknown choice.' ;;
            esac
        else
            case "$choice" in
                1) list_clients || error 'Could not list clients.' ;;
                2) add_client || error 'Client operation failed.' ;;
                3) remove_client || error 'Client operation failed.' ;;
                4) show_client_qr || error 'QR export failed.' ;;
                5) if uninstall_server; then
                       [[ ${UNINSTALL_COMPLETED:-no} != yes ]] || return 0
                   else error 'Uninstall failed.'; fi ;;
                *) error 'Unknown choice.' ;;
            esac
        fi
        read -r -p 'Press Enter to return to menu... ' _ || return 0
    done
}

main() {
    if [[ ${1:-} == --help || ${1:-} == -h ]]; then
        printf 'Usage: bash setup.sh [--name NAME --server-address HOST (--tls-server-name HOST | --insecure) [--show-qr]]\nNo arguments: interactive Smootify VPS menu (root terminal).\n'
        return 0
    fi
    require_host || return 1
    if (($#)); then provision_first cli "$@"
    else
        [[ -t 0 && -t 1 ]] || { error 'Interactive menu needs a terminal; pass CLI options for automation.'; return 1; }
        menu
    fi
}
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
