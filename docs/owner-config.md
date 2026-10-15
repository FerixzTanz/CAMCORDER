# Owner's settings — the app's defaults

Taken from the diagnostics export `zeekr_diagnostics_20261015_131145` (app 2.12.2, 7X), section
"全部设置的原始值（app_config）". **From the first release after 2026-10-15, these are the defaults** in
`AppConfig` — a fresh install, or a setting never touched, comes up like the owner's car.
A setting that was already saved on a car keeps its saved value; only the defaults moved.

To refresh this file after changing settings: export diagnostics (Settings → System → Diagnostics),
read the `app_config` block, update the table below and the matching defaults in `AppConfig.java`
(constants `SIDE_POPUP_DEFAULT_*`, `HUD_SIDE_DEFAULT_*`, `REARVIEW_DEFAULT_*`, and the
`prefs.getXxx(KEY_…, default)` getters).

## Side view

| Setting (key) | Owner's value = default | Was |
| --- | --- | --- |
| Show side view on (`side_output`) | 2 = screen and HUD | 0 = screen only |
| Turn-signal side view (`side_popup_enabled`) | on | off |
| Stay open after the signal (`side_popup_close_delay_ms`) | 500 ms | 1000 ms |
| Screen pop-up size / shape (`side_popup_size`, `_shape`) | 33 % / 220 | 55 % / 180 |
| Screen pop-up vertical (`side_popup_vertical`) | 0 | 0 |
| View angle / turn back / turn up (`side_popup_fov_v2`, `_yaw`, `_pitch`) | 90° / 33° / 10° | 80° / 50° / 15° |
| Mirror the screen pop-up (`side_popup_mirror`) | on | off |
| HUD picture size / shape (`hud_side_size`, `_shape`) | 49 % / 194 | 74 % / 100 |
| HUD picture distance from the edge / vertical (`hud_side_left`, `_vertical`) | 11 / 21 | 6 / 50 |
| HUD picture opacity / night brightness (`hud_side_opacity`, `_night`) | 100 / 60 | 100 / 60 (same) |
| HUD picture contrast (`hud_side_contrast`) | 220 | 150 |
| HUD picture edge fade (`hud_side_fade`) | 6 | 15 |
| Mirror the HUD picture (`hud_side_mirror`) | on | off |

## Recording, buttons, other

| Setting (key) | Owner's value = default | Was |
| --- | --- | --- |
| Start on boot (`auto_start_on_boot`) | on | off |
| Keep alive (`keep_alive_enabled`) | off | on |
| Keep recording with the screen off (`screen_off_keep_recording`) | off | on |
| Recording status on screen (`recording_stats_enabled`) | off | on |
| Driving info bar under the video (`info_bar_enabled`) | on | off |
| Licence plate on the video (`license_plate_enabled`) | on (the plate itself is not stored here) | off |
| Floating button: lock position (`floating_position_locked`) | on | off |
| Floating button: tap does (`floating_tap_action`) | `toggle_recording` | `open_app` |
| Photo straighten (`photo_fisheye`) | on | off |
| Action buttons side (`action_rail_side`) | right | right (same) |
| Super mirror window size (`rearview_width` × `rearview_height`) | 782 × 295 | 900 × 340 |
| Super mirror window position (`rearview_x`, `_y`) | 2418, 198 (top right of the 3200×2000 screen) | automatic |
| Super mirror pan (`rearview_pan`) | 1.0 | 0.5 |
| Include beta in update check (`update_include_beta`) | on | on (same) |

Worth a second look, because they reverse a default that was chosen on purpose:
`floating_tap_action` (the old default avoided a stray tap stopping a recording),
`keep_alive_enabled` and `screen_off_keep_recording` (both were on), and
`side_popup_enabled` (off until the overlay permission is given; the service stops itself if it isn't).

## Not made defaults

- One-time or state values: `first_launch`, `language_chosen`, `rail_side_chosen`, `rearview_guide_seen`,
  `ui_left_for_screen_off`, `signal_scan`, `signal_scan_since`, and the super mirror's last-viewed lane
  (`rearview_lane`, remembered from gestures).
- Device-specific: `last_detected_sd_path`, `license_plate` (private, never exported).
- Off in the owner's config, same as the default: super mirror (`rearview_enabled`), button mode
  (`rearview_button_mode`), auto record, screen-off recording.

## Recording profile (as exported, for reference)

7X surround stream (`zeekr_7x`), quality `balanced`; recording resolution auto, 30 fps, bitrate medium,
codec auto, 1-minute segments, four-up layout; photos max size, quality 95; recording mode auto
(MediaCodec in use); storage `external_sd`, volume chosen automatically.
