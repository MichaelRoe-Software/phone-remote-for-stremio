# Stremio App: phone remote for any Stremio install

An Android remote that pairs with a Stremio install by scanning a QR code, and
the matching changes to Stremio's open-source code that make the QR code and the
remote channel exist.

> **Name and trademark.** "Stremio" is a registered trademark of Smart Code OOD.
> "Stremio App" is only for personal use. Pick a neutral name (and icon) before
> any public release, store listing or redistribution of these builds.

## How it fits together

```
 phone (this app)  ──HTTP + X-Token──▶  relay :11471  ──WebSocket──▶  Stremio web UI
   d-pad, search,                       (stremio-service,             runs the command in-page:
   trackpad, volume                      or standalone)                focus ring, router, player
```

| Piece | Where | What it does |
|---|---|---|
| Settings → **Phone remote** | `stremio-fork/stremio-web` (branch `phone-remote`) | Shows the QR code `{"name","url","token"}`, the status and a *New pairing code* button. |
| In-page remote service | `stremio-web/src/services/PhoneRemote/` | Connects to the relay and runs commands: spatial d-pad navigation with a purple focus ring, OK/Back, routes, search, typing into the focused text box, a virtual trackpad cursor, and the player's own play/pause/seek/volume actions. |
| Relay | `stremio-fork/stremio-service/remote-relay` (branch `phone-remote`) | Small Rust (axum) server on port **11471**. stremio-service starts it next to server.js (turn off with `--no-phone-remote`); it also builds as a standalone binary / Docker image. Keeps a random per-install token in the user config folder (`%APPDATA%\stremio-remote-relay\token` on Windows). |
| This app | `stremio-app/` | Kotlin, package `za.michael.stremioapp`, display name "Stremio App". |

Security model: the token and the page channel (`/pairing`, `/page`) are only
served to loopback clients with an allowed web UI origin (web.stremio.com,
localhost builds, plus `RELAY_ALLOWED_ORIGINS`). Phone commands need the token.
*New pairing code* rotates it; paired phones then need to scan again.

The relay's phone API is the same as the old Linux HTPC backend
(`/key/{name}`, `/search`, `/type`, `/mouse`, `/volume/{action}`, `/health`,
token in `X-Token`), so the app controls both.

## Pairing

1. On the computer, open Stremio → **Settings → Phone remote** (the last section).
   It should say *Ready: scan the code with the phone app*. If it says the remote
   service isn't running, start Stremio Service (or the relay).
2. Phone on the same Wi-Fi. Open **Stremio App**. On first launch it opens
   *Devices*; otherwise use Back → *Manage devices*.
3. Tap **Scan QR code**, allow the camera, point it at the code.
4. Give the device a name (it's prefilled from the computer name) and save.
   You're on the remote, connected to it.

The app remembers every device and reconnects to the last one on launch.
Tap the device name at the top (or Back → *Switch device*) to switch.
In *Devices*, tap a device to control it, hold it to **rename** or **delete**.
Scanning a code for an address you already have just refreshes its token and
keeps your name for it.

**Add manually** takes a name, an address and a token. Use it for the Linux
HTPC backend (`http://192.168.1.107:8080` with its token from
`~/stremio-remote/token`), or paste a whole pairing code / `?t=` link into the
address field. A pairing link `stremioapp://pair?data=<url-encoded JSON>` does
the same as scanning (handy with `adb shell am start -d ...`).

## Remote controls

D-pad moves the focus ring, OK clicks (on a focused text box it submits),
Back closes a popup or goes back. Home / Discover / Library / Search pg open
those pages. The search box + **Search** searches; **Type text only** types into
the text box that has focus; **⌫ Delete** deletes a character. **Trackpad** moves
an on-screen cursor (tap = click, two fingers = scroll, speed slider remembered).
While a video plays, the d-pad works like a TV remote: left/right seek, up/down
volume, OK play/pause. Volume buttons and the phone's volume keys change the
player volume (Stremio's own player volume, not Windows' master volume). TV
on/off only appears for the HTPC backend.

## Running it on Dad's Windows laptop

Not built or tested on Windows yet; this is the intended path:

1. Build the stremio-service fork on Windows (`cargo build --release`, see its
   README) and install/run it. It runs server.js and the relay on port 11471.
   Allow port 11471 on *private* networks in Windows Firewall when asked.
2. Serve the stremio-web fork somewhere the laptop can open it (for example
   `pnpm build` and host `build/`, or the Docker `web` image below) and use that
   instead of web.stremio.com. The official desktop app loads the official web
   UI, which doesn't have the Phone remote section.
3. Open Settings → Phone remote and pair as above.

## Docker demo (verified)

`stremio-fork/stremio-web/phone-remote-demo/docker-compose.yml` runs the official
`stremio/server` image (unmodified), this web UI fork and the relay:

```bash
cd C:/Users/micha/PhpstormProjects/stremio-fork/stremio-web/phone-remote-demo
```

```bash
cp .env.example .env
```

Set `RELAY_PUBLIC_URL` in `.env` to this PC's LAN IP (`http://192.168.1.103:11471`
here), then:

```bash
docker compose up -d --build
```

Open http://localhost:8090/#/settings → Phone remote and scan the code. In the
demo `RELAY_TRUST_ANY_PEER=1` is set because the browser reaches the relay through
Docker's port map; that's only acceptable for a local demo.

## Building the app

Uses the local toolchain in `C:\Users\micha\Android\` (JDK 17, SDK, Gradle 8.14.3).

```bash
cd C:/Users/micha/PhpstormProjects/stremio-app && JAVA_HOME=C:/Users/micha/Android/jdk-17 ./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk` (copied to `StremioApp.apk`).
It's a debug build signed with the debug key, fine for sideloading.

## What was tested

On the `Xiaomi17TPro` emulator against the Docker demo: first run → Devices,
camera permission prompt and the scanner screen, pairing with the QR payload
(through the pairing link; the emulator's virtual camera couldn't be aimed at a
QR poster), naming, d-pad/OK/Back/routes/search/trackpad/volume keys reaching
Stremio, adding the HTPC backend by hand (health check only; no keys were sent
to the real TV), switching devices, rename, delete, and reconnecting to the last
device after a force-stop. Not tested: scanning a physical QR code, video
playback commands (no stream addon in the anonymous demo), the Windows build of
stremio-service.
