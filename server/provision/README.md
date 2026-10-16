# Install Smootify and connect your phone

This guide takes you from a Debian/Ubuntu VPS to a connected Android app:
**install the server → create a client → scan its QR → connect**.
Server details are imported from the QR; entering them manually is an advanced option.

- [Requirements](#requirements)
- [1. Install the VPS server](#1-install-the-vps-server)
- [2. Install the Android app](#2-install-the-android-app)
- [3. Scan the QR and connect](#3-scan-the-qr-and-connect)
- [4. Select networks and test traffic](#4-select-networks-and-test-traffic)
- [Optional SIM switching and compatibility](#optional-sim-switching-and-compatibility)
- [Manage clients and show a QR again](#manage-clients-and-show-a-qr-again)
- [Troubleshooting](#troubleshooting)
- [Advanced setup and recovery](#advanced-setup-and-recovery)

## Requirements

| Component | Requirement |
| --- | --- |
| VPS | Debian or Ubuntu, systemd, amd64 or arm64, root access |
| Public endpoint | Public IPv4 address or DNS name reaching the VPS |
| Firewall | Incoming UDP 443 allowed in both provider and host firewalls; keep SSH allowed |
| Phone | arm64 Android 8.0 or newer, with Smootify installed |
| QR scanning | Camera access and the VPS terminal visible on another screen |
| SIM Switcher only | Shizuku, phone permission and firmware that allows data-SIM switching |

The VPN, aggregation and iperf tests do **not** require Shizuku or root on the phone.
Use a dedicated VPS for this setup. An existing OpenMPTCProuter server requires
separate review before installing Smootify.

## 1. Install the VPS server

Open an interactive SSH terminal on a computer. Replace `YOUR_VPS_IP` with your
server's address:

```bash
ssh root@YOUR_VPS_IP
```

If you log in as a regular user, run `sudo -i` before continuing.

```bash
apt-get update
apt-get install -y git ca-certificates

git clone https://github.com/Brazzo978/smootify.git
cd smootify
bash server/provision/setup.sh
```

The no-argument setup command opens a Bash menu. On a new installation, choose
**1) Install server / create first client**, then:

1. Enter the VPS public IP or DNS name. Use the same reachable address the phone
   will use, rather than an internal/private server address.
2. Enter a client name such as `phone1`. Names must start with a lowercase letter
   and contain at most 32 lowercase letters, digits, underscores or hyphens.
3. Choose a TLS mode. If you already have a trusted certificate installed, choose
   **Verify CA-trusted certificate** and enter its DNS name. For a first PoC
   without one, choose **Allow insecure PoC connection** and confirm it. This
   disables certificate verification; see [TLS options](#tls-options) for the
   verified setup.
4. Confirm installation and, if prompted, allow installation of missing distro
   packages.
5. Wait for provisioning to complete and for the client QR to appear. Leave the
   terminal visible for the phone to scan.

Setup downloads pinned MQVPN **v0.17.0**, verifies the release SHA-256 manifest,
configures forwarding/NAT, installs the server and its management/iperf services,
and creates a separate client key. Fresh server configurations and Android
profiles use **MTU 1280**.

The QR contains the server address, UDP port, personal key, TLS mode, MTU and
internal iperf/control endpoints. It is a credential: keep each client's QR private.

## 2. Install the Android app

The app is **Smootify**, package **`com.codmanu.smootify`**, for arm64 Android 8.0+.
No APK release has been published in this repository yet; use the
[Android build guide](../../docs/ANDROID_BUILD.md) to build one.

If you have already been given a Smootify test APK, open it on the phone, allow
installation from that source when prompted, and install it. A later APK update
must use the same signing certificate. The original MQVPN PoC and Smootify use
separate packages, profiles and permissions.

## 3. Scan the QR and connect

### First launch: guided setup

1. Open Smootify. Choose **Seamless VPN**, or **Both features** if you also want
   to configure SIM Switcher, and tap **Continue**.
2. On **VPN server**, **Open VPS setup guide** opens this page. With the server
   already installed, tap **Scan server QR** and grant camera access.
3. Scan the QR from your VPS terminal. Review **Profile ready** and the server
   endpoint. The key stays hidden in the preview; the scan is not saved yet.
4. Tap **Continue**. Select the networks you want to use, such as Wi-Fi and mobile
   data, and continue through compatibility and permissions.
5. On **Summary**, tap **Save configuration**. This saves the profile without
   starting the VPN or automatically enabling SIM switching.
6. Open the **Seamless VPN** tab, tap **Connect**, and approve Android's VPN
   connection request. Wait until the dashboard says **Connected**.
7. Open a website to check connectivity, then return to Smootify for the speed test.

No server fields need to be typed in this flow. **Advanced: enter details manually**
is available when a QR cannot be used.

### Import a profile after onboarding

Open **Seamless VPN → Settings → Import profile**. Tap **Scan QR**, scan the VPS
QR, review the preview and tap **Import profile**, then **Done**. Return to the
VPN dashboard and tap **Connect**. You can also reopen **Guided setup** from
VPN settings.

To move to a different client/server profile, disconnect the VPN first, import
the new profile and connect again. Closing or cancelling the scanner does not
replace the saved profile.

## 4. Select networks and test traffic

### Choose connections

Open **Seamless VPN → Settings → Networks**. Enable the connection types you want
Smootify to use and allow individual interfaces under **Detected networks**.
Turn on **Use multiple networks together** for aggregation and multipath failover.

Start with Wi-Fi and mobile data both connected. Allowing a network does not mean
it is currently active: check the tunnel's active paths and per-connection traffic
while data is flowing. Two installed SIMs are not proof of simultaneous Internet
paths; modem, firmware and operator support determine that behavior.

Use **Save for later** when disconnected, or **Apply and reconnect** to use changed
selections immediately. Reconnecting interrupts the current tunnel session.

### Choose a tunnel mode

Under **VPN settings → Tunnel mode**, **Speed** selects WLB and **Stability**
selects MinRTT. These are client-side presets. Save them with **Save client**, or
use **Apply and reconnect** when ready to reconnect with the new settings.

Personal QR profiles do not include the global server administration key. They
can connect and run tests but cannot change the shared VPS scheduler or congestion
control. Client and server settings can differ.

### Run an iperf test

While the VPN is **Connected**, tap **Run speed test** on the VPN dashboard.
Choose TCP/UDP and the direction offered on the test screen, then tap
**Start speed test**. The imported profile supplies **10.88.0.1:5201** automatically;
no public iperf port needs to be opened.

The gauge shows the test rate, and **Connection contributions** shows per-path
traffic. Results include received throughput and, for UDP, loss and jitter.
**Advanced options** lets you change duration, parallel streams and UDP load.
Tests transfer real data and use the selected mobile connections' data allowances.

## Optional SIM switching and compatibility

### SIM Switcher

The **SIM Switcher** tab can run independently of the VPN:

1. Open its settings, install/open Shizuku and start it using Shizuku's instructions.
2. Tap **Authorize Smootify** when available and grant **Allow SIM access**.
3. Check that both SIMs and their signal/network status are visible. Use
   **Refresh radio and Shizuku status** after changing permissions.
4. Enable **Automatic data SIM selection**. Enable **Active even without VPN**
   if you want switching to continue while the VPN is disconnected. If prompted,
   allow notifications; use **Start independent monitor** if it is not running.
5. Review the hysteresis, stability and idle-pause settings before changing their
   defaults. Checks pause while the phone is idle.

SIM switching changes the preferred data SIM. VPN continuity also requires the
VPN to be connected; changing SIMs alone does not provide a tunnel or guarantee
an interruption-free handover.

### Battery and background operation

In **Battery and background**, tap **Allow unrestricted battery use** and follow
Android's prompt. **Open app battery settings** provides a manual route.
On Samsung, also check **Open Samsung Never sleeping apps** and add Smootify if
that setting is available. Menu names vary by firmware. This does not disable the
SIM monitor's configured idle pause or keep the screen awake.

### Wireless Android Auto

In **VPN settings → Android Auto**, enable **Exclude Android Auto from VPN** and
save. If connected, use **Save and reconnect Smootify**, then reconnect wireless
Android Auto to the car. Android's **Block connections without VPN** setting can
also block an excluded app. Compatibility still needs validation in the vehicle.

## Manage clients and show a QR again

Run from any directory as root:

```bash
smootify-setup
```

The installed server menu is:

```text
1) List clients
2) Add client
3) Revoke client
4) Show client QR
5) Uninstall Smootify (remove everything)
0) Exit
```

- **List clients** shows configured names and whether they are managed or external.
- **Add client** asks for a new name, creates a separate key/profile and shows its QR.
  Create a client for each phone rather than copying another phone's credentials.
- **Revoke client** asks for the client's name and confirmation, then removes its access.
- **Show client QR** lists managed clients by number. Select the number to show
  that client's existing QR, or select **0** to cancel. External users have no
  managed profile and are not offered for QR export.
- **Uninstall Smootify** has one confirmation and removes the server, companion
  services, configuration, keys, profiles and installed management command.
  Reinstall by running `bash server/provision/setup.sh` from the original checkout.

Adding or revoking a client restarts the shared server and briefly interrupts all
connected clients. Up to 64 users can be configured. Externally managed users can
be listed; this manager does not revoke them.

Installed assets are in `/usr/local/lib/smootify/server`; the command is
`/usr/local/sbin/smootify-setup`. Profiles are in `/etc/mqvpn/profiles` with directory
mode 0700 and file mode 0600. The server binary is `/usr/local/bin/mqvpn`.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| `smootify-setup: command not found` | Complete setup first. Then try `/usr/local/sbin/smootify-setup`; if it exists, check the root shell's PATH. |
| Interactive menu says it needs a terminal | Use an interactive SSH session, or `ssh -t` for a remote command. |
| QR is hard to scan | Show it on another screen, enlarge the terminal window, keep the entire QR visible and use **Show client QR** to display it again. |
| Scanning shows a preview but nothing connects | Finish with **Save configuration** in the wizard, or **Import profile** in the importer, then tap **Connect** on the VPN dashboard. |
| VPN does not reach Connected | Check the profile's public address/TLS mode and incoming UDP 443 in both provider and host firewalls. Check server service health below. |
| A previous test profile no longer connects | A clean reinstall generates new keys. Show the new client's QR and import it again. |
| Connected, but iperf fails | Check `mqvpn-iperf` and the imported 10.88.0.1:5201 endpoint. Keep iperf internal to the tunnel. |
| Only one path carries traffic | Check **Networks**, both connection types, individual interface selection and **Use multiple networks together**. Verify actual network availability under load. |
| Server tuning is unavailable in the app | Personal client profiles intentionally do not include shared server administration access. |
| SIM switching does not start | Check Shizuku, Smootify authorization, phone permission and compatible firmware. VPN connectivity itself does not require Shizuku. |
| Background behavior stops | Review Android/Samsung battery settings, notifications, Shizuku status and the SIM monitor's intentional idle pause. |

Check the three services and their logs:

```bash
systemctl is-active mqvpn-server mqvpn-management mqvpn-iperf
ss -lntup
journalctl -u mqvpn-server -u mqvpn-management -u mqvpn-iperf -n 60 --no-pager
```

A healthy install reports all three services as `active`. Only UDP 443 needs public
access for the tunnel. The internal endpoints are:

| Endpoint | Purpose | Exposure |
| --- | --- | --- |
| UDP 443 | MQVPN tunnel | Public |
| TCP 127.0.0.1:9090 | Native control | Loopback only |
| TCP 10.88.0.1:9443 | Authenticated management | Tunnel only |
| TCP/UDP 10.88.0.1:5201 | iperf3 | Tunnel only |

## Advanced setup and recovery

### TLS options

For verified TLS, use a DNS name resolving to the VPS and place the CA-trusted
certificate and private key at `/etc/mqvpn/server.crt` and `/etc/mqvpn/server.key`
before installation. Choose **Verify CA-trusted certificate** and enter the
matching DNS name. The installer verifies the certificate's hostname and trust
chain; it does not obtain or renew a certificate for you.

For the explicit PoC option, setup generates a self-signed certificate and exports
a profile with certificate verification disabled. In automation, this is selected
with `--insecure`.

### Non-interactive installation

Install prerequisites first, then run the same Bash entry point with arguments:

```bash
apt-get update
env DEBIAN_FRONTEND=noninteractive apt-get install -y \
  git ca-certificates curl bash python3 openssl qrencode iperf3 \
  iptables iproute2 procps tar coreutils

bash server/provision/setup.sh \
  --name phone1 --server-address 203.0.113.10 --insecure
```

Replace the example IP with your public VPS address. For verified TLS, replace
`--insecure` with `--tls-server-name vpn.example.com`. For an interactive terminal,
add `--show-qr` to display the QR immediately. For unattended setup, omit it and
later run `smootify-setup` to display the QR. Python 3 is an internal dependency;
normal installation and client management use the Bash menu.

### Recovery and validation

The fixed IPv4 tunnel subnet is **10.88.0.0/24**. Existing configurations on another
subnet are rejected before companion services are installed. Explicit config
changes reset the previous systemd start counter so administrative restarts do
not exhaust its budget; automatic crash-loop protection remains enabled.

A failed client update restores the previous configuration where possible. If
`server.conf.provision-backup` remains, inspect service health and run:

```bash
python3 /usr/local/lib/smootify/server/provision/mqvpn_provision.py recover
```

Do not delete that pending backup just to force another update.

A clean Debian 13 installation, live client add/revoke, numbered QR selection,
authenticated scheduler changes and encrypted TCP/UDP transfer with an isolated
Linux client have been verified. The tester has also confirmed optical QR import
and connection from the Android app. Automated tests cover menu dispatch,
cancellation, client isolation, config preservation, rollback and authentication.
These results do not prove simultaneous dual-SIM data support on other devices.
