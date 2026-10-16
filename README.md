# Smootify

**An open-source Android app for connection aggregation, VPN failover and automatic
SIM switching, based on [MQVPN](https://github.com/mp0rta/mqvpn).**

Smootify connects your phone to your own VPS and can use available Wi-Fi, mobile
and Ethernet networks. The aim is an open-source alternative to Speedify.
Smootify is independent and is not affiliated with Speedify or the upstream MQVPN project.

**[Installation and first connection guide](server/provision/README.md)** —
set up the VPS, scan your client QR and connect from Android.

## Two independent features

- **SIM Switcher** monitors SIM signal and network conditions, then changes the
  preferred data SIM using stability timers and hysteresis. It can work with the
  VPN disconnected. Switching without root requires Shizuku and compatible firmware.
- **Seamless VPN** provides failover and aggregation across selected networks.
  It includes Speed/WLB and Stability/MinRTT presets, iperf TCP/UDP tests,
  connection graphs and QR profile import. The VPN does not require Shizuku.

Android tunnel MTU is **1280**. Two installed SIMs do not guarantee two simultaneous
Internet paths: this depends on the modem, firmware and operator. Start with
Wi-Fi plus mobile data to check the VPN; SIM switching and dual-SIM aggregation
are different capabilities.

## Quick start

### 1. Set up the VPS

Use a Debian or Ubuntu VPS with systemd, amd64 or arm64. Connect over SSH as root
(or run `sudo -i`) and allow incoming **UDP 443** in the provider and host firewalls.
Keep SSH access allowed.

```bash
apt-get update
apt-get install -y git ca-certificates

git clone https://github.com/Brazzo978/smootify.git
cd smootify
bash server/provision/setup.sh
```

Choose **Install server / create first client**, enter your public IP or DNS name,
and give the first client a name such as `phone1`. The wizard offers to install
missing packages and asks which TLS mode to use. For a first PoC without a trusted
certificate, the explicit **Allow insecure PoC connection** option uses a
self-signed certificate with certificate verification disabled. See the
[TLS instructions](server/provision/README.md#tls-options) for verified TLS.

Setup installs the server, creates a personal client key and displays its QR.
Leave that terminal visible on a computer so the phone can scan it.

### 2. Install the Android app

Smootify supports **arm64 Android 8.0 or newer**. The package is
**`com.codmanu.smootify`**.

No APK release has been published in this repository yet. Build an APK using the
[Android build guide](docs/ANDROID_BUILD.md). If you already have a Smootify APK
for testing, open it on the phone and allow installation from that source when
Android asks. Updates must be signed with the same certificate as the installed app.

### 3. Scan and connect

1. Open Smootify and choose **Seamless VPN** or **Both features** in guided setup.
2. At **VPN server**, tap **Scan server QR**, allow camera access and scan the
   QR in your VPS terminal. Review the profile preview; manual fields are under
   **Advanced: enter details manually**.
3. Continue through network selection and compatibility, then tap
   **Save configuration**. Saving does not connect automatically.
4. Open **Seamless VPN**, tap **Connect** and approve Android's VPN connection
   request. Wait for **Connected**.
5. Open a website, then use **Run speed test** to check TCP/UDP traffic to your VPS.

Already finished onboarding? Open **Seamless VPN → Settings → Import profile**,
tap **Scan QR**, review the preview and tap **Import profile**. Connect from the
VPN dashboard afterward.

The [full guide](server/provision/README.md) covers network selection, iperf tests,
SIM Switcher, Samsung battery settings, Android Auto and troubleshooting.

## Manage the VPS

Reopen the menu from any directory:

```bash
smootify-setup
```

```text
1) List clients
2) Add client
3) Revoke client
4) Show client QR
5) Uninstall Smootify (remove everything)
0) Exit
```

**Show client QR** displays a numbered client list: select the client's number,
or `0` to cancel. Create a separate client for each phone. Personal profiles do
not contain the global administration key and cannot change shared server settings.
Adding or revoking a client currently restarts the shared server and briefly
interrupts connected clients.

**Uninstall Smootify** removes the server, companion services, configuration,
keys, profiles and installed management command after confirmation. To reinstall,
run the setup script from the original checkout again.

## Source and build

The Android app and SDK live under [`upstream/android`](upstream/android).
The repository includes the arm64 JNI engine and iperf executable, their rebuild
instructions and license texts. Android SDKs and downloaded native toolchains
are not committed.

- [Android build instructions](docs/ANDROID_BUILD.md)
- [Release signing and Google registration](docs/ANDROID_SIGNING.md)
- [VPS setup and connection guide](server/provision/README.md)

## Project status

Smootify is a proof of concept under active development. A clean Debian 13 server
installation, client management, QR rendering and TCP/UDP traffic through an
isolated Linux client have been verified. Optical QR scanning, profile import and
connection from the Android app have also been confirmed by the tester.

Wi-Fi plus mobile tunneling and data-SIM handover have been tested on Samsung
hardware. Handover delay depends on the device and network conditions;
simultaneous dual-SIM Internet remains hardware-dependent. Wireless Android Auto
compatibility still needs checks in the actual vehicle. These results do not
establish support for every phone, modem or operator.

## License and attribution

Apache-2.0 for the first-party project, with retained MQVPN copyright notices.
See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](third_party).
Android assets also carry the Shizuku, iperf3 and ZXing licenses. Keys and
certificates under `upstream/tests/certs` are public test fixtures only.
