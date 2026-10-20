# External desktop bridge validation

Omalateral discovers a compatible Omalauncher desktop provider, asks for consent
on PhoneUI, and hosts the provider's live home surface beneath Android app tiles.
The provider owns its widgets and theme. The embedded home remains the fallback
when the provider is missing, incompatible, or declined. This adapter follows the
proposed Omalauncher desktop bridge; a stock release without the bridge does not
share its existing home state with Omalateral.

Omalateral requests a fresh external profile with ten workspace pages. Its
widget visibility, placement, and pinned apps are independent of the phone's
home pages; the user adds installed widgets to each workspace. Widget
definitions, data services, and theme updates still come from Omalauncher.
Omalateral sends its display-specific UI + text scale to the provider when the
surface opens after a slider change. The provider reflows the external grid and
scales widget content without changing surface/input coordinates. Saved widget
page assignments persist. Verify existing widgets remain placed and usable.

The helper's composed-display capture includes the provider home and widgets.
It selects only a physical display that matches the requested logical layer
stack; an unknown or ambiguous SurfaceFlinger dump fails closed. The capture
process has an eight-second watchdog. Protected tile pixels remain excluded from
the screenshot.

## Emulator verification (2026-10-15)

- `:app:testStableDebugUnitTest`, `:app:assembleStableDebug`, and
  `:app:assembleStableDebugAndroidTest` pass offline.
- Duplicate SurfaceFlinger layer-stack matches are rejected by a unit test;
  connection retries and remote input now have bounded executor capacity.
- On emulator-5554, the selected bridge preview fixture passes connection,
  inherited pages side by side, visible host cursor, PhoneUI-routed click and
  wheel input, search, and panel dismissal. It also rejects a fabricated
  provider-requested owner-profile component before creating a managed task.
- On the same emulator with a temporary secure secondary overlay, Gallery
  capture passes image decoding, physical-output sizing, and nonempty inherited
  home/widget content. The integrated protected fixture passes composed capture
  exclusion, touch after tile resize and restore, two protected tiles, and helper
  shutdown cleanup.
- One back-to-back bridge fixture run triggered an emulator system-server watchdog
  while the 3840 × 1200 overlay and another virtual display were active. The
  watchdog recorded heavy graphics-composer and SurfaceFlinger CPU use; the live
  surface and Gallery tests passed separately after a reboot. Treat long combined
  emulator runs as a device-capacity limit, not a Nubia result.

## Open review gates

- Provider negotiation, session opening, pointer/actions, and shutdown now run
  off BeastActivity's UI thread, with connection and surface-opening timeouts.
  One retry can run alongside a stuck Binder call; input saturation returns to
  the embedded desktop. Consent requests are serialized across sessions. The
  emulator fixture covers normal service behavior; a deliberately stalled
  provider and process-death recovery still need targeted validation.
- The earlier fixture explicitly selected `dev.omalauncher.bridgepreview` and
  sampled an inherited first page. The current fresh-workspace profile needs a
  separate provider-discovery fixture. Release-provider autodetection remains
  a physical check.
- Work-profile launches currently go directly to the external display rather
  than through managed tiles and PhoneUI controls.

## Queued Nubia checks

Run with the external display connected and the phone unlocked. Record the
Omalateral APK commit, Omalauncher build/commit, Android version, display mode,
and Shizuku/helper state with each result. Preserve logs and captures on failure.

1. Install the consolidated Omalateral APK and bridge-enabled Omalauncher. Confirm
   automatic detection and the PhoneUI consent flow; deny once and confirm the
   embedded fallback, then allow and reconnect.
2. Verify the external grid starts independently from the phone's three home
   screens. Add an installed widget to a workspace and confirm it appears only
   there. Change a shared widget definition or theme on the phone and confirm
   the external desktop updates without replacing the phone launcher. Move or
   resize an external widget, reconnect, and confirm its arrangement persists
   while the phone's arrangement stays in place. Change UI + text scale from
   PhoneUI and confirm the grid, widget frame, and text resize together without
   moving the pointer target away from visible controls.
3. Use PhoneUI alone to move the visible cursor, click, drag, right-click, scroll,
   search, open and dismiss provider panels, and manage Android app tiles. Check
   that moving a floating window or rearranging a tile does not snap back.
4. Save a full-display screenshot to Gallery with home and widgets visible.
   Repeat with an ordinary app tile and a protected app tile; verify ordinary
   content is included and protected pixels are absent.
5. Resize, hide/restore, and reconnect a protected tile. Test direct touch and
   PhoneUI-routed touch after each change, plus key input, chrome controls, and
   helper shutdown cleanup. Protected sources retain their app dimensions during
   tile resize; verify that their display resolution catches up after protection
   clears.
6. Launch personal and work-profile apps from PhoneUI. Record whether each is
   managed as a desktop tile; work-profile launch currently bypasses the managed
   tile path, so this is a tracked limitation rather than a passing criterion.
7. Turn off automatic Omalateral desktop ownership and reconnect. Confirm the
   native/OEM desktop can take the display without Omalateral changing display
   sizing or routing PhoneUI input to the inactive shell.

Remove the test APK after validation only if its data and captures have been
backed up. Leave the user's existing Omalauncher installation intact.
