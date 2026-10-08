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

"Large" is `Configuration.smallestScreenWidthDp >= 600` -- Android's own
tablet-sized-pane line, and the one a foldable crosses when it opens.
`smallestScreenWidthDp` is the right signal because it ignores rotation: it
describes the panel in use, so folding changes it and rotating does not.

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
what decides whether a key is thumbable. Typical letter-key widths, for a Galaxy
Z Fold (cover screen 411dp wide; inner screen roughly 673dp portrait / 762dp
landscape), against Material's 48dp minimum touch target:

| layout | columns | cover portrait | cover landscape | inner portrait | inner landscape |
|---|---|---|---|---|---|
| `kbd_full`    | 14 | **29dp** | 65dp | 48dp | 54dp |
| `kbd_compact` | 11 | **37dp** | 83dp | 61dp | 69dp |
| `kbd_thumb`   | 10 | 41dp | 91dp | 67dp | 76dp |

That table is most of the answer:

**Small portrait (cover screen).** `kbd_full` gives 29dp keys here, which is not a
thumb target, it is a dare. `kbd_thumb` is the only layout that clears a usable
width, and it was built for exactly this shape -- a short panel typed on with
thumbs -- with symbols on the surface so there is no page to flip to, and F-keys
and arrows on the inline Fn2 layer. Height around 40-45%: the cover screen is very
tall, so even 40% is a lot of pixels.

**Small landscape (cover screen).** Width stops being the constraint and height
becomes one: the panel is only about 411dp tall, so a six-row keyboard at 55%
leaves roughly 37dp per row. `kbd_full` fits comfortably across the width. Keep
the split off unless reaching the middle one-handed bothers you.

**Large portrait (inner screen).** `kbd_full` lands exactly on 48dp, so the full
layout is finally comfortable. Height around 38-42%. This is the first profile
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
