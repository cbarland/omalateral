# omalateral source provenance

This independent application combines LATERAL's external Android workspace with
Omalauncher's Android desktop. It is not an official Omalauncher or Omarchy release.

- LATERAL main: https://github.com/CB-Soft/LATERAL_, commit
  `39e807cb79467dab6b3b3bf10282201a529f18e5`. Apache-2.0; existing LICENSE,
  LICENSE-UXSPACE and NOTICE retained.
- Local LATERAL `feature/omarchy-themes` working changes, including the live
  PhoneUI scrollbar background correction, imported on 2026-10-11.
- Omalauncher: https://github.com/SimonSchubert/omalauncher, commit
  `ada9c7b811a352c0a9ec4a6fdb3aa840ec643215`. Vendored in
  `third_party/omalauncher` with LICENSE and THIRD_PARTY.md retained. Its MIT
  code is compiled through separate `omalateral.gradle.kts` library adapters
  in its app and owl directories. The integration manifest is under `integration/`.
- FlorisBoard remains the original pinned submodule; see its license notices.

Omalauncher modifications: RuntimeOwner interface for the combined Application;
Android launch callback for hosted workspace tasks; drawer/shortcut launch routing;
package-relative file provider; phone-side search submission; a Kotlin 2.2-compatible
canvas helper; optional desktop-host hooks for dialog/popup cursor overlays and
phone-owned input connections (including terminal tiles); dialog focus requested
inside its attached composition. The host hooks default to null in standalone
Omalauncher. The separate integration manifest retains upstream
Linux window activities and services but does not register a second home launcher.

PRoot dependencies are downloaded from the exact versions and checksums in
upstream's fetch-proot.sh. On Windows run `scripts/fetch-oma-proot.ps1`.
Their license texts and source links are retained in Omalauncher's assets and
licenses screen. A Linux rootfs is never bundled or installed automatically.

Build the independent package `com.omalateral` with
`gradlew.bat :app:assembleStableDebug -Plateral.vitureSdk=false`.
Omalateral source and alpha releases: https://github.com/cbarland/omalateral

## Upstream proposal: permission-controlled external desktop bridge

Submitted for upstream discussion: https://github.com/SimonSchubert/omalauncher/issues/34

Status: design proposal for discussion, not an implemented or agreed protocol.

### Motivation

Would you be interested in a general external desktop bridge that lets an
authorized Android desktop host use an existing Omalauncher installation as its
desktop and widget provider on an external display, alongside the phone home?

The aim is to reuse the user's Linux installation, agent configuration and
authentication, widgets, and themes without a second setup or copying private
app data. I am exploring this for omalateral, but the contract should work for
any authorized desktop host, independently of a particular app, dock, or device.

### Ownership and user experience

- Omalauncher owns the Linux runtime, agent execution, credentials, widget
  execution, and permission checks. These remain inside its security boundary.
- The host owns external-display composition and its own window-management UI.
  Omalauncher supplies an independent desktop session for that display.
- Phone and external desktops remain usable simultaneously. Widget definitions
  and selected settings can be shared, with separate per-display placement
  profiles so connecting a monitor does not rearrange the phone home.
- The host discovers the bridge automatically. Omalauncher asks the user to
  approve the connecting application before exposing desktop content or runtime
  access. Subsequent connections can be automatic while approval remains valid.
- Disconnecting closes the external session, not the phone home or shared Linux
  and agent workloads. An absent or incompatible bridge leaves the host's normal
  standalone behavior intact, with a clear connection status.

### Proposed contract boundaries

Names and transport are intentionally provisional; this is a scope discussion,
not a request to adopt a finished API.

1. **Discovery and negotiation:** a documented endpoint, protocol version,
   capabilities, and consent status. Discovery alone must not start Linux,
   expose private configuration, or be reported as a successful connection.
2. **Desktop sessions:** open, resize, and close using display metrics and
   connection-scoped handles. Agree on rendering ownership and cross-process
   surface lifecycle before implementation. A provider-rendered desktop would
   preserve existing widgets without requiring each host to reimplement them;
   exporting widget definitions/state would be a separate optional capability.
3. **Session-scoped interaction:** keyboard, pointer, context click, focus, IME,
   and bounded UI actions such as search, widget picker, and dismiss. Events and
   asynchronous results must retain the originating session identity.
4. **Launch routing:** Android launch requests go back to the originating host,
   subject to ordinary platform restrictions. Linux and agent presentation needs
   an explicit capability and session-aware routing, not a global callback that
   redirects the phone's launches. Cross-process Wayland window presentation
   should be designed separately rather than assumed to work automatically.
5. **Lifecycle and status:** consent-required, unsupported, connecting, connected,
   disconnected, and failed states; provider/host process-death cleanup;
   display unplug/replug; and removal or revocation of authorized clients.

### Security and non-goals

- Validate actual Android caller identity, binding approvals to package and
  signing identity rather than trusting caller-supplied names. Different hosts
  must not need Omalauncher's signing key. Explicit revocation should invalidate
  sessions and their handles immediately.
- A local Binder/AIDL API could be suitable, but simply exporting LinuxService
  is not the proposal. Keep the service surface small and capability-controlled;
  do not add an unauthenticated arbitrary-command endpoint.
- No root/shared UID requirement, app-private directory access, credential
  transfer, rootfs copy, or replacement of the user's installed Omalauncher.
- This is a local external-desktop bridge, not a network remote-control service.
  It does not bypass Android restrictions on launching or embedding other apps;
  hosts remain responsible for any platform privileges their own UI requires.

### Suggested incremental scope

Start with discovery, consent/revocation, and one independently rendered external
home/widget session while the phone home stays active. Keep Linux/agent window
presentation behind separately negotiated capabilities until session-aware
routing and cross-process rendering are agreed. This avoids coupling the first
bridge version to a particular host's task model or an entire windowing protocol.

Acceptance coverage should include simultaneous phone/external use, reuse of
existing widgets and configuration without a new rootfs, unauthorized callers,
revocation, signing-identity changes, stale handles, process death, display
disconnect/reconnect, protocol mismatch, input/IME isolation, and absent-provider
fallback. Later Linux/agent capabilities should also verify reuse of existing
installation/authentication and correct originating-session routing.

Would this fit Omalauncher's direction? If so, which rendering boundary and
smallest initial capability set would you prefer for an upstream contribution?
