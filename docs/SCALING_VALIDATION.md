# Scaling validation — 2026-09-12

Status: the reproduced scaling-related focus loss is fixed in the on-device candidate;
this is not a claim of a full release qualification or reproduction of a Java crash.

## Follow-up with keyboard reconnected

Installed `LATERAL_-0.2.11-scaling-focus.apk`, SHA256
`397FC32BB5DD7CA420AA96AB3F3F47CE3AAC7D99DCD186D0336E82AA1D456C38`.
Verified the running bridge's CLASSPATH matches the installed APK.

The old build again exposed Home while LATERAL remained alive. The candidate
captures the foreground workspace's existing, navigation-cancellable focus
authority **before** virtual-display resize and surface reattachment. Previously
only task moves were guarded; a later reattachment repair could arrive after
focus had already been lost and could not acquire authority.

Physical Nubia checks passed with the keyboard connected:

- Original profile: PhoneView remained foreground after restoration settled.
- UI 2× / hosted 5× / supersampling 2×: live changes and cold reload into
  PhoneView passed; the workspace remained foreground.
- UI 0.85× / hosted 0.60× / supersampling 2×: cold reload into PhoneView passed;
  values were verified in Display afterwards and the workspace stayed foreground.
- Android Home and Recents stayed open from PhoneView without focus being stolen.
- PhoneUI yielded to Android Home and Recents; Overview remained open.
- Process-exit history showed only expected install/force-stop exits during tests,
  not a new exception, native crash, or OOM.
- Original UI 1.10× / hosted 1.85× / supersampling 1× restored and verified.
  Aspect ratios and PhoneView opt-in were unchanged. All 45 unit tests pass.

The records below describe the earlier baseline investigation, before this fix.

## Physical Nubia NX789J

Installed candidate SHA256:
`11EAEA5E667589078B768C8568C5D3CDDC84AD78B5E4E62466B985D44C4CCDC5`

- Original PhoneView profile: 1216 × 2688, phone ratio 1:1, tablet ratio 1.45:1,
  supersampling 1×, UI/text 1.10×, hosted apps 1.85×.
- Reproduced workspace disappearance after accepting PhoneView on reload at the
  original profile. Home became foreground while the same LATERAL process and
  stopped PhoneView task remained alive. Bringing that existing task forward
  recovered the workspace. This reproduction was focus loss, not process death.
- Tested saving UI/text 2× with hosted apps 5× and supersampling 1×, then cold
  launching and accepting PhoneView. The process remained alive. The physical
  Bluetooth keyboard subsequently disconnected and PhoneView exited to PhoneUI,
  so sustained workspace verification at this endpoint is incomplete.
- Tested saving UI/text 0.85×, hosted apps 0.60×, supersampling 2× and cold
  launching PhoneUI. All values persisted and the process remained alive. The
  keyboard was absent, so this is not a PhoneView rendering pass.
- Android process-exit history contained no new exception/native crash/OOM;
  the test's explicit force-stops were recorded as user-requested exits. Logcat
  was stale, so absence of a current logcat exception alone is not conclusive.
- Restored original scaling values through the UI and verified their labels.
  Aspect ratios and PhoneView preference were preserved. Physical density 520
  and existing override 442 were not changed. No new APK was installed.

## Automated bounds checks

`ScalingRangeTest` covers 84 UI steps × 89 hosted-app steps × 21 supersampling
steps = 156,996 combinations, plus popup sizing over phone/glasses landscape
heights. All 45 unit tests pass.

Selectable ranges: UI/text 0.85–2× normally, up to 5× on ultrawide; hosted apps
0.60–5×; supersampling 1–2×. Steps are 0.05×. Aspect ratios accept 0.25–3:1.

Numeric tests do not prove virtual-display lifecycle stability; see the follow-up
device checks above. Ultrawide UI scaling up to 5× was numerically tested, not
tested on physical glasses in this session.
