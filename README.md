# CAMCORDER

A surround-view dash cam and side-view helper for the ZEEKR 7X head unit, running as a sideloaded
App Lab app.

[中文说明](README.zh-CN.md) · [Changelog](CHANGELOG.md) · [Credits](NOTICE.md) · [Platform notes](docs/zeekr-platform-notes.md)

**2.13.0 is the final release.** The app does everything its author set out to do. Bug reports are
welcome, but no further features are planned.

> [!IMPORTANT]
> - CAMCORDER is a personal hobby project by a car owner, **not a professional developer**. My
>   changes were vibe coded with an AI assistant, checked by eye and tested only in my own car.
>   There is no proper QA.
> - It is **not** a safety system and does not replace the factory dash cam, reversing camera or
>   blind-spot monitor. Do not operate it while driving. Use at your own risk.
> - Not affiliated with or endorsed by ZEEKR.

## Standing on other people's work

CAMCORDER would not exist without the people below. Most of what it does is theirs.

- **[dts88](https://github.com/dts88/zeekr-shortcut-car)**, author of *Zeekr Shortcut (Car Version)*,
  the project this is forked from. The 2×2 surround split, the Super mirror, the recording
  pipeline, the playback screens, send-to-phone, and the reverse-engineering of the 7X's camera
  stream and vehicle signals are all dts88's work. I track their releases and bring their fixes in.
- **dantenothing**, who came up with the idea first, before I moved over to dts88's app. As far as I
  know no code here comes from them, but the idea started with them.
- **[suyunkai](https://github.com/suyunkai/EVCam)**, author of *EVCam*, the GPL-3.0 code base that
  dts88's app was built on. Camera handling, segmented recording, storage management and the
  floating window start there.

If you only want the dash cam and mirror, use dts88's app. Come here for the extras below.

## What CAMCORDER adds

**Turn-signal side view.** Signal left and the left camera pops up; signal right and the right one
does. It fades in, and fades out when the signal goes off. It is on by default and shows on both the
screen and the HUD.

- The car's own views win: in reverse, or while the stock 360, side pop-up or park-assist view is
  up, the screen pop-up stays hidden. The HUD steps aside only for reverse, so it also works at
  low speed when the car pops up its own views on the screen.
- The screen pop-up only opens above a set speed (default 30 km/h).
- Adjustable size, height and hold time. Optional fisheye straightening (display only, recordings
  are untouched) and an optional always-ready mode that keeps the camera warm in D.
- **HUD output**: show the side view on the screen, the HUD (found as the extra 1440×480 display),
  or both. It appears at any speed and is mirrored like a door mirror by default. Under **HUD
  picture**: size, position, opacity, contrast, edge fade, and dimming at night from the car's
  day/night signal. If the HUD isn't found, the screen pop-up is used instead. The car's own HUD
  graphics always draw above the picture; that layer is not reachable from an app.
- Switch it off in **Settings → Super mirror → Turn-signal side view**. It relies on vehicle
  signals that dts88 found on one car, so it may behave differently on yours.

**Recording follows the driver.** Starts when you take the driver's seat or leave P, stops after
you get out (or when Sentry Mode takes over). Can be switched off in Settings → Recording.

**Smarter recording and playback.** 30 fps, dropping to 15 fps when parked for a while. A hard stop
locks the footage around it. Playback groups clips by drive, shows distance, marks hard braking on
the timeline, and **Save clip** exports the 30 seconds around any moment. Each can be turned off.

**Its own update channel.** The in-app updater reads this repo's releases. Turn on *Include beta
releases* in **Settings → Updates** for betas.

## Install

Sideload the `.apk` from this repo's [Releases](../../releases) through App Lab. It installs over
dts88's app and keeps your settings. When the installer finishes, tap **Back**, not **Open**, or the
head unit's installer can get stuck.

Build it yourself with JDK 17+ and the Android SDK (compileSdk 36):

```bash
./gradlew assembleRelease
```

The repo carries a public AOSP test signing key (password `android`); override it with
`ZEEKR_KEYSTORE`, `ZEEKR_KEYSTORE_PASSWORD`, `ZEEKR_KEY_ALIAS` and `ZEEKR_KEY_PASSWORD`.

For the underlying dash cam, Super mirror, playback and send-to-phone features, see
[dts88's README](https://github.com/dts88/zeekr-shortcut-car#readme).

## Known limits

- Built for the ZEEKR 7X only. A head unit without the composite camera stream gets nothing split.
- Factory features come first and can take a camera back at any time.
- Record to a USB drive. If it can't keep up, use a faster drive or lower the frame rate/bitrate
  (needs Developer options; see Settings → Recording → Edit stream profile).
- Automated tests cover pure logic only. Try it in a parked car first.

Problems: [open an issue](../../issues) and attach a report from **Settings → System → Diagnostics**.

## License

**GPL-3.0**, inherited from EVCam via dts88's app. Every change since the first commit (EVCam's
complete tree) is visible as a diff. Full provenance and third-party licences: [NOTICE.md](NOTICE.md).
