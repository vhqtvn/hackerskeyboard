# Product flavors: one APK per device

This fork started as a Surface-Duo keyboard, grew a large set of AYN Thor features
(root, uinput injection, a second-screen deck, gamepad and recent-apps integration),
and is now also used on ordinary phones and foldables. Those Thor features cannot
run — and should not even be *present* — on a non-rooted phone.

So device-specific code, permissions and native binaries are selected **at compile
time** via a `device` product-flavor dimension, not guarded at runtime.

## Flavors

| Flavor    | Target                              | Extras |
|-----------|-------------------------------------|--------|
| `generic` | phones, foldables, tablets (default) | none — no root, no overlay service, no extra permissions |
| `thor`    | AYN Thor dual-screen handheld        | second-screen deck, uinput injector, signature perms (see `docs/ayn-thor.md`) |

Build: `./gradlew assembleGenericDebug` / `assembleThorDebug`.

Both flavors keep the same `applicationId`, so they are *not* installable side by
side — which is intended: a device runs exactly one of them.

## The seam

Shared code never references a flavor's packages. It talks only to:

    vn.vhn.pckeyboard.device.DeviceIntegration

Each flavor ships its own object under that exact fully-qualified name
(`app/src/generic/...` and `app/src/thor/...`). The generic one is entirely inert.
Keep this API small; if a feature needs more than a hook or two, that is a sign the
feature belongs in shared code behind a preference instead.

Current hooks: `suppressMainScreenKeyboard`, `attachIme`, `detachIme`,
`onEditorFocus`, `onMainActivityCreated`.

## What lives where

- `app/src/main/` — the keyboard itself, and anything that can run anywhere.
  This includes the bundled Unikey engine (`cpp/ukengine`, `libunikey.so`): it is
  plain NDK code with no privileges, so Vietnamese input is available to every device.
- `app/src/thor/` — `java/` (the `thor` package + its `DeviceIntegration`), `aidl/`
  (`ITaskStackListener`), `res/xml/kbd_thor.xml`, and `AndroidManifest.xml` holding
  the service, the boot receiver and the privileged permissions.
- `app/src/main/cpp/kbdinject.c` stays in main, but `CMakeLists.txt` only builds it
  when `BUILD_KBDINJECT=ON`, which only the `thor` flavor passes. Verified: the
  generic APK contains `libunikey.so` and no `libkbdinject.so`.

## Adding a device

Add a flavor, add `app/src/<name>/java/vn/vhn/pckeyboard/device/DeviceIntegration.kt`
(copy the generic one and fill in), and add a manifest only if the device needs
extra components or permissions.

## Toolchain

AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.4.21, compileSdk 36, built on **JDK 25**
(Android Studio's bundled JBR works: `JAVA_HOME=/opt/android-studio/jbr`).

A few things that upgrade forced, worth knowing before touching the build files:

- AGP 9 has **built-in Kotlin support** — there is no `kotlin-android` plugin to apply,
  and `kotlinOptions` is now a `kotlin { compilerOptions { ... } }` block.
- `compileSdkVersion`/`minSdkVersion`/`targetSdkVersion` are gone; use
  `compileSdk`/`minSdk`/`targetSdk`, and `packagingOptions` is now `packaging`.
- `buildToolsVersion` is pinned, because AGP 9.4 otherwise defaults to build-tools
  36.0.0 and tries to auto-install it.
- Gradle 10 will drop Groovy space-assignment (`namespace 'x'`) and
  `Project.getProperties`, so the build files use `namespace = 'x'` and
  `findProperty(...)` throughout. One exception: `cppFlags ""` stays a method call,
  since it appends to a list rather than assigning.
- **JCenter is gone**, and with it `me.jfenn:crasher` — it had been resolving only
  from a stale local Gradle cache. Replaced by `vn.vhn.pckeyboard.CrashReporter`, a
  small in-repo uncaught-exception screen with no dependencies.
- **Jetifier is no longer needed** and has been removed from `gradle.properties`;
  `com.microsoft.device.display:display-mask` builds fine without it.

### Still on targetSdk 28

`targetSdkVersion` is 28 while `compileSdk` is 36. That is deliberate for now:
raising it is not a number change, it is a behaviour change with several
specific consequences to handle first.

- **targetSdk 33+** makes `POST_NOTIFICATIONS` a runtime permission. The keyboard
  posts an ongoing notification (`PREF_KEYBOARD_NOTIFICATION`), which would
  silently stop appearing until something requests the permission.
- **targetSdk 34+** requires every foreground service to declare a
  `foregroundServiceType` and hold the matching permission. The Thor deck
  (`OverlayKeyboardService`) is a foreground service and would fail to start.
- **targetSdk 30+** changes window-insets behaviour, which this IME already
  works around by hand in `LatinIME.onCreate` (`setDecorFitsSystemWindows`).
- **targetSdk 35** enforces edge-to-edge for activities, affecting the settings
  screens.

None of that can be checked without a device, so it is left as a deliberate
to-do rather than a blind bump.

### Dependencies

Every dependency is checked against actual source usage; several were carried
for years without a single import (`androidx.window`, `constraintlayout`,
`kotlinx-coroutines`, a 2011 `voiceimeutils.jar`) and have been dropped.

Two constraints worth remembering:

- `core-ktx` stops at **1.18.0**; 1.19.x requires compileSdk 37.
- `display-mask` stays at **0.3.0** -- Microsoft never published a newer one.
  Jetifier used to be kept on for its sake, but the artifact turns out to be a
  single class with no `android.support` references at all, so Jetifier is gone.
- JCenter is fully shut down (it redirects to Maven Central, which 404s). Nothing
  may depend on it; anything that still resolves from it is only working out of a
  stale local Gradle cache.
