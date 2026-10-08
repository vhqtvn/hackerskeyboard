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

Profile-scoped so far: keyboard height, keyboard layout mode, hint mode, and the
split gap. To add another, give it a base key, read it through `ProfilePrefs` in
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

**Fn + ;** toggles it, via the keyboard's existing per-key `fn1Codes` mechanism,
so it needs no special handling in the view layer. The binding lives in
`kbd_full.xml` only -- the compact layout has no key at that position. Settings
has the same toggle plus a Telex/VNI/VIQR picker; both default to off.

The Thor deck has its own independent toggle, since it composes against a
different target.
