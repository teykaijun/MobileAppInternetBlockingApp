# Internet Blocker

[![Latest release](https://img.shields.io/github/v/release/teykaijun/MobileAppInternetBlockingApp?label=download&logo=android)](https://github.com/teykaijun/MobileAppInternetBlockingApp/releases/latest)
[![Build](https://github.com/teykaijun/MobileAppInternetBlockingApp/actions/workflows/android.yml/badge.svg)](https://github.com/teykaijun/MobileAppInternetBlockingApp/actions/workflows/android.yml)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/casunoxd)

**A no-root firewall for Android.** Pick the apps that shouldn't go online, flip one switch, and they
lose all internet access — Wi-Fi and mobile data — while everything else on your phone keeps working.

Free, open source, no ads, no tracking. The app doesn't even have permission to use the internet itself.

## Why use it?

- **Stop ads and tracking** in games and apps that don't need a connection to work.
- **Save mobile data and battery** by cutting off apps that sync in the background.
- **Keep offline apps offline** — note-takers, calculators, keyboards, gallery apps.
- **Stay in control** of what leaves your phone, without rooting it or trusting a remote VPN server.

## Download

Get the latest APK from **[Releases](https://github.com/teykaijun/MobileAppInternetBlockingApp/releases/latest)**.

1. Download `InternetBlocker-<version>.apk` on your phone and open it. If Android asks, allow installs
   from your browser or file manager.
   From a computer you can also run `adb install -r InternetBlocker-<version>.apk`.
2. Open **Internet Blocker**, select the apps to block, and turn **Blocking** on.
3. Accept Android's **Connection request** dialog. It appears because the app uses Android's VPN feature,
   locally, as explained below.

Requires **Android 8.0 (Oreo) or newer**.

> Release builds are debug-signed. If an update refuses to install over an older version, uninstall the
> old one first (you'll need to pick your blocked apps again).

## Features

- **Per-app blocking** with search, a "blocked only" filter and an optional system-app view.
- **Instant failure instead of endless loading.** Blocked connections are refused on the spot, so apps
  show "offline" right away. DNS lookups are blocked too.
- **Private by design.** No `INTERNET` permission, no analytics, and no servers: nothing leaves the device.
- **Set and forget.** Blocking comes back after a reboot or app update, and a blocked app stays blocked
  even if you reinstall it.
- **Status notification** showing how many apps are blocked, with a one-tap *Turn off*.
- **Material You** design with dynamic colors, dark mode and a themed icon.

## How it works

Android has no public API that lets a normal app switch off another app's network. The one mechanism
available without root is `VpnService`, so Internet Blocker creates a **local** VPN interface — a tunnel
that goes nowhere:

1. Only the **blocked** apps are routed into the tunnel (`VpnService.Builder.addAllowedApplication`).
   Every other app keeps using the real network and is never touched.
2. The tunnel claims all IPv4 and IPv6 routes and its own DNS servers, so nothing a blocked app sends can
   leak around it.
3. Packets that arrive in the tunnel never leave the phone. Instead of dropping them silently, which would
   leave apps spinning until they time out, the app answers:
   - TCP connection attempts get a **RST**, so the connection fails immediately;
   - UDP datagrams, including DNS queries, get an ICMP **"administratively prohibited"** error;
   - anything else (fragments, ping, multicast) is dropped.

The packet handling thread sleeps until a blocked app actually tries to send something, so battery impact
is negligible.

## Limitations

These come with the no-root `VpnService` approach:

- **Only one VPN can be active at a time.** Starting another VPN app turns blocking off; Internet Blocker
  notices and tells you.
- **Traffic handled by another app on a blocked app's behalf isn't blocked.** The common case is push
  notifications, which arrive through Google Play services rather than the blocked app itself.
- **Core system components are not listed.** Packages with a system user ID (below 10000) are left out on
  purpose, because routing the system's own traffic into the tunnel can break the device.
- **Apps that share a user ID are blocked together**, because Android's allow-list works per user ID.
  The list marks such apps.
- A work profile is a separate user; the app only covers the profile it's installed in.

For protection that can't be undone by opening another VPN app, set Internet Blocker as the
**Always-on VPN** in Android's settings (the app's menu has a shortcut).

## Build from source

Requires JDK 17+ and the Android SDK with platform 36.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

`PacketRejecterTest` builds real IPv4/IPv6 TCP and UDP packets, feeds them through the rejecter and
verifies every reply byte by byte, including independently recomputed checksums.

### Project layout

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

### Releasing

Pushing a tag such as `v1.0.1` makes GitHub Actions build the APK, run the tests and publish a release
with the APK attached ([`.github/workflows/android.yml`](.github/workflows/android.yml)). The release text
starts with [`.github/release-notes.md`](.github/release-notes.md), followed by an automatically
generated list of changes.

## Support the project

Internet Blocker is a free side project. If it saves you data, battery or sanity, a coffee helps keep it
going. Thank you!

<a href="https://buymeacoffee.com/casunoxd"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-casunoxd-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy Me a Coffee"></a>

Bug reports and ideas are welcome in [Issues](https://github.com/teykaijun/MobileAppInternetBlockingApp/issues).
