# Workspace display profiles

Display settings in both PhoneUI and BeastUI now contain:

- Phone-style app aspect ratio (width:height).
- Tablet-style app aspect ratio (width:height).
- Supersampling (1–2×).
- Linked UI + text scale (85–200%, up to 500% on ultrawide modes).
- Hosted app scale (60–500%).

These controls have been removed from the general Settings panel. Enter ratios such as `9:19.5`, `9:16`, `4:3`, or `16:10`, then select Apply aspect ratios. Ratios between 1:4 and 3:1 are supported. The ratio applies to app content, excluding card borders and title bars; fullscreen still fills the workspace. Narrow title bars scroll horizontally to keep controls reachable. The workspace toolbar has overflow arrows for larger UI scales.

## Persistence and identity

Each profile is saved in `lateral_display_profiles`, keyed by display identity and the active physical mode width/height. Refresh-rate changes at the same resolution share a profile; rotated window bounds do not create a new profile.

The built-in screen has a dedicated PhoneView identity. External screens use public Android display/product identifiers (name, manufacturer, product and model year where available), falling back to display name. Android's temporary display number is not used. Identical devices without publicly exposed distinguishing identifiers share a profile; changing an OEM-provided display name may create a new profile.

PhoneUI edits the connected external workspace profile, or the PhoneView profile when no external screen is connected. Display dialogs refresh their target on connection/mode changes. An edit started against an old target cannot be saved into the newly connected display's profile.

Unsaved profiles inherit the old global hosted/supersampling values and standard/ultrawide UI defaults. New profile edits do not change those legacy defaults, so preferences do not leak between displays. Existing app task identity and presentation mode remain unchanged.

## Validation, 2026-09-12

- SDK-free release build/lint and all 21 unit tests passed. Four new tests cover ratio parsing and invalid values, round-trip persistence of all five fields, display/resolution/PhoneView isolation, legacy defaults, and bounded invalid values.
- Pixel_9/API 35 emulator: edited PhoneView supersampling to 1.85×, UI/text to 1.50×, and hosted scale to 1.20×. Values survived app restart and switching to/from simulated external displays.
- External 1920×1080 retained its separate defaults, saved 1.55× supersampling, and restored it after switching to 3840×1200. The ultrawide profile had its own default UI scale and separate supersampling value. Runtime display numbers changed without losing the profile.
- Saved phone ratio 9:16 and tablet ratio 4:3 survived APK update. Actual hosted virtual displays measured 692×1231 and 1642×1231 respectively (integer rounding), confirming settings affect app geometry.
- At 1.5× UI scale, toolbar overflow exposes Display and Settings without overlap; narrow-card horizontal scrolling exposes the presentation switch. Phone/tablet switching resized the existing hosted display.
- Public identity implementation builds and runs without invoking Android's blocked hidden getUniqueId API. No physical Nubia/VITURE mode-switch validation was performed.

Candidate APK: `build/releases/LATERAL_-0.2.11-display-profiles.apk`. Publication remains on hold; this supersedes the earlier PhoneView-only release candidate.

## PhoneView crash fix, 2026-09-12

Nubia's retained crash report identifies `Dialog.dismiss` from `PhoneView.onInputDeviceRemoved`: the offer window had already detached. This is not evidence of a scaling allocation failure. Popup ownership now tracks the Activity explicitly, clears before dismissal, cleans up on pause/destroy, and dismisses before the entry transition. Only the known detached-window exception is suppressed. Failed window-token presentation permits a later offer retry.

- Independent sub-agent review completed; both identified edge cases addressed.
- SDK-free stable release build/lint and all 27 unit tests passed. Six added tests cover detached-window cleanup, owner identity, reentrancy, late callbacks, unrelated exceptions, and failed-presentation retry.
- API 35 emulator: pause the visible offer, disconnect the keyboard, reconnect and accept; the same app process remained alive. Changed both ratio fields to 1:1 and increased supersampling/UI/hosted scales to 2.00/2.00/1.40. Re-entered the scaled workspace, disconnected the keyboard, and confirmed return to PhoneUI without a new crash. The detached OEM-window exception itself is injected in the unit test, not reproduced on demand in the emulator.

Verified crash-fix candidate: `build/releases/LATERAL_-0.2.11-phoneview-crashfix.apk`, SHA-256 `49D5A0B31D98533FD8A9AAF82B6E35FF6FA3F9B3904E20045827EEACC478D934`. Version remains 0.2.11 (6); this is an updated candidate, not a published release.

## Follow-up: manual entry and workspace focus

The user still observed the issue after the popup fix. Nubia recorded no newer exception; later exits were classified as task removal. Emulator testing reproduced a separate failure: restoring a hosted task exposed Home, then Android removed the hidden PhoneView task. The helper only recognized MainActivity as the phone workspace, and its repair always launched MainActivity even when given PhoneView's task ID.

The helper now recognizes both workspace activities and focuses the exact existing PhoneView task. Manual PhoneView entry no longer requires a keyboard; keyboard detection still offers entry, keyboard disconnection exits an attached-keyboard session, and external displays prevent entry/exit PhoneView. Entry requires both READY state and a successful live helper Binder ping. An unavailable bridge initiates connection recovery and leaves the user in PhoneUI to pair/connect and retry.

- All 31 unit tests and the SDK-free stable release build passed, without delegation.
- Emulator verified missing-bridge entry rejection, manual keyboard-free entry with a live bridge, and hosted-app restoration without losing PhoneView.
- From PhoneView's own Display dialog, verified both persisted ratios at 1:1, lowered and increased UI scale, and lowered/increased hosted scale. Workspace remained resumed with UI 1.95×, hosted 1.40×, supersampling 2×, with no new process crash.
- Candidate: `build/releases/LATERAL_-0.2.11-phoneview-focus.apk`. This supersedes the popup-only candidate; physical Nubia scaling confirmation is still required.

## Corrected opt-in, native ribbon clicks, and 5× hosted scale

The manual-entry interpretation above was incorrect. The Display switch now persists an opt-in preference (initially off), independently of keyboard presence. Enabling without a keyboard does not launch PhoneView. With opt-in enabled, keyboard detection without an external screen shows the confirmation popup; only acceptance with a live bridge enters. Disconnection exits the session but keeps the preference armed. Turning the switch off exits and suppresses offers.

Native primary mouse clicks on ribbon controls now retain their target through DOWN/UP and invoke the normal click action, avoiding interception by the horizontal scrolling container. Mouse movement beyond touch slop, cancellation, and release outside the target do not click. Hosted-app input is unchanged. Emulator mouse events reproduced the original +APPS failure and verified +APPS and Display after the fix.

This uncovered another high-scale crash: the launcher used a scaled minimum height larger than the landscape viewport's maximum. Popup dimensions now prioritize available space, with regression coverage for the observed 920px minimum/842px maximum.

Hosted scaling now supports 5× in controls, persisted profiles, density calculations, and the renderer's minimum buffer scale. Emulator verified 5.00× with 1:1 ratios and actual resized hosted displays. All 37 tests and the SDK-free stable release build passed.

Open issue: Show All accepts a mouse click but exposed Home during the emulator's multi-app transition; exact workspace focus was restored manually to continue tests. Do not consider that interaction verified or the release ready based on this candidate.

Candidate: `build/releases/LATERAL_-0.2.11-phoneview-optin.apk` (version 0.2.11/6, not published).

## Show All focus and deliberate Android navigation

The preceding Show All focus issue is fixed in `LATERAL_-0.2.11-navigation-focus.apk`. Recovery now reads live running tasks, including PhoneView when absent from Recents, and consistently uses leaf task IDs (not Home's containing root-task ID).

Focus recovery is limited to a foreground-originated, 2.5-second transaction. It may repair transient Home, never an unrelated app or unknown foreground. Android Home/Recents broadcasts revoke the transaction and invalidate pending callbacks. A new foreground workspace interaction is required to re-arm recovery. The navigation listener runs in the app process on its own thread and forwards cancellation directly over Binder, not behind queued launches. The helper leaves recovery disabled unless the app confirms listener registration. A standalone helper receiver was tested and discarded because Android did not deliver navigation broadcasts to it.

Validation (API 35 emulator, no delegation):

- All 43 unit tests passed, including six recovery-gate tests for cancellation, expiry, unrelated apps, unknown foreground, fresh interactions and stale batch callbacks.
- Native-mouse Show All left PhoneView foreground after hosted apps settled, including after returning from Home and re-arming via a fresh interaction.
- Home and Recents navigation produced `homekey`/`recentapps` cancellation logs; Recents stayed open beyond the recovery window.
- With a simulated external display, stable PhoneUI could leave via Home and Recents without being pulled back. Selecting Android Settings from Recents left Settings foreground on subsequent checks.
- No new app-process crash during these tests. The simulated display and keyboard were removed after testing; the separate emulator dev build was stopped to prevent display ownership interference.

SHA-256: `11EAEA5E667589078B768C8568C5D3CDDC84AD78B5E4E62466B985D44C4CCDC5`. Built and tested locally only; not installed on Nubia or published. Physical Nubia navigation validation remains outstanding. Reload the APK and its ADB helper together.
