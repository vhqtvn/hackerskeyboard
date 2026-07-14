# AYN Thor dual-screen keyboard (`ayn-thor` branch)

Adapt VHKeyboard (`vn.vhn.pckeyboard`, a Hacker's Keyboard fork) into a keyboard for the
**AYN Thor** dual-screen handheld, used as a development machine. The second screen becomes
a keyboard deck; keystrokes are injected into whatever app is focused on the main screen.

Reference material (read-only) lives outside the repo at `~/devices/ayn-thor/`. We do **not**
like DSKeyboard's style/handling but reuse its findings (`DSKeyboard/REFERENCE.md`).

## Device topology

| Display | Role       | Size (portrait) | layerStack | touch    | notes |
|---------|------------|-----------------|------------|----------|-------|
| 0       | main       | 1080×1920       | 0          | INTERNAL | landscape override 1920×1080 |
| 4       | "Screen-2" | **1080×1240**   | 4          | EXTERNAL | near-square, FLAG_PRESENTATION, uniqueId `local:4630946482288158084`, port 132 |

Rooted (Magisk). `su -c` works non-interactively (context `u:r:magisk:s0`). adb id `dce658d8`,
codename `kalama` (SD8 Gen 2).

## Core decisions

1. **Injection, not InputConnection.** The keyboard view lives on display 4 but keystrokes must
   reach the focused editor on display 0. We inject at the kernel level via `/dev/uinput`.
   `/dev/uinput` is `crw-rw-rw-` but SELinux only lets root domains open it, so injection runs in a
   **bundled native root daemon** launched via `su`. uinput key events follow input focus, so a
   `FLAG_NOT_FOCUSABLE` overlay on display 4 types into display 0's focused field automatically —
   no per-display targeting or `InputManager.injectInputEvent` reflection needed.
   (Pattern mirrors the user's `~/devices/ayn-thor/joyscroll/joyscroll.c`.)

2. **Configurable toggle** (floating icon on display 4, à la DSKeyboard minimized state).
   - Toggle ON  → persistent keyboard on display 4, always-on but *auto-focus* (greys when no
     editor is focused, but stays up).
   - Toggle OFF → normal on-focus IME behavior; display 4 is free for other uses.

3. **Reuse HK view stack.** `LatinKeyboardView` + XML layouts render the keys; the
   `LatinKeyboardBaseView.OnKeyboardActionListener.onKey(...)` callback is the injection hook
   (`LatinIME` currently implements it via `InputConnection`). The standalone overlay inflates the
   layout itself (the `KeyboardSwitcher` singleton is bound to an `InputMethodService`).

4. Existing LG / Surface-Duo dual-screen code in the repo is clamshell-specific (force-landscape
   IME) and does **not** apply to Thor's two-physical-display topology. Build a fresh Thor path.

## Injection primitive notes (verified on device)

- `su -c id` → uid 0, context `u:r:magisk:s0`. ✅
- `input [-d DISPLAY_ID] ...` exists (can target a display) but spawns a process per event —
  usable for manual testing, too slow for a keyboard.
- `/dev/uinput` → `crw-rw-rw- uhid uhid u:object_r:uhid_device:s0` — DAC-open to all, SELinux to root.

## Native daemon packaging

Built by `app/CMakeLists.txt` as `libkbdinject.so` (executable-in-`.so` trick so AGP packages it),
with `extractNativeLibs` true so it lands runnable at `applicationInfo.nativeLibraryDir/`.
Launched via `su -c <path>`; app talks to it over its stdin (or a unix socket). Single ABI
(arm64-v8a) is fine — personal device only.

## Build notes (important)

- **JDK**: system `java` is 26; Gradle 8.5 needs ≤21. Build with
  `JAVA_HOME=/opt/android-studio/jbr ./gradlew ...` (JBR 21).
- **NDK** pinned to `25.1.8937393` in `app/build.gradle` (`ndkVersion`).
- The old `jni_pckeyboard` CMake target referenced dictionary `.cpp` files that were
  deleted in the Kotlin migration (nothing loads that lib) — `CMakeLists.txt` now builds
  only `kbdinject`. The `externalNativeBuild { cmake { path "CMakeLists.txt" } }` link was
  also missing from the `android {}` block, so native never built before; now fixed.
- ABI restricted to `arm64-v8a`; `packagingOptions.jniLibs.useLegacyPackaging = true` so the
  binary is extracted (runnable) rather than compressed in-place.

## Status

- **Phase 1 DONE & validated on device.** `cpp/kbdinject.c` + CMake target → `libkbdinject.so`
  packaged and extracted to `nativeLibraryDir`. Verified: run via `su` from `/data/app`, it
  types into display-0's focused editor (Chrome omnibox test: "hello world", "vhkbd").
  Kotlin `thor/InputInjector.kt` (daemon lifecycle over `su`, line protocol) +
  `thor/KeyMap.kt` (Android keycode → evdev) are code-complete; exercised for real in Phase 2.
- **Phase 2 DONE & validated on device.** `thor/OverlayKeyboardService` (foreground) draws
  HK's full `LatinKeyboardView` on display 4 as a `FLAG_NOT_FOCUSABLE` overlay via
  `createDisplayContext`; `thor/ThorKeyboardHost` routes `onKey` → uinput injection (modifiers
  as one-shot toggles, no re-shift). Floating toggle button shows/hides it. `LanguageSwitcher`
  now takes a `Context` so the stack builds outside the IME. Verified: tapping keys on screen 2
  types into the focused editor on screen 1; toggle works.
  Runtime setup on device: `appops set vn.vhn.pckeyboard SYSTEM_ALERT_WINDOW allow` and
  pre-authorize su in Magisk (`magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (<uid>,2,0,0,0)"`).
  Start: `am start-foreground-service -n vn.vhn.pckeyboard/.thor.OverlayKeyboardService`.
  Note: both displays are FLAG_SECURE, so `screencap` needs the **physical** display id, not
  logical `4`: `screencap -p -d 4630946482288158084 <file>` (main = `4630946441858561667`).
  See `dumpsys SurfaceFlinger --display-id`.
- **Deck layout:** full-screen opaque `FrameLayout` on display 4; keyboard bottom-anchored
  `WRAP_CONTENT` at 68% height (must stay a bottom-anchored wrap-content child — a weighted
  LinearLayout breaks the keyboard's buffered rendering, drew all black), utility strip on top
  (trackpad/dev-tools placeholder), ▾ minimize button; floating ⌨ button restores when minimized.
- **Phase 3 core DONE & validated on device.** Our app is the selected IME; `ThorController`
  (in-process flag) makes `LatinIME.onEvaluateInputViewShown()` return false while the deck is
  shown, so no keyboard appears on screen 1 (DSKeyboard/Gboard suppressed). Set default IME:
  `ime enable vn.vhn.pckeyboard/.LatinIME && ime set vn.vhn.pckeyboard/.LatinIME`.
  Current minimized behavior: focusing a screen-1 field shows our HK on screen 1 (normal IME).
  Still TODO: auto-focus greying of the deck when no editor is focused; option to re-pop the
  deck on focus instead of showing a screen-1 keyboard.

## Phases

1. **Native uinput injector + Kotlin bridge** — `cpp/kbdinject.c`, CMake target, `InputInjector.kt`
   over `su`. Validate: type into a display-0 editor.
2. **Overlay keyboard service on display 4** — foreground service, floating toggle (56dp), expand to
   full HK keyboard on display 4 as non-focusable overlay, wire `onKey` → injector.
3. **Mode switching + IME integration + auto-focus** — toggle on/off, LatinIME reports focus state
   for greying, persist state, autostart.
4. **Config UI, display targeting, square-screen extras** — settings, robust display selection,
   near-square layout with a future utility strip (macros / clipboard / dev shortcuts).

## Protocol (daemon control channel) — draft

Line-based over stdin, one command per line:
- `D <evdevcode>` — key down
- `U <evdevcode>` — key up
- `T <evdevcode>` — tap (down+up)
- `S` — sync/flush
Modifiers are just their own evdev keycodes (KEY_LEFTSHIFT=42, KEY_LEFTCTRL=29, KEY_LEFTALT=56,
KEY_LEFTMETA=125). The Kotlin side brackets a key with modifier down/up as needed.
