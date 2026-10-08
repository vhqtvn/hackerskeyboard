# VHKeyboard launcher icon (black & gold)

Source SVGs (rendered with `rsvg-convert`):
- `ic_launcher.svg`    — full composed icon (rounded-square clip) → legacy mipmap PNGs.
- `ic_launcher_bg.svg` — adaptive background layer (dark gold-glow backdrop).
- `ic_launcher_fg.svg` — adaptive foreground (frame + VH + keyboard, scaled to the safe zone).

Regenerate:
```
# adaptive layers (nodpi, 432px)
rsvg-convert -w 432 -h 432 ic_launcher_bg.svg -o ../../app/src/main/res/drawable-nodpi/ic_launcher_bg.png
rsvg-convert -w 432 -h 432 ic_launcher_fg.svg -o ../../app/src/main/res/drawable-nodpi/ic_launcher_fg.png
# legacy square + round at mdpi..xxxhdpi (48/72/96/144/192)
```
Adaptive XML: app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
