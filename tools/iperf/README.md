# iperf3 Android arm64

Binario ESnet iperf3 3.18, commit 2a2984488d6de8f7a2d1f5938e03ca7be57e227c.
`build-esnet318.ps1` scarica quel tag, verifica il commit e compila con NDK r28c
per Android API 26, usando la configurazione Android derivata da AOSP
external/iperf3 e abilitando pthread/atomics. Richiede Git e NDK in
`tools/android-ndk-r28c`. Il binario PIE e installato come `libiperf_exec.so`
per essere eseguito dalla directory nativa estratta dell'APK.
Non viene eseguito da una directory scrivibile dall'app.

SHA-256 del binario incluso:
A24945AB36E21E81413FED5096EABC63530B91CE3D108FFF7D21F7D4E41E1158
Licenza ESnet in app/src/main/assets/licenses/iperf3-LICENSE.txt.
