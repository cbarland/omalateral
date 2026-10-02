# Android Open Accessory helper bootstrap

The phone accepts manufacturer `LATERAL_`, model `USB Host Prototype`, version
`1.0` (case sensitive), both in its attachment filter and at runtime. MainActivity
enumerates attached accessories on startup and attachment intents, requests USB
permission when needed, and runs one bootstrap attempt per attachment/activity lifetime.
The worker writes exactly four US-ASCII bytes `PING` with no terminator and
requires exactly four reply bytes `PONG`. It accumulates partial reads/writes
using fixed four-byte buffers. No accessory data is logged.

I/O uses one serial worker and one independent scheduled watchdog with a
five-second deadline starting when a queued probe becomes active. A stalled open
can retain one active probe and at most one replaceable pending attachment.
Detach/destruction and deadline expiry close the installed stream transport.
AutoCloseInputStream owns the ParcelFileDescriptor; FileOutputStream borrows its
same FileDescriptor. Closing the owner reaches Android's descriptor invalidation
and async-close signaling path (ParcelFileDescriptor / IoUtils / IoBridge), rather
than relying on O_NONBLOCK or Thread.interrupt. Installation and cancellation are
serialized; completed watchdog tasks are removed. Late
permission callbacks and results cannot revive cancelled sessions. The Android
`openAccessory` Binder call itself is not interruptible by this controller; if
it returns after cancellation/deadline, its descriptor is immediately closed.

`ProbeCoordinator` remains the isolated preflight implementation and its existing
PFD tests are retained. The controller now uses `UsbBootstrapCoordinator`, which
owns the same descriptor across preflight, bootstrap, and Binder correlation.
After PONG it starts a 120-second deadline covering service admission, BOOT/BSTA,
and Binder arrival. Cancellation closes the actual descriptor outside its state
lock, invalidates the token immediately, and suppresses stale completion callbacks.
The service serial worker admits only one USB attempt. A healthy bound helper or
an in-progress wireless startup prevents BOOT. Wireless startup is suppressed only
while the admitted USB attempt remains valid; failure, timeout, or detach releases
admission and restores wireless fallback. Preflight failure leaves wireless behavior
unchanged.

## Firmware wire agreement

After raw `PING` / `PONG` (four ASCII bytes each, no delimiter), Android writes:

- `BOOT`: four ASCII bytes.
- Version: one byte, `01`.
- Base APK path byte length: unsigned 16-bit little endian, 1..512.
- That many UTF-8 bytes from `applicationInfo.sourceDir`, without a terminator.
  The path must start with `/data/app/`, end with `.apk`, and contain no NUL or
  control characters. Invalid UTF-16 and paths exceeding the byte limit are rejected.
- Sixteen cryptographically random bytes from `SecureRandom`.

Maximum BOOT frame size is 535 bytes. One request is in flight. No token or path
is logged. Firmware replies with six-byte frames: ASCII `BSTA`, version byte `01`,
status byte. Android accumulates fragmented reads and rejects bad headers,
versions, statuses, truncation, and repeated interim status. Status `00` means
awaiting user ADB authorization and may occur once; `01` is terminal shell
OPEN/OKAY, `02` terminal rejection, and `03` terminal failure. A terminal frame
ends status parsing. The board must not send subsequent frames for that request.
The board's authorization wait is 90 seconds; Android's bootstrap/Binder deadline
is 120 seconds, following the separate five-second preflight deadline.

Firmware hex-encodes the token as exactly 32 hex digits and launches the fixed
`com.lateral.privileged.PrivilegedServer` entry point with the sole argument
`--usb-lifeline=TOKENHEX`, using the supplied base APK as its classpath. It must
quote the APK path safely when constructing a shell command; path validation is
not shell escaping. Firmware retains the ADB shell and keeps stdin open.
The helper rejects malformed/extra arguments, skips the global orphan sweep in
USB mode, and echoes the normalized lowercase token as Bundle key `usb_token`
alongside `binder` through the existing provider reflection path. The shell/self
UID restriction is unchanged. EOF or read failure on stdin invokes process-local
cleanup followed by guaranteed process exit. Wireless startup with no arguments
retains its existing behavior.

BSTA `01` alone never means READY. Binder and BSTA may arrive in either order;
both must belong to the current valid attempt, and the Binder must ping and accept
`linkToDeath` before publication. A duplicate accepted Binder is ignored. Rejected
stale/different offers terminate only the offered helper, never a healthy accepted
wireless or USB helper. Generation and Binder identity guard death recovery.
READY promotes startup into an accepted lease containing the exact attempt/token,
remote Binder, service generation, and an app-process owner Binder. The coordinator
reports success once, cancels startup deadlines, and retains the AOA transport.
Activity destruction, detach, explicit cancellation, or coordinator close releases
that lease and closes the transport once. Release exits only the matching helper;
an old lease cannot terminate a later wireless service. Binder death clears
readiness and permits wireless recovery.

The provider bounds its USB acknowledgement wait to ten seconds without waiting
on the service worker. It returns `usb_accepted=true` and `usb_owner` only for live,
committed ownership; timeout invalidates the matching offer/lease. Both reflective
provider signatures return the actual Bundle. USB helper startup bounds publication
to fifteen seconds, requires the acknowledgement, links owner death, and rechecks
owner liveness. Owner death, rejected/failed acknowledgement, or shell stdin EOF
causes process-local cleanup and exit. Firmware must keep shell stdin open for the
whole accepted connection. Wireless publication still needs no acknowledgement.
BOOT/BSTA remain version 1, with the existing maximum 535-byte BOOT frame.

## Validation

The StableDebug build and ordinary emulator launch validate integration without
requiring USB hardware. An emulator with no accessory should launch normally;
existing launcher-search and focus-repair intents should retain their behavior.
Neither an emulator launch nor assembly proves physical accessory operation.

On Nubia hardware later, use an AOA host advertising the exact strings above:

1. Attach with the app stopped, then with MainActivity already running. Accept
   permission and observe `AccessoryProbe` logging the bootstrap outcome. Confirm
   host capture begins with precisely `50 49 4e 47`, without newline, then BOOT
   only after PONG and service admission.
2. Reply in fragments (for example `P`, then `ON`, then `G`); verify success.
3. Deny permission; verify no host traffic. Detach and reconnect to retry.
4. Send an incorrect reply, close early, or withhold the reply; verify failure
   or deadline logging, responsive UI, and descriptor release.
5. Detach during permission or I/O, destroy/recreate the activity, and reconnect.
   Verify old callbacks/results cannot report success or open a stale session.
6. Change each identity field separately; verify no probe. Exercise ordinary
   focus-repair/launcher-search intents and wireless ADB independently.

JVM tests exercise fragmented replies, malformed replies, EOF, timeout, explicit
close, late resource installation, and replacement of queued attachments. A
loopback socket surrogate separately verifies that its cross-thread close releases
a blocked reader. This proves coordination and the surrogate's close behavior;
it does not prove Android accessory-driver wakeup. No physical USB result is claimed.

`ProbeCoordinatorAndroidTest` adds an Android instrumentation gate using two
`ParcelFileDescriptor.createPipe()` pairs, with no network, backend, or fabricated
`UsbAccessory`. Each endpoint uses an `AutoCloseInputStream` owner and a
`FileOutputStream` borrowing that exact descriptor; closure is input first,
output second, matching the controller. Pipes are unidirectional, so PING writes
use a separate pipe from PONG reads. The read endpoint's borrowed output is never
written. This exercises Android/Libcore descriptor close and blocked-read wakeup,
but does not reproduce a single duplex accessory-driver descriptor.

The tests consume exactly PING, keep the reply peer open without sending data,
observe read entry and 200 ms without completion, then require both local PFD
invalidation and read failure within bounded waits. Watchdog completion and an
explicit coordinator close are independently bounded. Explicit close also waits
for the coordinator worker to exit before asserting no stale callback. Fragmented
PONG is sent one byte at a time, awaiting consumption between writes, and must
complete after exactly four bytes. EOF on the PING pipe checks for trailing bytes.
Potentially blocking test operations and cleanup run off the instrumentation
thread with five-second waits. The APIs used are available at the app's API 26
minimum; a run on API 35 does not establish runtime coverage of every API 26+
release or physical AOA behavior.

On 2026-09-26, local ADB identified `emulator-5554` as API 35 with ABI list
`x86_64,arm64-v8a`; the shipping `arm64-v8a` filter is unchanged. The requested
gate is `:app:testStableDebugUnitTest :app:connectedStableDebugAndroidTest
:app:assembleStableDebug`, with `--no-daemon`, `-Plateral.vitureSdk=false`, and
`ANDROID_SERIAL=emulator-5554`. That unchanged gate passed (Gradle exit 0): all
three instrumentation tests passed on `Ripp_Lateral_API35(AVD) - 15`; unit tests
and StableDebug assembly were up to date. This is observed Android pipe
async-close evidence, not accessory-driver validation. Physical Nubia checks
above remain required.

Bootstrap unit coverage additionally checks exact BOOT layout/limits, fragmented
BSTA and malformed frames, strict helper arguments, cancellation/deadline token
invalidation, late descriptor installation, admission denial without BOOT, BSTA
without Binder, duplicate/stale Binder policy, and generation-guarded death policy.
These JVM policy tests do not exercise actual cross-process Binder death or ADB
authorization. The original ProbeCoordinator Android pipe tests remain unchanged.
Emulator build/tests cannot prove physical AOA-to-ADB authorization, a board-held
shell lifetime, or Nubia accessory-driver close behavior. Follow-up hardware tests
must include auth acceptance/denial/timeout, detach before and after acceptance,
wireless already healthy, duplicate/stale token offers, and shell EOF.

After the bootstrap implementation, the same unchanged Gradle gate passed on
2026-09-26: 74 JVM tests with zero failures, all three API 35 emulator PFD
instrumentation tests, and StableDebug assembly (Gradle exit 0). This does not
claim the follow-up actual token/Binder/ADB-shell lifeline or physical Nubia checks.

Inspect descriptor/thread counts across repeated cycles. Verify on physical hardware
that Android async-close signaling releases blocked accessory reads and writes on
deadline, detach, and activity destruction. A delayed openAccessory Binder call
cannot be forced to return; its eventual transport is closed without an exchange.

Accepted-lease coverage adds `UsbBootstrapCoordinatorAndroidTest` for withheld
preflight watchdog and explicit close on real Android pipes. The process suite
uses `UiAutomation.executeShellCommandRw`, retained stdin, the installed base APK,
and the fixed production USB entrypoint. It checks remote Binder READY, retention
past startup deadlines, coordinator detach/close-once release, and stdin EOF death.
A test-APK-only wireless publisher uses production provider reflection without
the global orphan sweep, so stale USB rejection can be tested against a healthy
wireless Binder. Tests clean up only helpers they launch and do not replace an
existing healthy helper. No production test entrypoints are added.

Owner-process death is implemented but is not exercised by killing the app in this
suite: the actual owner lives in the instrumentation process. An external parent
may force-stop that controlled app after observing READY and verify helper death.
API 35 pipe/process results do not establish physical AOA, board authorization,
Nubia driver wakeup, or behavior on older reflective provider signatures.

On 2026-09-26 the unchanged Gradle gate passed after the lease repair (exit 0):
all eight API 35 instrumentation tests ran with no failures or skips, unit tests
passed, and StableDebug assembled. The five added instrumentation cases cover
two production-coordinator PFD cases and three isolated helper process cases.
Process exit is observed by remote Binder death and/or the launched PID before
closing retained stdin; stdout EOF alone is insufficient because UiAutomation's
output pump can remain open while its stdin pump waits. The test shell uses `exec`
to preserve the recorded PID when launching the fixed helper entrypoint.

Attempt termination now has a durable listener independent of the completed
admission and READY futures. The coordinator subscribes before admission, releases
the matching retained transport on helper death, and claims callbacks and resource
detachment once under its ownership lock. Descriptor closure, service cancellation,
and result callbacks run after unlocking. Termination of an old attempt cannot
clear or close a replacement connection. Successful READY still retains transport.

Additional production-path instrumentation covers READY followed by helper stdin
EOF while the coordinator remains open, remote Binder death, exact-PID exit,
retained PFD invalidation, callback uniqueness, and replacement isolation. An early
real helper offer is observed on the service worker before shell acceptance; its
acknowledgment must remain pending while that worker continues to accept work.
Publishing shell acceptance must then produce the live correlated Binder and owner.
On 2026-09-26 the unchanged full gate passed after this termination repair
(Gradle exit 0): 76 JVM tests passed, and the API 35 XML reports record 11
instrumentation cases, zero failures, and one expected external-probe skip
(10 passed). StableDebug assembly also passed. Physical Nubia qualification
remains pending.

`UsbHelperLifecycleAndroidTest.externalOwnerDeathWithHostStdin` is a separate
test-APK-only external experiment. Without instrumentation argument
`externalUsbToken` it is skipped in the ordinary suite. The argument must be exactly
32 hexadecimal digits. The probe admits that production attempt and publishes shell
acceptance, then sends status Bundle `usb_phase=admitted`. The external parent
launches the production helper with that token and installed base APK using its
own ADB shell, retaining stdin independently of instrumentation. After receiving
`usb_phase=ready`, the parent force-stops the target app and verifies that the exact
helper PID becomes absent or zombie while host stdin remains open. Only afterward
may it close stdin. The probe waits at most 120 seconds after READY and fails if
the parent never stops it; it never force-stops the normal instrumentation suite.
No owner-death experimental success is claimed until the external parent records
that independent process-exit evidence. No raw guest logs are needed.

ADB-only production launch acceptance also waits for `LATERAL_USB_READY`, creates
a small trusted virtual display through the published remote Binder with a
disposable surface, and closes retained stdin to verify Binder death and service
cleanup. A duplicate ADB-only launch must exit without replacing or killing the
accepted Binder. These device checks depend on privileged display APIs and are
separate from emulator build or pipe coverage.
# Experimental detached USB bootstrap

For a USB ADB shell only, the experimental `--usb-adb-bootstrap` mode completes the normal
shell UID admission and correlated app-owner Binder handoff, then remains alive after shell
stdin closes. READY is printed only after the app acknowledges ownership. The transport label
records USB bootstrap provenance; it does not mean the USB cable must remain connected.

Example detached launch (replace the APK path with the installed stable APK):

```sh
nohup sh -c 'CLASSPATH=/data/app/.../base.apk app_process /system/bin --nice-name=lateral_privileged com.lateral.privileged.PrivilegedServer --usb-adb-bootstrap' </dev/null >/data/local/tmp/lateral-usb-bootstrap.log 2>&1 &
```

The helper is cleaned up when its app-owner Binder dies and has a hard ten-minute maximum
lifetime from process start. Forced process termination, reboot, or other process death also
ends it. This is an experiment and must be launched over USB ADB; do not enable wireless ADB
for this mode.
