# Smootify privacy information

Smootify is an open-source Android app with a self-hosted VPN endpoint. The app
uses the server you configure; this repository does not provide a hosted VPN service.

- Server credentials and app preferences stay in the app's private Android storage.
  Android cloud backup is disabled in the application manifest.
- SIM Switcher reads subscription, signal and network information to make local
  switching decisions. Phone permission is optional for the VPN itself. Shizuku
  is used only for the privileged SIM-selection operations you enable.
- The VPN sends encapsulated network traffic to your configured VPS. Its operator
  can observe connection metadata and any traffic visible at that endpoint; choose
  and administer the server accordingly.
- The QR scanner processes the camera image locally to import a connection profile.
  Camera access is optional; a profile can also be pasted or opened from a file.
- iperf tests exchange test traffic with the configured test endpoint. They consume
  data on the connections selected for the tunnel.
- The app source does not include advertising or an analytics collection service.
  Diagnostic data and test reports are shown locally. Using the share action sends
  the chosen report to the destination you select and may disclose network details.

App removal deletes its private local data. Revoking access to a server is a
separate action in that server's client manager. This document describes this
source tree, not the privacy practices of an independently operated VPS or a
third-party build.
