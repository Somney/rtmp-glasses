# Glasses Live

An Android app that streams the camera (and mic) of Meta AI glasses to your own RTMP, SRT or RTSP
server. It's built for a **glasses → phone → MediaMTX → OBS** setup.

> **Unofficial.** This is a hobby project, not affiliated with or endorsed by Meta. It uses Meta's
> Wearables Device Access Toolkit, which is in developer preview: it only works with
> **Developer Mode** enabled on your glasses, and Meta may change or break it at any time.
> Only stream people who know they're being recorded, and follow your local laws and
> [Meta's Acceptable Use Policy](https://wearables.developer.meta.com/acceptable-use-policy).

It is Meta's official *CameraAccess* sample (Wearables Device Access Toolkit 1.0.1) with a live
streaming pipeline added. Two stream modes:

```
Original quality (default):  glasses H.265 ─ smoothing buffer ─ Enhanced RTMP (H.265 + AAC) → MediaMTX → OBS
Compatible:                  glasses H.265 ─ smoothing buffer ─ decode → H.264 + AAC → RTMP/SRT/RTSP
```

*Original quality* forwards the glasses' own video untouched (no second compression, lowest delay).
*Compatible* re-encodes on the phone, for players that can't show H.265 or for SRT/RTSP.

## 1. Install

**Requirements:** an Android 12+ phone, Meta AI glasses paired in the Meta AI app, and
**Developer Mode** turned on in the Meta AI app (Settings → your glasses → Developer Mode).

1. On your phone, download the latest APK:
   **[GlassesLive.apk](../../releases/latest/download/GlassesLive.apk)** (or pick a build on the
   [Releases](../../releases) page).
2. Open it and allow your browser/files app to "install unknown apps". Play Protect may warn that
   the developer is unknown, because the app isn't from the Play Store; tap *More details → Install anyway*.
3. Open **Glasses Live HQ**, allow *Nearby devices*, tap **Connect**, and approve it in the Meta AI app.

The app asks for Nearby devices (to talk to the glasses) and Microphone (only if you stream
audio). It only sends video to the server URL you enter, and it
opts out of the Meta SDK's analytics and crash reporting.

Each release lists a SHA-256 checksum (`GlassesLive.apk.sha256`) if you want to verify the download.

## 2. MediaMTX + OBS

MediaMTX's default config already accepts RTMP publishing on port 1935 to any path, so no config
change is needed.

1. On the PC running MediaMTX, find its LAN IP (e.g. `192.168.1.50`). Allow ports 1935 (RTMP)
   and 8554 (RTSP) through the PC firewall.
2. In the app, tap the ⚙ next to **Go Live** and enter:
   `rtmp://192.168.1.50:1935/live/glasses`
3. In OBS add a **Media Source**, untick *Local File*, and set *Input* to either
   - `rtsp://192.168.1.50:8554/live/glasses` (recommended, required for H.265 on older OBS), or
   - `rtmp://192.168.1.50:1935/live/glasses` (needs OBS 30+ for H.265)

   Turn on *Restart playback when source becomes active* and consider unticking
   *Use hardware decoding when available* if you see glitches. Lower *Network buffering* for less delay.

## 3. Using the app

1. **Connect** (first launch only) → approve in the Meta AI app.
2. **Start Session** → **Preview** (the glasses camera turns on).
3. **Go Live**. The top-left chips show what the glasses are sending (`in: 504×896 · 24 fps · 1.8 Mbps`)
   and what's going out (`live: live 1900 kbps`). If the picture gets blocky while the `in`
   numbers drop, the glasses↔phone link is the bottleneck: move the phone closer or use Medium quality.
4. Tap **LIVE** again to stop. Ending the preview/session also ends the stream.

Settings (⚙):

| Setting | Notes |
|---|---|
| Server URL | `rtmp://` or `rtmps://` (both modes); `srt://` or `rtsp://` (Compatible only). MediaMTX SRT: `srt://IP:8890?streamid=publish:live/glasses` |
| Stream mode | Original quality (H.265 passthrough) or Compatible (H.264 re-encode) |
| Smoothing buffer | Off / 0.25 / 0.5 / 1 s. Holds frames briefly and releases them evenly, fixing stutter-then-fast-forward. Adds that much delay. |
| Glasses camera quality | Low 360×640, Medium 504×896, High 720×1280. Takes effect the next time you start Preview. Medium is usually steadier over Bluetooth. |
| Frame rate | 15 / 24 / 30 fps |
| Video bitrate | Compatible mode only |
| Lower bitrate on weak networks | Compatible mode only; leave off on home Wi-Fi |
| Audio | Glasses mic (Bluetooth), phone mic, or none |
| Auto-reconnect | Retries every 3 s if the connection to the server drops |

The output is portrait 720×1280 (the glasses' native orientation). In OBS, rotate or crop the
source if you want it landscape.

Streaming continues with the screen off (foreground service + Wi-Fi lock). If Android kills it
anyway, set the app's battery usage to *Unrestricted*.

## Troubleshooting

- **"Waiting for video from the glasses"** — wait until the preview shows an image, then tap Go Live.
- **Stuck on "connecting"** — the phone can't reach the PC: same Wi-Fi network? firewall port 1935
  open? correct IP? Test the URL by streaming to it from another app/OBS first.
- **Black video in OBS for a few seconds** — the stream starts on the glasses' next keyframe; it fills in shortly.
- **OBS shows nothing in Original quality mode** — play it via the `rtsp://` URL, update MediaMTX
  (H.265-over-RTMP needs v1.4+), or switch the app to Compatible mode.
- **Still stuttery** — raise the smoothing buffer to 1 s. In OBS, Media Source → *Network buffering* 2 MB.
- **Audio slightly out of sync** — use OBS → Advanced Audio Properties → *Sync Offset* on the source.
- **No audio from the glasses mic** — the glasses must be connected to the phone as a Bluetooth
  audio device. The app tells you if it falls back to no audio. Try *Phone mic* to confirm audio works at all.
- **Local recording is disabled while live** — the glasses mic can only feed one consumer.

## Building it yourself

The project needs internet access to Google Maven, Maven Central and JitPack.

- **Android Studio:** open the folder (JDK 17+, Android SDK 36), sync Gradle, then
  `./gradlew assembleRelease`. Without a signing key the APK is signed with your local debug key.
- **Your own fork on GitHub:** the *Build APK* workflow builds on every push to `main`. To publish
  installable releases, add these repository secrets (*Settings → Secrets and variables → Actions*):
  `KEYSTORE_BASE64` (your keystore, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`.
  Without them the workflow still builds, but skips the release.

Signing keys never go in the repo (`.gitignore` blocks `*.jks`, `*.keystore`, `*.p12`).

## Credits / licences

- Base app: Meta's CameraAccess sample, under the Meta Wearables Developer Terms (`LICENSE-meta-sample`).
- Streaming: [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1, Apache-2.0.

New code is in `stream/LivePublisher.kt`, `stream/FramePacer.kt`, `stream/LiveSettings.kt`, `ui/LiveControls.kt`, plus
small hooks in `camera/CameraViewModel.kt`, `camera/CameraUiState.kt`, `ui/CameraScreen.kt` and
`stream/StreamingService.kt`.
