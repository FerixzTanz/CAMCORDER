# CAMCORDER

**A personal, vibe-coded fork of [dts88's Zeekr Shortcut (Car Version)](https://github.com/dts88/zeekr-shortcut-car)**, a surround-view dash cam for the ZEEKR 7X head unit.

> [!IMPORTANT]
> **Please read this first.**
> - This is a hobby project by an owner, **not a professional developer**. The changes in this
>   fork were **vibe coded**: written with an AI coding assistant, reviewed by eye, and tested
>   only by driving my own car. There is no proper QA.
> - **All the real work is dts88's.** The dash cam, the Super mirror, the camera handling and the
>   vehicle-signal research all come from [dts88/zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car).
>   If you want the app without my additions, use theirs.
> - Not affiliated with dts88 or ZEEKR, and not endorsed by either. Not a safety system. Use at
>   your own risk.

## What this fork adds

**Turn-signal side view.** When you signal, a pop-up shows the camera on that side: left signal,
left camera; right signal, right camera. It closes when the signal goes off.

- The car's own views always win: in reverse, or while the stock 360 view, side pop-up or park
  assist view is showing, the pop-up stays hidden (and closes if it is open).
- Only opens above a set speed (default 30 km/h; below that the car shows its own side view).
- The two pop-ups meet at the middle of the screen. Size, height and an optional "stay open for a
  few seconds" are adjustable.
- Optional straightening of the fisheye picture (display only; recordings are unchanged).
- Optional instant pop-up: in D the picture is kept ready in the background, at the cost of the
  camera staying on while driving.
- Off by default: **Settings → Super mirror → Turn-signal side view**. It does not show while the
  Super mirror itself is switched on.

It relies on vehicle signals that dts88 found by experiment on one car. They may behave
differently on yours.

**Updates come from this repo.** The in-app update check looks at this repo's releases, not
dts88's. Releases here are marked `-beta`, so turn on *Include beta releases* under
**Settings → Check for updates** to get them.

Install: download the `.apk` from this repo's [Releases](../../releases) and sideload it through App Lab.
It installs over dts88's version and keeps your settings.

---

*Everything below is dts88's original README, unchanged apart from the credits.*

# Zeekr Shortcut (Car Version)

A surround-view dash cam for the ZEEKR 7X head unit.

[中文说明](README.zh-CN.md) · [Changelog](CHANGELOG.md) · [Credits](NOTICE.md) · [Platform notes](docs/zeekr-platform-notes.md)

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by, or endorsed by ZEEKR,
> and not certified for any vehicle safety function. It does **not** replace the factory dash cam,
> reversing camera, or blind-spot monitor. Do not operate it while driving.
> Full safety notice: [安全须知](README.zh-CN.md#安全须知).

---

## What it does

App Lab gives a third-party app the four surround cameras as **one pre-stitched stream**: four
square views packed into a single frame, which previews as a distorted vertical strip. This app
splits it back into a 2×2 grid and builds on that.

### Dash cam

- Records the surround view as a 2×2 grid, plus the front and rear cabin cameras if you turn them on.
- Three recording presets — save space (10 fps), balanced (20 fps), sharpest — each showing how
  many GB an hour it needs and how long your USB drive will last. Frame rate, bitrate, segment
  length and codec can also be tuned per camera.
- 1–10 minute segments. With a video storage cap set, the oldest clips are deleted to stay under
  it; with no cap, nothing is deleted and recording stops when the drive is full.
- Photos use each camera's largest size.
- Records to a **USB drive only**. Writing to the head unit's built-in flash sits behind developer
  options, because that storage cannot be replaced once worn out.

On the main screen, tap any view to fill the preview with it and tap again to go back. A floating
button shows whether recording is running and opens the app from anywhere.

### Super mirror

An electronic rear-view mirror: a floating, dockable window showing any one camera enlarged. Pinch
to zoom; swipe left or right to change camera; swipe up or down in the middle third to raise or
lower the framing. Push it half off-screen to hide it at the edge, and tap to bring it back. The
rear camera is mirrored, like a real mirror. Fisheye correction with an adjustable field of view is
optional.

### Send to your phone

While viewing a photo or a video segment, tap **send to phone**: the app shows a QR code, your
phone's browser opens that file, and you save it. Nothing to install on the phone.

The file is served over your local network only while the dialog is open, from a random one-off
address, and only that one file. Both devices need to be on the same network — the easiest way is
to turn on the phone's hotspot and connect the car to it.

---

## Install

- **Recommended — the [latest stable release](../../releases/latest).** The first stable release
  was [1.0.0](../../releases/tag/v1.0.0).
- **Happy to try things early — the newest `-beta` on the [Releases](../../releases) page.** Or
  turn on *Include beta releases* under Settings → Check for updates and let the app fetch it.
- Builds tagged `-alpha` are **not recommended**: they are test builds, often unverified on a
  vehicle. The in-app update check never offers them. They are on the same
  [Releases](../../releases) page if you want one anyway.

Download the `ZeekrShortcut-*.apk` asset from the release page and sideload it through App Lab.

Then open **Settings → Recording → Stream profile** and choose *Zeekr 7X (surround
composite)*, or *surround + front and rear cabin* to add the cabin cameras. Restart the app to
apply. If it reports that no composite stream was detected, this head unit or firmware does not
provide one and the app will not work on it.

Building it yourself needs JDK 17+ and the Android SDK (compileSdk 36):

```bash
git clone https://github.com/dts88/zeekr-shortcut-car.git
cd zeekr-shortcut-car && ./gradlew assembleRelease
```

The repo ships a public AOSP test signing key (password `android`). Override it with your own
through `ZEEKR_KEYSTORE`, `ZEEKR_KEYSTORE_PASSWORD`, `ZEEKR_KEY_ALIAS` and `ZEEKR_KEY_PASSWORD`.

---

## Tips

### If the USB drive can't keep up

If recording stops working properly on the USB drive because the files have grown too large:

1. **Lower the frame rate to 10 fps first.** In **Settings → Recording → Edit stream profile**, tap
   each camera under *Cameras recording* and set **Frame rate** to *10 fps (cap)*.
2. **Still not working? Then lower the bitrate** for those cameras.

Changes apply from the next recording.

A dash cam's main job is a clear picture. At 10 fps motion looks choppy, but each frame stays sharp.

For anything else, [open an issue](../../issues) and attach a report from **Settings → System →
Diagnostics**.

---

## Limitations

- Built for the ZEEKR 7X. Only the camera recognised as the composite stream is split; a head unit
  that does not provide one gets nothing split.
- Factory features come first: the built-in 360° view, reversing camera and parking cameras can
  reclaim a camera at any time.
- Starting on boot, recording on launch and preventing sleep are off by default. Recording with
  the screen off is only available with developer options on.
- Cropping a surround view is switched off for now.
- On-vehicle validation is ongoing. Automated tests cover the pure logic only; test in a stationary
  vehicle first.

---

## Credits and license

This fork: the turn-signal side view and small changes on top of
[dts88/zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car), vibe coded by FerixzTanz
with an AI assistant. Everything else is dts88's work and EVCam's before that.

**GPL-3.0**, inherited from [EVCam](https://github.com/suyunkai/EVCam) by suyunkai — the code base
this app is forked from. The first commit in this repository is EVCam's complete working tree, so
every change since then is visible as a diff.

Splitting App Lab's composite stream into a grid and rendering it is implemented here from scratch.

Origins, third-party licences and boundaries: [NOTICE.md](NOTICE.md).
