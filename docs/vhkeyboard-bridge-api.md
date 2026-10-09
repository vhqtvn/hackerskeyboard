# VHKeyboard ↔ Web Panel Bridge API — v0.1 (draft for discussion)

## Purpose / mental model

Three tiers, each doing what it's best at:

- **Server** — the brain: AI calls, session/history state, tools, long-running/background work.
- **Web panel** (e.g. VHSolara, but *any* web app) — a thin, multi-device view of the server. Runs unchanged in a normal browser on phone/desktop.
- **VHKeyboard** (Android app on the AYN Thor) — the *native integration layer*: the on-screen keyboard/deck, injecting text into whatever app is focused on the main screen (e.g. VHEditor over SSH), and reading the current selection. It hosts the web panel in a WebView and exposes these native powers to it.

The panel does **not** get its intelligence from the device. It keeps talking to the server exactly as it does in a browser. The bridge only adds **device-native abilities** (type into the editor, deck buttons, read selection) *when* the panel happens to be running inside VHKeyboard.

The reusable contract is this bridge. VHSolara is "one panel, not all" — anything that speaks this API can be hosted.

---

## Detection & graceful degradation

The native host injects a global `window.VHKeyboard` **only** when the panel runs inside VHKeyboard (and only for allow-listed origins — see Security). Everywhere else it's absent.

```js
const kb = window.VHKeyboard;           // undefined in a normal browser
if (kb) {
  // enable deck verbs, "send to editor", etc.
} else {
  // behave as a plain web app
}
```

- `kb.version` → bridge semver, e.g. `"0.1.0"`.
- `kb.capabilities` → string[] of supported features, e.g. `["inject","keys","selection","verbs","clipboard"]`. Check before using anything beyond the baseline so older/newer hosts degrade cleanly.

---

## Async model

- **Web → native** calls are async and return **Promises**. They reject with `{ code, message }` (see Error codes).
- **Native → web** is delivered as **events** the panel subscribes to:

```js
const unsub = kb.on("verb", (payload) => { /* ... */ });
// unsub() to remove
```

---

## Web → Native  (the panel uses the Thor's native powers)

### Editor / injection (targets the focused app on the MAIN screen)
| Method | Returns | Notes |
|---|---|---|
| `kb.injectText(text)` | `Promise<void>` | Types `text` into the currently focused external app (VHEditor, terminal, …). The core capability. |
| `kb.sendKey(key, modifiers?)` | `Promise<void>` | Sends a keystroke, e.g. `sendKey("Enter")`, `sendKey("s", ["ctrl"])`. `modifiers`: `"ctrl"\|"alt"\|"shift"\|"meta"`. For driving the editor/terminal. |
| `kb.getEditorSelection()` | `Promise<{text:string}\|null>` | Best-effort current selection in the focused app. May be null / limited depending on the target app (mechanism is native's concern). |

### Deck / keyboard integration
| Method | Returns | Notes |
|---|---|---|
| `kb.setVerbs(verbs)` | `Promise<void>` | Declare the action buttons the **deck** (2nd screen) shows. Tapping one fires a `verb` event back to the panel. Replaces the current set. |
| `kb.setVerbEnabled(id, enabled)` | `Promise<void>` | Enable/disable one verb (e.g. disable "→Editor" when nothing is selected). |
| `kb.clearVerbs()` | `Promise<void>` | Remove all panel verbs. |

`Verb` shape:
```ts
type Verb = { id: string; label: string; icon?: string; group?: string; enabled?: boolean };
```

### System / panel
| Method | Returns | Notes |
|---|---|---|
| `kb.getClipboard()` | `Promise<string>` | |
| `kb.setClipboard(text)` | `Promise<void>` | |
| `kb.hidePanel()` | `Promise<void>` | Minimize the panel (e.g. after "→Editor") so the editor is unobstructed. |
| `kb.getDeviceInfo()` | `Promise<DeviceInfo>` | `{ platform, bridgeVersion, capabilities, screen:{w,h,density} }`. |
| `kb.log(level, msg)` | `Promise<void>` | Pipe to device logs for debugging. |

---

## Native → Web  (the host drives the panel — events)

| Event | Payload | When |
|---|---|---|
| `"verb"` | `{ id: string }` | A deck verb button was pressed. The panel decides what it means (act on its own selected message, or pull `getEditorSelection()` — panel's choice). |
| `"insertContext"` | `{ text: string, source?: string }` | Native pushes text into the panel — e.g. user selected code in VHEditor and hit a "send to chat" key. Panel inserts into its input/context. |
| `"visibility"` | `{ visible: boolean }` | Panel shown/hidden. **Use this to pause streaming/animations when hidden** (battery). |
| `"theme"` | `{ theme: "light"\|"dark" }` | Follow device theme. |
| `"resize"` | `{ w, h, density }` | Panel viewport changed. |

---

## Example flows

**1. Panel boot**
```js
const kb = window.VHKeyboard;
if (kb) {
  await kb.setVerbs([
    { id: "translate", label: "Vi⇄En" },
    { id: "refine",    label: "Refine" },
    { id: "research",  label: "Research" },
    { id: "toEditor",  label: "→Editor", enabled: false },
  ]);
  kb.on("visibility", ({visible}) => visible ? resumeStreams() : pauseStreams());
}
```

**2. Verb on the selected message** (all AI work stays server/web; native just triggers)
```js
kb.on("verb", async ({id}) => {
  if (id === "translate") translateSelectedMessage();   // your existing server call
  if (id === "toEditor")  await kb.injectText(selectedMessage.codeBlock);
});
```

**3. Pull code from the editor into the prompt**
```js
kb.on("insertContext", ({text, source}) => promptBox.append(text));
// (native fires this after the user selects code in VHEditor + presses a deck key)
```

**4. Selection-aware verb enablement**
```js
onSelectionChange(sel => kb?.setVerbEnabled("toEditor", !!sel));
```

---

## Error codes (reject payload `{code, message}`)
`UNSUPPORTED` · `NO_SELECTION` · `INJECT_FAILED` · `NO_FOCUS_TARGET` · `PERMISSION_DENIED` · `INTERNAL`.

---

## Security
- The bridge is injected **only for allow-listed origins** (VHSolara's URL and/or bundled local assets). `injectText`/`sendKey` can type into other apps, so only trusted panels get the bridge.
- Panels must treat the bridge as optional and untrusted-absent (feature-detect).

## Transport (native side, informational — not the panel's concern)
- Web→native: AndroidX WebKit `WebViewCompat.addWebMessageListener` (structured `postMessage`).
- Native→web: `evaluateJavascript` / injected `postMessage`.
- Each call carries a request id; native replies resolve/reject the matching Promise. All payloads are JSON.

---

## Open questions for the VHSolara designer
1. **Auth/session:** does VHKeyboard need to hand the panel any identity/token (so it binds to *your* server session), or does the panel authenticate to the server itself exactly as in a browser? (Prefer the latter — keeps the bridge dumb.)
2. **Selection source for verbs:** should a verb act on the panel's *own* selected message, the *editor* selection (`getEditorSelection`), or should the panel decide per-verb? (Draft assumes: panel decides.)
3. **Verb catalog:** is a flat list enough, or do you want groups / sub-menus / a "more" overflow on the deck?
4. **Offline / server-unreachable:** what should the panel show, and should the bridge surface connectivity?
5. **Multi-panel:** will VHKeyboard host more than one web app (switcher), and if so does each get its own verb set? (Bridge already supports per-panel `setVerbs`.)
6. **Streaming pause contract:** is `visibility=false` enough to pause server streams, or do you want an explicit `kb.on("suspend"/"resume")`?
