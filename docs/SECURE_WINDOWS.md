# Secure windows in the external workspace

This is a flat-workspace adaptation of LATERAL's protected native
composition work (`63e3bed`, with coordinate corrections through `b97ed7a`).
Those commits are on `feature/ar-cylindrical-workspace`, which produces newer
LATERAL_dev builds; the older local `LATERAL_dev` branch tip does not contain them.

The helper mirrors only its owned task displays onto an existing secure external
display on Android 15+. Protected pixels do not enter TextureView, bitmaps, or
omalateral's screenshot encoder. No source application's FLAG_SECURE is removed
and no secure-capture permission is requested. The compile-only Android platform
signatures must not be packaged into an APK; use `scripts/verify-hidden-api-packaging.py`.

## Operation and boundaries

- Ordinary app tiles retain their TextureView rendering. Protected tiles use a
  secure SurfaceView anchor and helper-owned native display mirrors below the
  normal activity chrome. Input is mapped using committed native window transforms
  and targeted to the original app UID.
- Only helper-owned source displays and an already-secure external output are
  accepted. Display 0 and nonsecure/helper-owned destination displays are rejected.
- Android blocks input through an opaque untrusted host, even where its touchable
  region has a hole. With explicit user approval, the helper grants a temporary
  trusted-overlay lease to **omalateral's own BeastActivity window only**. It verifies
  the caller's installed app UID, the allowed host component, and system-reported
  window UID/display before activating protected presentation. Debug builds also
  allow the nonexported instrumentation host.
- Leases are reference-counted by native layer ID. Hiding/closing the last protected
  tile, clearing its secure flag, or gracefully stopping the helper removes the
  lease. Helper shutdown waits for worker cleanup before exiting. Source windows
  and third-party overlays are never marked trusted; global touch policy is unchanged.
- A small read-only JNI accessor obtains the local ViewRoot surface on an attached
  native thread. It does not install VMRuntime exemptions, enable global hidden-API
  access, or expose a general-purpose reflection bridge. ROMs lacking the required
  platform interfaces fail closed: ordinary rendering remains, protected content
  stays unavailable.
- Focused-app capture is disabled for a protected tile. Normal screenshots still
  exclude its protected pixels; chrome and ordinary tiles remain capturable.
- A protected app keeps its current virtual-display dimensions while its tile is
  resized. The native mirror scales to the new tile bounds; once the app clears
  `FLAG_SECURE`, the source display catches up to the tile size. This avoids an
  Android input-disabled transition during secure presentation.

This is secure presentation, not DRM decoding or a guarantee that every banking,
streaming, or device-policy-restricted application supports external displays.
Physical Nubia/glasses validation, display disconnect/reconnect, and abrupt helper
process death remain unverified for this omalateral port. If the helper crashes,
close/reopen the external workspace to destroy its old host surface before retrying.

## Current validation (2026-10-15)

- `stableDebug` and test APK builds and JVM tests pass in the isolated secure-presentation worktree.
- On emulator-5554 with a temporary 3840 × 1200 secure overlay, the fixture passes
  source/output/host rejection, protected clone composition, screenshot exclusion,
  chrome interaction, protected taps, scroll, keys, and text, tile resize, hide and
  restore, two protected tiles, and graceful helper cleanup. The second tile starts
  in its fixture activity and toggles `FLAG_SECURE` there; launching a new secure
  activity across tasks is outside this fixture.
- A physical Nubia display has not been tested for this split.

The instrumented fixture is `com.lateral.beast.ProtectedTileAndroidTest`; it requires
an emulator with an already-secure secondary display. Set `protectedDisplayId` only
when selecting among multiple secure outputs.
The fixture writes diagnostics to `test-captures/protected-flat/` in the app's external files directory.

```powershell
./gradlew.bat :app:assembleStableDebug :app:assembleStableDebugAndroidTest :app:testStableDebugUnitTest '-Plateral.vitureSdk=false'
python scripts/verify-hidden-api-packaging.py app/build/outputs/apk/stable/debug/app-stable-debug.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.lateral.beast.ProtectedTileAndroidTest com.omalateral.test/androidx.test.runner.AndroidJUnitRunner
```
