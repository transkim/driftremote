# Driftremote

A phone remote for Android TV and Google TV over your home Wi-Fi. No ADB, no developer mode, and nothing to install on the TV: it talks to the remote service that is already built into every Android TV / Google TV (Hisense, Sony, TCL, Philips, Xiaomi, Chromecast with Google TV, and others).

## What it does

- Finds TVs on the Wi-Fi automatically (mDNS `_androidtvremote2._tcp`), or add one by IP address
- Pairs once with the 6-character code the TV shows on screen
- D-pad with OK, or a swipe touchpad
- Back, Home, Menu, Power, Input, TV settings
- Volume up/down/mute, plus the phone's own volume buttons control the TV
- Play/pause, rewind, fast forward
- Keyboard: type into the TV's search boxes from the phone
- More buttons: number pad, channel up/down, guide, info, subtitles, color keys, recent apps
- App shortcuts (YouTube, Netflix, Prime Video, Disney+, Spotify, Play Store) and your own, by package name
- Long press on any button sends a real long press (e.g. hold OK for a context menu)

## Install

1. Open the latest green run under **Actions**, download **Driftremote-Android-apk**, and unzip it.
2. Copy `Driftremote.apk` to your phone and open it. Allow "Install unknown apps" for your file manager or browser when asked.
3. Connect the phone to the same Wi-Fi as the TV, open Driftremote, tap your TV, and type the code shown on the TV.

Builds are signed with a fixed key that lives in this repo, so new builds install over old ones. Use your own keystore before publishing anywhere.

## How it works

The TV runs Google's "Android TV Remote Service" (protocol v2):

| Port | Purpose |
| --- | --- |
| 6467 | Pairing over TLS. The phone presents a self-signed certificate; the TV shows a code; the phone proves it saw the code by sending SHA-256(client modulus, client exponent, server modulus, server exponent, code[2:]). |
| 6466 | Remote control over TLS with the paired certificate: key presses, text input, app links, volume and power state, pings. |

Messages are protobuf, length-prefixed with a varint. The app has its own tiny protobuf encoder (`core/Pb.java`) and certificate writer (`core/Identity.java`), so it has no dependencies.

Protocol references: [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) (Python), [louis49/androidtv-remote](https://github.com/louis49/androidtv-remote) (Node), and the [protocol notes](https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2)).

`android/coretest/FakeTvTest.java` plays the TV side (pairing and remote) and checks the pairing hash against the Python algorithm; CI runs it on every push.

## Troubleshooting

- **TV not listed:** check both are on the same Wi-Fi (not a guest network), then add the TV by IP (TV: Settings > Network & Internet).
- **Pairing keeps failing:** on the TV open Settings > Apps > See all apps > Show system apps > Android TV Remote Service > Clear data, then pair again.
- **Power on doesn't wake the TV:** turn on the TV's network standby / "Wake on Wi-Fi" setting (Hisense: Settings > Network > Network standby).
