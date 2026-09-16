# Internet Blocker

An Android app that cuts internet access for the apps you pick. Everything else on the phone keeps
working normally, and no root is required.

Select apps in the list, flip **Blocking** on, and those apps can no longer reach Wi-Fi or mobile
data — their connections fail immediately instead of hanging.

## How it works

Android has no public API to switch off another app's network. The one mechanism a normal app can
use is `VpnService`, so the app creates a **local** VPN interface — a tunnel that goes nowhere:

1. `VpnService.Builder.addAllowedApplication()` is called for each **blocked** app, so only those
   apps are routed into the tunnel. Every other app keeps using the real network untouched.
2. The tunnel claims all IPv4 and IPv6 routes plus its own DNS servers, so nothing a blocked app
   sends can leak around it, DNS lookups included.
3. Packets that arrive in the tunnel never leave the device. Instead of dropping them silently
   (which would leave apps spinning until they time out), the app answers:
   - TCP connection attempts get a **RST**, so `connect()` fails right away;
   - UDP datagrams, including DNS queries, get an **ICMP "administratively prohibited"** error;
   - anything else (fragments, ICMP, multicast) is dropped.

The app itself does **not** request the `INTERNET` permission — it has no way to send your data
anywhere, and you can verify that in `AndroidManifest.xml`.

## Features

- Per-app blocking with search, an "only blocked apps" filter, and an optional system-app filter.
- Blocking survives reboots and app updates (`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`).
- Rebuilds the tunnel when a blocked app is reinstalled and gets a new user ID.
- A status notification with a "Turn off" action, and a shortcut to the system's Always-on VPN
  settings.
- Turns itself off cleanly if another VPN app takes over, and says so in the UI.
- Material 3 UI with dynamic color and dark mode.

## Install

Download the APK from the [Releases](../../releases) page and install it:

```bash
adb install -r InternetBlocker-v1.0.0.apk
```

Or copy the APK to the phone, open it, and allow installs from that source. Released APKs are
debug-signed, which is fine for personal use; to publish your own build, sign it with your own key.

On first use Android shows its own "connection request" dialog — that is the system asking whether
this app may run a VPN. Blocking only works after you accept it.

## Build from source

Requires JDK 17+ and the Android SDK (platform 36). Then:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

`PacketRejecterTest` builds real IPv4/IPv6 TCP and UDP packets, feeds them through the rejecter and
verifies the replies byte by byte, including independently recomputed checksums.

## Limitations

These are inherent to the no-root `VpnService` approach:

- **Only one VPN can be active at a time.** Starting another VPN app switches blocking off; the app
  detects this and tells you.
- **Traffic that another app performs on a blocked app's behalf is not blocked.** The clearest
  example is push messaging: Firebase Cloud Messaging notifications arrive over Google Play
  services' own connection, not the blocked app's.
- **System UIDs are not listed.** Core system packages (user ID below 10000) are deliberately left
  out, because routing the system's own traffic into the tunnel can break the device.
- **Apps sharing a user ID are blocked together.** Android's allow-list works per user ID, so the
  list marks such apps.
- A work profile is a separate user; the app only covers the profile it is installed in.

For blocking that cannot be bypassed by opening another VPN app, enable this app as **Always-on
VPN** in Android's VPN settings (the overflow menu has a shortcut).

## Project layout

```
app/src/main/java/io/github/teykaijun/netblocker/
├── MainActivity.kt              # Compose host, VPN consent and notification permission flows
├── data/
│   ├── BlockerSettings.kt       # persisted selection + on/off state, exposed as StateFlows
│   └── InstalledApps.kt         # lists apps that request INTERNET
├── ui/                          # Material 3 screen, view model, icon loading, theme
└── vpn/
    ├── BlockerVpnService.kt     # builds the tunnel, foreground notification, lifecycle
    ├── PacketLoop.kt            # reads the tunnel on its own thread, writes replies
    ├── PacketRejecter.kt        # pure packet parsing / RST + ICMP construction (unit tested)
    ├── TunnelState.kt           # state shared with the UI
    └── BootReceiver.kt          # restores blocking after reboot or update
```

## Releases

Pushing a tag such as `v1.0.0` makes GitHub Actions build the APK, run the tests and publish a
release with the APK attached (`.github/workflows/android.yml`).
