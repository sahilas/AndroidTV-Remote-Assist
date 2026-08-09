# Design — what an AccessibilityService can and cannot do here

Companion app for [AndroidTV-Web-Remote](https://github.com/sahilas/AndroidTV-Web-Remote).
This document exists because the reason this project was proposed was **partly wrong**, and
building before correcting it would waste the effort.

## The claim that was wrong

The parent repo said the air-mouse and hold-OK were unavailable on locked boxes, and that
"a companion APK with an AccessibilityService" would close both. The word doing the damage
was *inject*.

An AccessibilityService **does not inject input events**. It has no `INJECT_EVENTS`
permission — that is signature-level and unavailable to any sideloaded app. What it has is:

- `performGlobalAction(...)` — a fixed enum: BACK, HOME, RECENTS, NOTIFICATIONS,
  QUICK_SETTINGS, POWER_DIALOG. `GLOBAL_ACTION_DPAD_*` exists only from **API 34**;
  both boxes tested run **API 31**, so it is not available there.
- `AccessibilityNodeInfo.performAction(...)` — CLICK, LONG_CLICK, FOCUS, SET_TEXT, SCROLL —
  on nodes the focused app chooses to expose.
- `dispatchGesture(...)` — **absolute touch strokes**, not a cursor.

## What is actually achievable

| Goal | Verdict | Mechanism |
|---|---|---|
| **Boot persistence on a locked box** | ✅ Real | The system starts an enabled AccessibilityService on every boot. No BOOT_COMPLETED receiver, no foreground service, no writable `/vendor`. This is the strongest reason for the app to exist. |
| **Hold-OK → context menu** | ⚠️ Partly | `ACTION_LONG_CLICK` on the focused node. Works where the app exposes a long-clickable node. |
| **Typing** | ✅ Likely better | `ACTION_SET_TEXT` sets the whole string at once — no per-character `input text` fork, and it sidesteps the caps-latch bug documented in the parent repo. |
| **D-pad / media keys** | ➖ Not needed | The Go server already does these on locked boxes via Android's `input` command. The app adds nothing. |
| **Air-mouse / relative cursor** | ❌ Not achievable | No path exists for an unprivileged app. See below. |

### Why the air-mouse cannot be recovered

Two independent reasons, either sufficient:

1. `dispatchGesture` produces **absolute touch strokes**, not relative pointer motion. There
   is no cursor to move.
2. An Android TV UI consumes **D-pad focus**, not touch. Even a perfectly delivered touch
   event at (x, y) does nothing in a leanback app.

Hardware note, since it is easy to reach for the wrong evidence: the Google ATV **emulator**
declares `android.hardware.touchscreen`, and so does the HiSilicon projector — a device with
no touchscreen whatsoever. The feature flag is not a reliable signal, and it is not the
reason the pointer is unavailable. The reasons above are.

**The pointer stays a rooted/Permissive-box feature.** The parent repo should say so
plainly rather than implying this app will fix it.

### Why hold-OK is narrower than what it replaces

The Go server injects at the **evdev layer, below the app**, which is why its held-OK is
indistinguishable from a physical remote and works everywhere.

`ACTION_LONG_CLICK` acts at the **node layer, above the app**. It only exists where the
focused view exposes it. SurfaceView-based video players and games frequently expose no
accessibility node at all. Expect this to work in launchers, settings and list-based UIs,
and to do nothing in exactly the media apps where the parent repo measured its original
evdev hold as most useful.

This must be measured per app, not assumed. Failure must surface as an error, never a
silent no-op reported as success.

## Architecture — the fork to decide first

Persistence comes from whatever the system starts at boot, which means the APK is the
entry point. Two options:

### A. APK hosts everything
A Kotlin HTTP server plus the AccessibilityService. Self-contained, but it means **two
server implementations to maintain**, and it strands the existing Go codebase as
rooted-boxes-only.

### B. APK hosts the existing Go binary *(preferred)*
Ship the Go binary as a bundled native library, start it from the app at boot, and have it
call back into the app over loopback for node-level actions. Everything already built —
token gate, TLS, capability probe, embedded UI, ABI selection — is preserved.

**Blocking question for B, to settle before any directory layout is fixed:** can an app on
API 31 exec a bundled binary? W^X forbids exec from `filesDir`; the supported route is
`nativeLibraryDir` with `android:extractNativeLibs="true"` and the binary named `lib*.so`.
Verify with a throwaway APK — do not design around an assumption here.

If B is not viable, A is the fallback, and the honest consequence is that the two projects
diverge permanently.

## Enabling the service without TV menus

Measured on the emulator — `settings put secure` succeeds from `adb shell`:

```bash
adb shell settings put secure enabled_accessibility_services dev.sahilas.tvassist/.RemoteAssistService
adb shell settings put secure accessibility_enabled 1
```

This matters more than it sounds. The alternative is talking a user through
Settings → Accessibility on a TV with no working remote — which is the exact situation this
project exists to solve. The parent repo's `deploy.sh` can do this in one line.

Verify the write took, since a silent failure here looks identical to a service that will
not start:

```bash
adb shell settings get secure enabled_accessibility_services
```

## Status

Scaffold only. Gradle project, manifest, service config and a stub that compiles. **No
transport, no wiring to the Go server, nothing installed on a device yet.**

Nothing in this document has been tested on a Fire TV. Both boxes referenced are a HiSilicon
projector (`userdebug`, Permissive, API 31) and a Google ATV emulator image (`user`,
Enforcing, API 31).

## First tasks, in order

1. Throwaway APK: can it exec a binary from `nativeLibraryDir` on API 31? Decides A vs B.
2. Install the scaffold, enable it over adb, confirm `onServiceConnected` fires on a real box.
3. Measure `ACTION_LONG_CLICK` coverage across a launcher, a settings screen, and a
   SurfaceView player. Record which apps expose a long-clickable node — that table is the
   real answer to whether hold-OK is worth shipping.
4. Only then pick a transport and wire it.
