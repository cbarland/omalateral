# Shizuku activation and helper recovery

LATERAL uses one privileged server and one operation facade. Select **Privileged
helper** in PhoneUI configuration, then choose **Shizuku (recommended)** or
**Wireless ADB (self-contained)**. Upgrades retain Wireless ADB until explicitly
changed. A fresh installation defaults to Shizuku; that first choice is persisted.

## Shizuku setup

1. Install [Shizuku](https://shizuku.rikka.app/download/) on the phone.
2. Start Shizuku in ADB/shell mode. This release supports runtime v13 or later,
   UID 2000; root mode is explicitly unsupported.
3. Open LATERAL's helper panel and select Shizuku. Choose **Authorize LATERAL**
   when requested. Denied authorization is changed in Shizuku's authorized apps.
4. Wait for **Shizuku connected**. LATERAL's ADB pairing marker, discovery,
   pairing notification, and notification permission are unnecessary for this path.
5. Once running, the Shizuku UserService has no Wi-Fi dependency. Non-root
   Shizuku must be started again after a phone reboot.

The API/provider dependencies use library version 13.1.5. This is distinct from
the installed manager's version. The provider authority, UserService tag, ADB
process name, and ownership handoff are application-specific for stable/dev.
The Shizuku class is instantiated directly with a Context; its ADB main entry
point and orphan scan are not run. The UserService is non-daemon: it is owned by
the application process, rather than by an activity.

Use **Restart helper** after installing debug builds that reuse a versionCode.
**Stop helper** intentionally removes the remote service. Activity pause,
rotation, and recreation do not remove it. A backend switch waits for confirmed
shutdown; a failed stop retains the selected backend and reports the failure.
Shizuku disconnection never silently starts wireless ADB.

For a full SDK-free FlorisBoard build on a memory-constrained Windows session,
use a single worker and in-process Kotlin compilation. The successful validation
run appended these arguments to the commands below:

```powershell
"-Pkotlin.compiler.execution.strategy=in-process" `
"-Pkotlin.daemon.jvmargs=-XX:+UseSerialGC -Xmx1024m -XX:MaxMetaspaceSize=384m" `
"-Dorg.gradle.jvmargs=-XX:+UseSerialGC -Xmx2048m -Xms256m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" `
--max-workers=1
```

These change the validation processes only; repository JVM defaults are retained.

## Shared lifecycle

Startup attempts and helper generations are separate. Both backend callbacks
are correlated with the selected backend and current attempt. An identical
Binder is an idempotent success. A genuinely new Binder must pass protocol and
initialization checks before READY; initialization is bounded and independent
of the input queues. Existing AIDL IDs 48–50 are occupied in this checkout, so
the new protocol handshake uses ID 51. The reserved destroy transaction and
exit transaction retain their IDs.

A helper death immediately invalidates its session and queued pinch. Old
queued input, delayed Back checks, hotkey deliveries, and query responses
cannot act on a replacement. Canceled queries still complete with an unavailable
result. Input and pinch retain separate queues. Wireless helpers also link to
an application ownership Binder: stream closure is not treated as proof of
shutdown. Cleanup stops monitoring, cancels focus repair and touch guards,
restores IME selection where possible, closes native input, and releases displays.

Retained displays include their helper generation. Surface replacement within
one helper can reattach; helper replacement creates a new display and restores
the exact known task without launching a duplicate. Unavailable task snapshots
never become an empty workspace. Keyboard activation and verification are
canceled on loss. The previous-IME recovery record is cleared only after
successful restoration or verified selection of another IME; stale recovery
runs before a fresh keyboard session.

## Validation

```powershell
git submodule update --init --recursive
.\gradlew.bat :app:testStableDebugUnitTest :app:assembleStableDebug :app:assembleDevDebug "-Plateral.vitureSdk=false"
.\gradlew.bat :app:assembleStableDebugAndroidTest "-Plateral.vitureSdk=false"
```

Unit coverage includes fake backend selection, repeated startup, stop failure,
obsolete attempts, session loss, stale queued input/query delivery, retained
displays, and IME recovery outcomes. Existing USB admission tests remain in
place. `ShizukuHelperAndroidTest` requires an authorized v13+ shell Shizuku and
a clean installation with no LATERAL pairing marker. It checks repeated start,
protocol compatibility, and removal followed by Binder death. Device tests
must run on the supported ARM64 hardware; an emulator build is not a Beast
acceptance result.

### Local recovery validation results

The final source passed 98 stable debug unit tests in 24 suites, with zero
failures and zero skipped tests. Stable and dev debug APKs and the stable
Android test APK assembled successfully with `lateral.vitureSdk=false` and
the bounded JVM arguments above. Merged manifests contain distinct
`com.lateral.shizuku` and `com.lateral.dev.shizuku` authorities.
`git diff --check` passed. Android test sources compiled, but device tests
were not executed: only an x86_64 emulator was connected, while these APKs
and the Beast release target use ARM64. Local success does not satisfy the
physical release gate below.

### Hardware release gate

On the Nubia/Beast target, start Shizuku and host multiple apps. Turn Wi-Fi
completely off. Verify clicking, dragging, scrolling, pinching, typing, app
opening, exact task switching, and glasses reconnection. Helper generation,
hosted displays, and keyboard state must remain unchanged through Wi-Fi loss.
Close/reopen the activity separately from killing the app process.

Then independently test Shizuku stop/restart, helper death with queued input,
authorization denial/revocation, phone reboot, stable/dev side-by-side installs,
and existing ADB pairing. Confirm workspace identities/order/preferences
survive privilege loss and that stale keyboard restoration retries safely.
These are separate recovery scenarios; they do not substitute for Wi-Fi-off
operation. Physical target verification remains a release requirement.

## Recovery audit and corrected implementation process

The reference specification described commit `1bff99c`; the recovered checkout
started at `431d1dc` and already contained USB admission/ownership and AIDL IDs
48–50. Applying the older specification without reconciling those differences
would overwrite newer behavior and collide with transaction IDs.

The interrupted extraction also changed USB argument parsing and omitted an
AtomicLong import. The existing USB parser test exposed the argument-contract
regression; compilation exposed the missing import. Multi-file patch attempts
were being treated as if they were atomic despite partial application. Shell
inspection was additionally interrupted by a Windows sandbox ACL setup error;
that tool failure did not establish the repository's source state.

The correction is to inspect the actual branch and diff, preserve pre-existing
user files, separate wireless arguments from USB arguments, and use checked
file edits. Work is saved as three reviewable commits: ADB extraction, Shizuku
selection, then session recovery/tests/documentation. Each stage is compiled
or tested before progressing, and final APK/device claims are based on their
own results. Hardware acceptance is reported separately from local builds.

During final validation, the 1 GB Gradle daemon reached 99% tenured heap
occupancy and logged `OutOfMemoryError: Java heap space` during external dex
merging. A 4 GB rerun passed dex merging but a new Kotlin JVM then failed Windows
commit allocation. Windows reported about 69.2 GB committed against a 70.8 GB
limit. Releasing the two idle validation JVMs and using one worker, in-process
Kotlin, and a bounded 2 GB heap completed the unit tests and all three APK
builds successfully. These build-resource failures were separate from source
compilation errors; repository JVM defaults were retained.
