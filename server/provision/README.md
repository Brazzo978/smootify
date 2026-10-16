# Smootify VPS manager

The entry point is **Bash**. It presents an interactive menu on every no-argument
run; Python 3 is used internally for safe, atomic client/profile management.
No Python commands are needed for normal administration.

Use a new **Debian or Ubuntu**, amd64 or arm64 VPS with systemd. Open an
interactive SSH terminal as root (`sudo -i` first if you log in as a regular user).
Allow inbound **UDP 443**, keeping SSH allowed. Then:

```bash
apt-get update
apt-get install -y git ca-certificates
git clone https://github.com/Brazzo978/smootify.git
cd smootify
bash server/provision/setup.sh
```

The install wizard asks for a public IP or DNS name, the first client name and TLS
mode. Missing distro packages can be installed after confirmation. Setup downloads
the pinned upstream MQVPN **v0.17.0** release and verifies its SHA-256 manifest,
configures forwarding/NAT, installs management and iperf, and creates a separate
client key. Its QR imports into the Smootify Android app, with **MTU 1280**. Fresh server configurations also use MTU 1280.

## The menu

After installation, run the same script again, or simply:

```bash
smootify-setup
```

The command works outside the checkout: installer assets are retained in
`/usr/local/lib/smootify/server` and the launcher in `/usr/local/sbin`.

- Configured client names are listed when the menu opens.
- **Add client** creates a private profile and shows its QR.
- **Revoke client** removes the selected managed client's access, after confirmation.
- **Show client QR** re-displays an existing client's QR without changing its key.
- **Uninstall (keep config)** removes the server and companion services while
  preserving configuration, keys and profiles for a later reinstall.
- **Uninstall and purge config** also deletes configuration and profiles after a
  separate confirmation. The menu itself remains available to reinstall.

Adding or revoking users currently restarts the shared server and briefly
interrupts all clients. Explicit configuration changes reset the previous systemd
start counter so repeated client edits do not exhaust its budget; automatic
crash-loop protection remains enabled. Native MQVPN supports up to 64 configured users. Existing
externally managed users can be listed; this manager does not revoke them.

QRs contain personal credentials: scan privately and close the terminal when
finished. JSON profiles live in `/etc/mqvpn/profiles` (directory 0700, files 0600).
The global administration key is never exported in personal profiles; normal
client profiles cannot change global server parameters.

## TLS

For a trusted certificate, place the CA-trusted certificate and private key at
`/etc/mqvpn/server.crt` and `/etc/mqvpn/server.key` before installation, choose
verified TLS in the wizard and enter the matching DNS name. The profile provisioner
checks the certificate's hostname and trust chain.

For an explicit proof of concept, the wizard can allow the generated self-signed
certificate after confirmation. This corresponds to `--insecure` below.

## Automation

The previous non-interactive bootstrap flags remain available. Install the
prerequisites first:

```bash
apt-get update
env DEBIAN_FRONTEND=noninteractive apt-get install -y \
  git ca-certificates curl bash python3 openssl qrencode iperf3 \
  iptables iproute2 procps tar coreutils

bash server/provision/setup.sh \
  --name phone1 --server-address 203.0.113.10 --insecure --show-qr
```

Replace the example IP with your public VPS address. For verified TLS use
`--tls-server-name vpn.example.com` instead of `--insecure`. QR display requires
a terminal: omit `--show-qr` for unattended setup, then open the menu over SSH to
show the QR. Do not pipe QR output to shared logs. For a remote command use `ssh -t`.

## Network and health

The fixed tunnel subnet is **10.88.0.0/24**. Existing configurations on another
subnet are rejected before companions are installed. This is a separate setup
from OpenMPTCProuter; review an existing OMR VPS before installing anything here.

| Endpoint | Purpose | Exposure |
| --- | --- | --- |
| UDP 443 | MQVPN tunnel | Public; allow inbound |
| TCP 127.0.0.1:9090 | Native control | Loopback only |
| TCP 10.88.0.1:9443 | Authenticated management | Tunnel only |
| TCP/UDP 10.88.0.1:5201 | iperf3 | Tunnel only |

Do not open the internal ports on the public firewall. Check service health with:

```bash
systemctl is-active mqvpn-server mqvpn-management mqvpn-iperf
ss -lntup
journalctl -u mqvpn-server -u mqvpn-management -u mqvpn-iperf -n 60 --no-pager
```

If the app cannot connect, check the public endpoint, UDP firewall and TLS mode.
If it connects but the speed test fails, check `mqvpn-iperf` and the profile's
10.88.0.1:5201 endpoint.

A failed client update restores the previous config where possible. A retained
`server.conf.provision-backup` means rollback needs attention: inspect service
status, then run `python3 /usr/local/lib/smootify/server/provision/mqvpn_provision.py recover`.
Do not delete the backup just to force another change.

Local/CI tests validate menu dispatch, cancellation, client isolation, config
preservation, rollback and management authentication. They do not replace a
fresh-VPS installation and optical QR import on the actual phone.
