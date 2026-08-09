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

Summary after measurement: **the app is worth building for persistence and for hosting the
Go server, not for input.** Both input capabilities it was proposed to restore are
unavailable.

| Goal | Verdict | Mechanism |
|---|---|---|
| **Boot persistence on a locked box** | ✅ Real | The system starts an enabled AccessibilityService on every boot. No BOOT_COMPLETED receiver, no foreground service, no writable `/vendor`. This is the strongest reason for the app to exist. |
| **Hold-OK → context menu** | ❌ **Measured: does not work** | See "Hold-OK was measured and it failed" below. |
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

### Hold-OK was measured and it failed

This was expected to be the headline capability. It was tested on the Google ATV image
across the three most representative surfaces, and **no node anywhere near the focus
declares `ACTION_LONG_CLICK`**:

| Context | Focused node | Long-click target found? | `longPressFocused()` |
|---|---|---|---|
| TV launcher (`com.google.android.tvlauncher`) | `android.widget.GridView` | none, searching descendants *and* ancestors | `false` |
| Settings (`com.android.tv.settings`) | **no focus at all** (`windows=1`) | none | `false` |
| YouTube TV | `android.view.View` | none | `false` |

The prediction below — that it would work in launchers and list UIs and fail only in
SurfaceView players — **was wrong**. It failed in the launcher too.

The reason is structural: Android TV UIs are D-pad driven, and a long press is handled by
the app's own `KeyEvent` handling with `FLAG_LONG_PRESS`, not by an exposed accessibility
action. There is usually no long-clickable node to find, because the interaction was never
modelled as a click.

**Trap worth keeping.** The first implementation reported success here. `performAction(
ACTION_LONG_CLICK)` returned `true` on the launcher's `GridView` — a node whose `actionList`
is `[2, 4, 8, 64, …]` and does not contain `ACTION_LONG_CLICK` (32). The return value is not
evidence the action happened. Any implementation must check `actionList` itself, or it will
report a no-op as a working button, which is worse than an error because the user cannot
tell it from a dead one.

### Why hold-OK was expected to be narrower anyway

The Go server injects at the **evdev layer, below the app**, which is why its held-OK is
indistinguishable from a physical remote and works everywhere.

`ACTION_LONG_CLICK` acts at the **node layer, above the app**, so it only exists where the
focused view chooses to expose it. The reasoning above predicted this would still work in
launchers and list UIs and fail only in SurfaceView players. The measurement says otherwise:
it failed everywhere tested, because TV UIs generally do not model long-press as a click
action at all.

Kept as a record of how the wrong estimate was reached — the layer argument was right, the
optimism about coverage was not. Failure must surface as an error, never a silent no-op
reported as success.

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

**Settled: B is viable.** Measured on the Google ATV image (API 31, arm64) — the Go server
binary was shipped as `jniLibs/arm64-v8a/libtlsproxy.so` with
`android:extractNativeLibs="true"` and `useLegacyPackaging = true`, and the app exec'd it
from `nativeLibraryDir`:

```
exec=ok exit=0 canExec=true out=<Usage of /data/app/~~…/lib/arm64/libtlsproxy.so: …>
```

It ran and printed its own usage, so this is a real exec, not a permissions artefact.
`useLegacyPackaging` is load-bearing: a library compressed inside the APK is not a file on
disk and cannot be executed.

**So the plan is B.** Everything already built in the parent repo is preserved, and the app
contributes what the parent cannot do: surviving a reboot on a locked box.

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

## Universal release build — tested on both boxes

`./gradlew assembleRelease` produces one **universal** APK: every ABI in a single file, each
carrying its own copy of the parent repo's Go server. A per-ABI split would be smaller but
would mean picking the right file by hand at sideload time, which is worse for a project
whose whole point is that the TV has no working remote.

Signed with a local key (`release.keystore` + `keystore.properties`, both gitignored). A
fresh clone with no key still builds — the release variant falls back to unsigned rather
than failing, so a contributor is not blocked on being handed signing material.

| | Emulator | Projector |
|---|---|---|
| box | Google ATV image | HiSilicon Hi3751V350 (Zeasn Whale OS) |
| ABI / SDK / SELinux | `arm64-v8a` / 31 / **Enforcing** | `armeabi-v7a` / 31 / Permissive |
| install | ✅ | ✅ |
| service binds | ✅ | ✅ |
| ABI-correct binary selected | ✅ `lib/arm64` | ✅ `lib/arm` |
| exec from `nativeLibraryDir` | ✅ `exit=0 canExec=true` | ✅ `exit=0 canExec=true` |

Both ran the binary and got its usage output back, so these are real execs on both
architectures. The projector result matters most: it is a non-Google-certified build, and
its accessibility framework works anyway.

APK is 13 MB for all four ABIs; `aapt` confirms it is not debuggable.

### The probe is off unless you ask for it

The release build registers **no** receiver by default. Enable it deliberately:

```bash
adb shell setprop debug.tvassist.probe 1
```

Verified on the emulator: with the property cleared, the service connects, registers nothing,
and a broadcast produces no result at all. The property does not survive a reboot, so it
cannot be left on by accident. This replaces the earlier always-on receiver, which would have
let anything on the device drive the focused UI.

## Boot launch works — and exposed a blocking conflict

The service now provisions the server's files into its own storage and starts the binary on
connect, which the system does on every boot. **Persistence is proven**: after a full device
reboot with no intervention, `ps` showed

```
u0_a114  libtlsproxy.so -listen :8543 -dir /data/user/0/dev.sahilas.tvassist/files/tvremote
```

and the server answered 401 without a token, 200 with one, served the embedded UI, served
`/ca.crt` in the clear, and 404'd `key.pem`. That is the whole reason the app exists, and it
works.

### But the hosted server loses the features it had

Running under the **app** uid instead of **shell**, key injection, text and app launch all
fail:

```
keyevent volup(24): exit status 255
app list: android.intent.category.LEANBACK_LAUNCHER: exit status 255
```

`/system/bin/input` and `/system/bin/cmd` are world-executable, so this is not a file
permission problem — `am`/`cmd` check the *calling uid's* privileges, and an app uid has
none of them. The shell uid does, which is why the same binary works when adb starts it.

So the two goals are currently in direct conflict:

| Launched by | Persistence | D-pad / text / app launch |
|---|---|---|
| `adb` as **shell** (parent repo today) | ❌ dies on reboot | ✅ works |
| this app as **app uid** | ✅ survives reboot | ❌ `exit status 255` |

A remote that survives reboot but whose every button 500s is worse than one that needs
restarting, so **this is not shippable as-is.**

### What could resolve it, and what cannot

- **App performs the actions itself, server just routes.** App launch is straightforward
  via `PackageManager` + `startActivity`. BACK and HOME exist as
  `performGlobalAction`. Text may work via `ACTION_SET_TEXT`.
- **D-pad is the blocker.** It is the core of a TV remote, and there is no accessibility
  route to it before **API 34** (`GLOBAL_ACTION_DPAD_*`). Both boxes tested are API 31, so
  on those it cannot be done at all.
- **Running the child as shell is not an option** — a process started by the app inherits
  the app's uid.

That makes the app's value conditional on the box: genuinely useful on API 34+, and on API
31 it can offer persistence only by giving up the D-pad. Worth measuring on an API 34 image
before building further.

Also degraded under the app uid: mDNS. `net.InterfaceAddrs()` returns nothing, logged as
`mdns: no non-loopback IPv4 found`, because interface enumeration is restricted for apps.
The IP still works; only the `.local` name is lost.

## Status

Scaffold. Gradle project, manifest, service config, and a service that builds, installs,
binds and has been measured on a real Android TV image. **No transport and no wiring to the
Go server yet.**

The probe `BroadcastReceiver` is gated behind the `debug.tvassist.probe` system property and
is absent from a release build unless explicitly enabled. See above.

Nothing in this document has been tested on a Fire TV. Both boxes referenced are a HiSilicon
projector (`userdebug`, Permissive, API 31) and a Google ATV emulator image (`user`,
Enforcing, API 31).

## First tasks, in order

1. ~~Can it exec from `nativeLibraryDir`?~~ **Done — yes.** Architecture B.
2. ~~Install, enable over adb, confirm `onServiceConnected`.~~ **Done.**
3. ~~Measure `ACTION_LONG_CLICK` coverage.~~ **Done — it does not work.** Drop hold-OK from
   the app's goals unless a different mechanism turns up.
4. Decide whether persistence alone justifies the app. It probably does: on a locked box the
   parent repo currently dies on every reboot, and this is the only route to fixing that
   without root.
5. ~~Make the app start the Go binary at boot.~~ **Done, and it works** — but see the
   conflict above: the hosted server cannot inject input under the app uid.
6. ~~Remove the debug receiver before anything ships.~~ **Done differently** — gated behind
   `debug.tvassist.probe`, so a release build registers nothing unless explicitly enabled.
7. Decide the conflict above. Test on an **API 34** Android TV image first: if
   `GLOBAL_ACTION_DPAD_*` drives a real TV UI, the app route is viable there and the design
   becomes "app performs actions, server routes". If not, this app is a persistence
   mechanism for a remote that cannot press anything, and should be abandoned.
8. Provisioning still needs solving. `key.pem` and `token` are `0600 shell` in the staging
   directory, so the app cannot read them (measured: `copied=2/4`, EACCES on exactly those
   two). Loosening them even briefly re-creates the world-readable-private-key bug the
   parent repo already fixed once. The clean answer is for the server to generate its own
   certificate and token on the device, so nothing is ever staged.
9. Test on real Fire TV hardware. Everything here is one emulator image.
