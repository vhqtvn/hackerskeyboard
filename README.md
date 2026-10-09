# VHKeyboard

A fork of [Hacker's Keyboard](https://github.com/klausw/hackerskeyboard), rebuilt as a
desktop-like keyboard for writing code on Android. Prediction and autocomplete are gone;
what is left is a full key layout with real modifiers, function keys and arrows, plus the
things a phone keyboard needs once you actually type on it all day.

It started as a Surface Duo experiment, grew a second-screen deck for the AYN Thor, and
is now mainly used on a book-style foldable. Those paths have not been merged into one
compromise: device-specific code is selected at build time, so each device gets only what
it can use.

## What it does

**A keyboard you can code on.** Ctrl, Alt, Meta, two Fn layers, Esc, F1–F12, arrows,
Home/End/PgUp/PgDn, Tab, Insert, forward-delete. Modifiers chord properly, so Ctrl+C and
Alt+Tab reach the app rather than being swallowed.

**Layout profiles.** Screen size crossed with orientation gives four profiles — a
foldable is really two keyboards, and the height and layout that suit the cover screen are
wrong for the inner one. Keyboard height, layout, hint mode, the split gap and the
function-key row are all set per profile. See [docs/layout-profiles.md](docs/layout-profiles.md).

**Split keyboard.** On a wide screen the middle is out of thumb reach, so the halves can
be pushed apart by up to 60%. Where a row divides is a touch-typing fact rather than a
geometric one, so layouts declare it: `y` and `h` sit left of centre but belong to the
right hand.

**Vietnamese input.** The Unikey engine (bundled, unprivileged NDK code) composes Telex,
VNI or VIQR against the InputConnection the way ibus-unikey does. **Fn + ;** toggles it;
the key's label is solid while it is on and faded while it is off. It stands aside in
password and non-text fields, and nowhere else — a browser's address bar is also its
search box.

**Clipboard history.** **Fn + v**. Android never tells an IME about a copy made in another
app, so the clipboard is sampled whenever the keyboard is in a position to read it. Capped,
persisted, and refuses clips the source app marked sensitive.

**Reclaiming the space the system takes.** The strip the framework draws below the keyboard
for its switcher and back buttons can be hidden, giving that height back to the app above.
Its two buttons move onto the keyboard: **Fn + x** hides the keyboard, **Fn + m** opens the
input-method picker.

**AYN Thor second screen.** On that device the keyboard runs as a deck on the second
display and injects keystrokes into whatever is focused on the first, through a bundled
root daemon over `/dev/uinput` — plus a trackpad, gamepad-to-mouse, and a recent-apps
switcher. See [docs/ayn-thor.md](docs/ayn-thor.md).

## Fn layers

Two layers, coloured to match their Fn key: **Fn1 green**, **Fn2 yellow**.

| Fn1 (green) | | Fn2 (yellow) | |
|---|---|---|---|
| `;` | Vietnamese on/off | `` ` `` | Esc |
| `v` | clipboard history | `1`–`0`, `-`, `=` | F1–F12 |
| `x` | hide the keyboard | `⌫` | forward delete |
| `m` | input-method picker | `◁ ▽ △ ▷` | Home, PgDn, PgUp, End |
| `s` | sticky keyboard | | |
| `i` | bypass the IME (send raw key events) | | |
| `o` | orientation lock | | |
| `.` | settings | | |

With the function-key row switched off, Esc, F1–F12 and forward-delete are reachable on
Fn2 exactly as above, and the top-left key becomes Esc outright — the way a 60% keyboard
behaves, with the backtick moving to Fn2.

## Layouts

| | columns | for |
|---|---|---|
| `kbd_full` | 14 | the full layout: F-row, number row, and everything above |
| `kbd_compact` | 11 | narrower, with a toggleable extension row |
| `kbd_thumb` | 10 | big targets for thumbs on a short panel — a cover screen, or the Thor's second display |

Physical key width is panel width divided by column count; density does not enter into it,
since keys are sized as a percentage of the width. On a Fold's 64mm cover screen that is
4.6mm per key with `kbd_full` against 6.4mm with `kbd_thumb`, which is the difference
between unusable and workable.

## Build

Two product flavors on a `device` dimension. Device-specific code, permissions and native
binaries are chosen at compile time, not guarded at runtime — see
[docs/flavors.md](docs/flavors.md).

| flavor | target | extras |
|---|---|---|
| `generic` | phones, foldables, tablets | none: no root, no overlay service, no extra permissions |
| `thor` | AYN Thor | second-screen deck, uinput injector, signature permissions |

```sh
./gradlew assembleGenericDebug      # phones and foldables
./gradlew assembleThorDebug         # AYN Thor
```

Toolchain: AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.4.21, compileSdk 36, targetSdk 36,
minSdk 24, NDK 29 (which aligns native libraries to 16 KB pages, as Android 15 devices
require). Build on **JDK 25** — Android Studio's bundled JBR works:

```sh
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleGenericDebug
```

Both flavors keep the same `applicationId`, so they are not installable side by side.
That is intended: a device runs one of them.

### Native code

- `cpp/ukengine` + `unikey_jni.cpp` → `libunikey.so`, the Vietnamese engine. Unprivileged,
  so it ships in every flavor. Its JNI symbols are derived from `Unikey`'s package name —
  moving that class renames all of them.
- `cpp/kbdinject.c` → `libkbdinject.so`, the Thor's uinput daemon. Built only when the
  `thor` flavor passes `BUILD_KBDINJECT=ON`.

## Documentation

- [docs/layout-profiles.md](docs/layout-profiles.md) — profiles, split keyboard,
  Vietnamese input, clipboard, hiding the system keyboard bar, and display cutouts
- [docs/flavors.md](docs/flavors.md) — the flavor split, the `DeviceIntegration` seam,
  toolchain notes and the targetSdk migration
- [docs/ayn-thor.md](docs/ayn-thor.md) — the second-screen deck: injection, overlay,
  root setup and the device's quirks

## Licence

Apache 2.0, inherited from Hacker's Keyboard and the Android Open Source Project.

The bundled Unikey engine in `app/src/main/cpp/ukengine/` is **LGPL v2** and keeps its own
`LICENSE` there. It is compiled into `libunikey.so` and linked over JNI, so it stays a
separate shared library rather than being folded into the app's own code.
