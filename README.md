# Smootify

**An open-source project for Android connection aggregation and seamless failover,
based on [MQVPN](https://github.com/mp0rta/mqvpn).**

The aim is an open-source alternative to Speedify: keep traffic moving across
available Wi-Fi, mobile and Ethernet connections, with a self-hosted VPS endpoint.
Smootify is independent and is not affiliated with Speedify or the upstream MQVPN project.

## Two independent features

- **SIM Switcher:** monitors the available SIMs and changes the preferred data
  SIM using signal/network conditions, stability timers and hysteresis. It can run
  independently of the VPN. No-root switching requires Shizuku and compatible firmware.
- **Seamless VPN:** uses the MQVPN tunnel for connection failover and aggregation
  across the selected available networks. Includes Speed/WLB and Stability/MinRTT
  presets, advanced controls, iperf TCP/UDP tests, live graphs and QR profile import.

Android tunnel MTU is **1280**. Simultaneous mobile data on two SIMs depends on
the modem, firmware and operator. Two configured SIMs do not guarantee two active
Internet paths. Hardware support must be verified with simultaneous traffic;
SIM switching and bandwidth aggregation are different features.

## Set up your VPS

On a new Debian/Ubuntu VPS (amd64 or arm64), open an interactive SSH terminal as
root. Allow incoming **UDP 443** in the provider and host firewalls.

```bash
apt-get update
apt-get install -y git ca-certificates

git clone https://github.com/Brazzo978/smootify.git
cd smootify
bash server/provision/setup.sh
```

The Bash menu guides installation, offers to install missing distro packages,
asks for the public endpoint and TLS mode, then creates the first private client
profile and displays its QR. Scan it with the Android app's profile importer.

After installation, reopen the menu from any directory:

```bash
smootify-setup
```

The menu shows configured clients and supports adding/revoking clients, displaying
a client's QR, uninstalling while keeping configuration, or explicitly deleting
configuration and keys. Personal profiles contain separate keys, not the global
administration key. The current add/revoke workflow restarts the shared server
and briefly interrupts connected clients.

See the **[VPS guide](server/provision/README.md)** for trusted TLS, automation,
firewall details, recovery and troubleshooting.

## Android source and build

The Android app is **Smootify**, package **`com.codmanu.smootify`**. It builds on
the PoC8 tunnel/radio baseline with a simpler two-feature interface.
Guided setup links to the VPS guide and scans the server QR before reviewing the
connection. Manual server details are available under Advanced setup.
The app and SDK sources live under [`upstream/android`](upstream/android).

Follow the **[Android build guide](docs/ANDROID_BUILD.md)**. The repository includes
the arm64 JNI engine and iperf executable plus their source/rebuild instructions
and license texts; no Android SDK or downloaded native toolchains are committed.

```bash
cd upstream/android
./gradlew :app:assembleDebug
```

## Project status

This is a proof of concept under active development. Wi-Fi plus mobile tunneling
and data-SIM handover have been tested on Samsung hardware. The behavior and
handover delay depend on network conditions and the device; simultaneous dual-SIM
Internet remains hardware-dependent. Wireless Android Auto compatibility needs
further checks in the actual vehicle.

The VPS manager has automated tests; a fresh-VPS installation and optical QR scan
still need end-to-end validation. Do not treat unit tests as proof of a particular
phone/modem's dual-data capability.

## License and attribution

Apache-2.0 for the first-party project, with retained MQVPN copyright notices.
See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](third_party).
Android assets also carry the Shizuku, iperf3 and ZXing licenses. Keys and
certificates under `upstream/tests/certs` are public test fixtures only.
