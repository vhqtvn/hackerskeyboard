# Layout profiles, split keyboard, and Vietnamese input

Three features aimed at using this keyboard on a book-style foldable (the target
device is a Galaxy Z Fold, unrooted), all of which work on any device.

## Layout profiles

A foldable is really two keyboards. Folded in portrait you want a narrow thumb
layout; unfolded in landscape you have a slate wide enough to split, and the
height and key scaling that suit one are wrong for the other. Android's single
pair of portrait/landscape settings cannot express that.

`KeyboardProfile` is screen size crossed with orientation:

| | portrait | landscape |
|---|---|---|
| **small** | `sp` | `sl` |
| **large** | `lp` | `ll` |

"Large" is `smallestScreenWidthDp >= 600` by default -- the scaled unit, the same
one CSS media queries work in.

Everything in this keyboard is already sized the way a CSS layout is: key widths are
percentages of the keyboard width (`keyWidth="7.14%p"`, effectively `vw`) and the
keyboard height is a percentage of the screen height (effectively `vh`). Classifying
the screen in the scaled unit keeps the whole system in one frame of reference.

dp rather than a physical measurement because the display-size / minimum-width
setting is a deliberate statement of how much content the screen should hold, and a
layout decision should honour it: turn a screen "bigger" and you have asked for the
denser keyboard. It is also the unit Android's own `sw600dp` resource qualifiers
use, so the keyboard classifies screens the same way the rest of the system does.

Measuring the screen's *short* side keeps the size half of a profile independent of
the orientation half; otherwise a cover screen held in landscape would be classed as
a large screen. Folding changes it, rotating does not.

600dp is that qualifier's own tablet line, and a Fold crosses it when it opens:
cover screen around 411dp, inner screen around 673dp at stock density. The threshold
is a preference, since both panel sizes and display-size settings vary.

**The trade-off, stated plainly.** Physical key size depends only on the panel width
and the column count -- it does not change with density. So raising the display size
far enough flips a screen to "large", which selects a denser layout, which does make
the keys physically smaller. That is the setting doing what it was asked to do, but
it means a comfortable configuration is a pairing of display size *and* profile
settings, not either alone.

**Unverified caveat.** For an Activity, `Configuration` reflects the *window* in
multi-window mode rather than the display. Whether an `InputMethodService` sees the
same has not been confirmed on a device. The purpose-built API for fold state is
`androidx.window`'s `FoldingFeature` -- the library was dropped during the dependency
audit because nothing imported it, and it is where to go if dp turns out not to
separate the two screens reliably.

Because this is the one thing that can look plainly broken -- a screen classed
wrongly silently applies the wrong layout -- the profiles screen reports what was
detected and the numbers behind it in all three units (dp, px and physical mm where
the panel reports a believable dpi). Only dp decides anything; the other two are
there because seeing them side by side is what makes a surprising classification
diagnosable. It prefers the values the *keyboard* resolved over the settings
activity's own, since in multi-window the two Configurations need not agree. The
same line goes to logcat as `Layout profile: ...` on every resolve.

### How a setting resolves

Overrides are stored under `"<base key>@<profile suffix>"`, e.g.
`settings_height@ll`. `ProfilePrefs` resolves in this order:

1. the profile override
2. the legacy per-orientation key, for settings that had one
   (`settings_height_portrait` / `settings_height_landscape`)
3. the plain global key
4. the built-in default

A profile nobody has touched stores nothing, so a device that only ever sees one
profile behaves exactly as it did before, and existing settings keep working.

Profile-scoped so far: keyboard height, keyboard layout mode, hint mode, the split
gap, and whether the extension row stays up.

## What to put in each profile

The layouts differ mainly in how many columns they pack into the width, which is
what decides whether a key is thumbable. Physical key width is panel width divided
by column count -- density does not enter into it, since the keys are percentages of
the width:

| | cover, folded (~64mm) | inner, portrait (~130mm) | inner, landscape (~143mm) |
|---|---|---|---|
| `kbd_full` (14 col) | **4.6mm** | 9.3mm | 10.2mm |
| `kbd_compact` (11 col) | **5.8mm** | 11.8mm | 13.0mm |
| `kbd_thumb` (10 col) | 6.4mm | 13.0mm | 14.3mm |

A fingertip pad is around 8-10mm wide. That table is most of the answer:

**Small portrait (cover screen).** `kbd_full` gives 4.6mm keys here, against a
fingertip pad of 8-10mm. That is not a thumb target, it is a dare. `kbd_thumb` is the only layout that clears a usable
width, and it was built for exactly this shape -- a short panel typed on with
thumbs -- with symbols on the surface so there is no page to flip to, and F-keys
and arrows on the inline Fn2 layer. Height around 40-45%: the cover screen is very
tall, so even 40% is a lot of pixels.

**Small landscape (cover screen).** Width stops being the constraint and height
becomes one: the panel is only about 411dp tall, so a six-row keyboard at 55%
leaves roughly 37dp per row. `kbd_full` fits comfortably across the width. Keep
the split off unless reaching the middle one-handed bothers you.

**Large portrait (inner screen).** `kbd_full` reaches 9.3mm, so the full layout is
finally comfortable. Height around 38-42%. This is the first profile
where a small split (10-12%) earns its keep: holding both halves open, the centre
of the keyboard is past where either thumb reaches.

**Large landscape (inner screen).** The widest case, and the one the split exists
for: 15-20% is the useful range. Height around 45-50% -- the inner screen is
short in landscape, so the percentage has to be higher to get the same key height.

One thing worth knowing about a book-style foldable: the inner screen is nearly
square (about 673x775dp portrait, 762x673dp landscape), so its two orientations
are far more alike than a normal phone's. The real divide is cover versus inner,
not portrait versus landscape. The two large profiles will usually differ only in
height percentage and split width.

### Choosing the layout

The per-profile "Keyboard layout" setting maps to `KeyboardSwitcher`'s `mFullMode`:

| value | layout |
|---|---|
| `-1` | inherit the global per-orientation setting |
| `0` | full 5-row, with the phone-style symbols page |
| `1` | compact 5-row |
| `2` | full 5-row, with the Fn layer (`kbd_full_fn`) |
| `3` | thumb, 10 columns (`kbd_thumb`) |

Modes 0 and 2 use the same letter layout and differ only in what the symbol key
reaches. Mode 3 is only selectable per profile; the in-session mode cycling in
`LatinIME.getKeyboardModeNum()` still toggles between compact and full.

### Inherited values in the settings screen

A slider cannot express "unset", so an untouched height slider would otherwise
show a default that is not what the keyboard is doing. `PrefScreenProfiles` moves
those sliders to the value actually being inherited and labels them. The list
preferences solve the same problem with an explicit "Use global setting" entry.
 To add another, give it a base key, read it through `ProfilePrefs` in
`LatinIME.loadProfileSettings()`, and add a row to each category in
`res/xml/prefs_profiles.xml`.

### Reacting to changes

Two paths reload these settings:

- `LatinIME.onSharedPreferenceChanged` short-circuits on any key containing `@`.
- `updateOrientation()` compares the profile as well as the orientation, because
  **folding changes the profile without changing the orientation** -- otherwise
  unfolding would keep the cover screen's layout.

## Split keyboard

`Keyboard.applySplit()` runs after the layout is parsed. Every key is scaled down
by the width of the gap and the right-hand half pushed right by it, so the
keyboard still spans the screen and no key changes shape.

Where a row opens is decided **per row**, because the rows are not aligned: the
number row and the home row are offset by their leading key, so no single x
splits all of them between keys. Each row opens at the key boundary nearest the
middle, which leaves the gap slightly ragged -- the same as any staggered split
layout. A key that genuinely spans the middle and is much wider than its
neighbours (the space bar) stretches across the gap instead, so it stays
reachable by either thumb.

Two things the geometry has to get right:

- Key positions accumulate from percentages, so a boundary meant to land exactly
  on the middle arrives a hair off it. Without a tolerance the whole row reads as
  one straddling key and no gap opens at all.
- The two edges of a key are moved by their own side's rule, so the comparison
  has to be strict on one side, or a key ending on the split point is dragged
  across it.

The gap width is a per-profile preference, 0 (not split) everywhere by default.

## Vietnamese input

The bundled Unikey engine (`cpp/ukengine`, `libunikey.so`) is unprivileged NDK
code, so it ships in every flavor. `VnComposer` drives it against an
`InputConnection` the way ibus-unikey does: the current word is composing text
and is committed when the word breaks.

`LatinIME` routes printable keys through it while enabled, instead of sending key
events -- Vietnamese needs a Unicode commit. The word is committed whenever it
ends: a non-word key, a modifier chord (a chord is a shortcut, not text),
backspace past the start, a new field, or the caret moving out of the composing
region.

**Fn + ;** toggles it, via the keyboard's existing per-key fn-codes mechanism, so
it needs no special handling in the view layer. It is bound in `kbd_full.xml` (on
Fn1) and in `kbd_thumb.xml` (on Fn2, because that is the Fn key that layout has).
The compact layout has no key at that position. Settings
has the same toggle plus a Telex/VNI/VIQR picker; both default to off.

The Thor deck has its own independent toggle, since it composes against a
different target.

### Knowing it is on, and when it stays out of the way

The key carrying the toggle lights up like an active modifier while Vietnamese is
composing, using the same `key.on` plumbing as Shift and Fn. Without that the only
feedback is a toast you have already dismissed by the time you start typing.

Composing also suspends itself for fields where it would do harm rather than good:
passwords, URLs, email addresses, numbers, phone numbers, dates, and anything
asking for no suggestions. Typing `aa` into a password and getting `â` is the kind
of thing you only notice after the login fails. The setting stays on; it simply
does not apply, and the key is unlit so you can see that.


## Clipboard history

Android only lets an app read the clipboard while it has focus, and an IME counts
as focused while it is up -- but it is never *notified* of a copy made in another
app. So `ClipboardHistory` does not listen; it samples the clipboard at the moments
the keyboard is allowed to read it: when it opens on a field, and when the history
is opened.

**Fn + v** opens it. Picking an entry commits it (ending any Vietnamese word first,
so the paste is not swallowed into the composing region). The last item clears the
history.

The history is persisted, because an IME process is killed and restarted constantly
and an in-memory-only history would almost always be empty exactly when wanted. It
is capped at 30 entries of at most 4000 characters, and clips the source app marked
sensitive -- password managers set this -- are refused.

The picker borrows the keyboard's window token and sets `FLAG_ALT_FOCUSABLE_IM`,
the same way the existing options menu does: an IME has no activity of its own, so
a dialog has to attach to the input view's window and stay out of the IME's own
focus handling.

## The extension row

The compact layout has an extra top row of punctuation and Home/End/Del marked
`android:extension="true"`. It is normally a swipe-up toggle that resets on every
field; a profile can keep it up permanently instead.

Worth knowing: this row only exists in `kbd_compact.xml`. `kbd_full` already has an
F-key row and a number row, and `kbd_thumb` already carries Esc and punctuation on
its top row, so the setting has no effect on either.

## Hiding the system IME navigation bar

Below the keyboard the framework draws a strip holding a keyboard-switcher button and
a back button. On a Fold's inner screen that is 114px -- roughly two thirds of a key
row -- spent on two buttons that a swipe down and the IME-switcher notification
already cover.

There is no API to turn it off, but on a device where `imeDrawsNavBar` is true (check
with `adb shell dumpsys input_method | grep imeDrawsNavBar`) the framework builds it
inside *our* window. Dumping the IME window's decor tree shows:

    DecorView (y=996, h=852)
      ... inputArea
          KeyboardContainer   h=852  paddingBottom=114
            LatinKeyboardView h=738
      View  id=navigationBarBackground            y=1734 h=114
      NavigationBarFrame                          y=1734 h=114
        NavigationBarView -> ime_switcher, back

Both of those are children of our own decor view, so `LatinIME` can hide them -- but
not once. The framework shows them again whenever it re-evaluates the strip, most
visibly when the IME-switcher button comes and goes, and with the inset no longer
reserved the strip then sits *on top of* the bottom key row. The hiding is therefore
reasserted from an `OnGlobalLayoutListener` on the decor, which every path the
framework takes passes through. Setting GONE triggers another layout pass, but by then
it is already GONE, so it settles rather than looping. Hiding alone is not enough: the matching window inset is still
applied as `KeyboardContainer`'s bottom padding, which would leave exactly the gap the
bar was filling. `KeyboardContainer.onApplyWindowInsets` therefore drops the bottom
inset too, so the window genuinely shrinks and the app above gets the space back.

Off by default. Verified on a Galaxy Z Fold 7 (Android 17): the strip disappears, the
keyboard sits flush to the bottom edge, and the app above gains the 114px.

The keyboard stops above the `mandatorySystemGestures` inset rather than going flush
to the edge. Sitting in that strip, the bottom row still takes taps -- checked on the
device -- but a *swipe* starting there is the system's home gesture, so the
sliding-key feature would be lost on that row. The remaining margin is still far
smaller than the navigation bar it replaced.

The two buttons that bar provided are on the green Fn layer instead, in the same
place on both layouts: **Fn + x** hides the keyboard, **Fn + m** opens the
input-method picker.

## Display cutouts and the keyboard's width

`Keyboard` takes its width from `display.mode`, which is the panel. The window an IME
is actually given is narrower than that wherever a display cutout sits beside it: an
IME window defaults to `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT`, so the system keeps it
clear of the cutout's strip.

On a Fold's cover screen in landscape the camera is on what is then the right edge and
takes 104px of 1972: the window came back `[0,533][1868,1248]` while the keyboard laid
itself out to 1972, and the last key of every row was sliced in half. The keyboard now
subtracts the cutout's horizontal safe insets. Only horizontal: it is bottom-anchored
and full width, so a cutout above or below does not shrink the window.

Worth knowing when something looks clipped: `adb shell dumpsys window windows` gives
the IME window's `frame=`, and comparing it with `wm size` says immediately whether the
window or the layout is the odd one out.
