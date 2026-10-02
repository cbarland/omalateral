# PhoneView — 0.2.11

PhoneView runs the complete BeastUI workspace fullscreen in landscape on the phone, using Android's local mouse and external keyboard. Hosted apps still require the privileged input bridge.

Use `Ctrl+Left` and `Ctrl+Right` to focus and reveal the adjacent workspace window without reaching for the phone screen. The same shortcuts work in PhoneView and on an external BeastUI display.

New PhoneView display profiles default to 1.5× supersampling. Existing saved or
legacy supersampling choices remain unchanged; external-display profiles retain
their 1.25× default.

## Issue log

- PV-1 — fixed: native primary/secondary buttons and wheel events route through the hosted surface, without the managed cursor or raw input capture. Keyboard events preserve down/up, repeats and modifiers. Trusted hosted displays use independent focus and cannot steal top focus where the OS supports those flags, fixing the input timeout found during emulator testing.
- PV-2 — fixed: touch remains a touchscreen stream, never a secondary mouse click. Corrected Android UP/MOVE conversion into the helper wire protocol; previously taps remained unfinished and drag streams were confused.
- PV-3 — fixed: Display settings in both UIs contain the PhoneView toggle. An external alphabetic keyboard and no external display are required. Each eligible connection offers PhoneView once; declining permits manual activation later. Manual exit, Back, keyboard removal or display connection exits PhoneView. External-display handoff waits for surface retention before opening the replacement workspace, and restores PhoneUI if the transition exposes Home.
- PV-4 — fixed during validation: supersampling clipped at 640 raster DPI / 2.5x render scale. Render-only limits now cover supported density, UI scale and sampling combinations without changing logical content size.
- PV-5 — fixed during validation: PhoneUI now rechecks keyboard/display eligibility every time it resumes and presents a fresh offer when valid. PhoneView decorator controls use width-aware geometry instead of a horizontal scroller, preserving a centered navigation group and keeping every control visible. Native mouse clicks on all shell controls, including decorators, use the same captured click path as the ribbon.

## Source provenance and scope

Recovered over wireless ADB from the Nubia NX789J:
`/sdcard/Documents/LATERAL_branches/fix/supersampling-and-beastui-display/app/src`.
Local recovery: `builds-temp/nubia-fix-src` (not committed). The original main/dev exports were older; matching APK class names had not established matching implementations.

The incomplete embedded BeastWorkspaceView prototype was replaced with a display-0 session of the existing BeastActivity so launcher, task controls, fullscreen and lifecycle share one implementation. Display-panel and control-debouncing changes were recovered; existing density, supersampling and focus fixes were preserved and extended. Unrelated USB/ADB bridge changes and permission removals were not imported.

Agent controls are absent from main, including its dev build flavor. Managed Termux agent/loop work is preserved on `LATERAL_dev` (8ceb35a, b567b4f). No agent-runtime asset ships in this release.

## Validation — 2026-09-12

- SDK-free stable release build, release lint and 17 unit tests passed: density (9), PhoneView policy (4), touch protocol (2), existing agent policy (2).
- Signed upgrade installed over the existing stable emulator app without deleting its data. Certificate matches published 0.2.3: SHA-256 `6674ddc08c7e982b1dac995e4ad19d59bf79700c4434cf09737e07c9c04e27cd`. This retains the project's existing Android debug-certificate convention, not a new production keystore.
- Pixel_9, Android 15/API 35, emulator-5556: simulated external USB keyboard via Android uinput; initial and resumed-PhoneUI offers; fullscreen landscape; native launcher; manual toggles from both UIs; Back; keyboard disconnect/reconnect; external-display handoff.
- Genuine mouse frames changed a card between phone/tablet modes and entered/exited fullscreen through decorator controls. All eight controls fit each phone-width decorator and the centre navigation group remained centered. A temporary 1920x1080/320 overlay confirmed stable BeastUI moved to the external display with PhoneUI on display 0; +APPS opened the external Beast launcher. The overlay was removed and the temporarily disabled dev flavor was restored afterwards.
- An emulator-only hosted input receiver verified touchscreen DOWN/UP, primary mouse DOWN/UP, secondary button=2 and button press/release, wheel=-3, and complete mixed-case `PhoneView` key sequences with Shift and no five-second stalls. Mouse injections target display 0 and traverse the real workspace surface/privileged bridge. Earlier candidate ANRs exposed the focus race; those candidates were not released.
- A live hosted app survived handoff to a 1920x1080/240 simulated external display. BeastUI moved external, PhoneUI remained on display 0, and the hosted virtual display was reused rather than duplicated.
- Earlier Chrome testing confirmed visible mixed-case editing. Both UIs expose no agent toolbar entry. Test receiver/injector and logs are under ignored `build/`, not in the APK.

The final candidate passed 49 unit tests and was installed on the Nubia without clearing data:
`LATERAL_-0.2.11-phoneview-decorators.apk`, SHA256
`7631F94958F78FEC79D7AF40CEFB607E7D7EAF3E7D9A193EEEF0BD8312578681`.
PhoneUI launched successfully and the bridge reported READY. The Bluetooth keyboard
had disconnected before this final phone smoke check, so the new resume offer and
decorator mouse interactions were certified on the emulator, not repeated on Nubia.
Real VITURE timing switches remain advisable hardware regression. Android versions
lacking independent-focus flags retain the existing fallback and were not tested.
