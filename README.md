# Vynox

**Offline-first, multi-layer video and motion-graphics editor for Android.**

Vynox is a complete Android app with its own editing engine: a real timeline, real
keyframe animation with a Bezier graph editor, text and shape layers, masks, GPU
effects, blending modes, audio, an OpenGL ES preview, a versioned `.vnx` project
format and genuine MP4 export (H.264 + AAC rendered from the composition — not a
screen recording).

Everything works with the network switched off. The only optional network call in
the whole app is the update check on the home screen, which can be turned off in
Settings.

- **Download the latest APK:** <https://pushparaj9749.github.io/Vynox/>
- **Source:** <https://github.com/pushparaj9749/Vynox>

---

## Contents

1. [Features](#features)
2. [Offline architecture](#offline-architecture)
3. [The `.vnx` project format](#the-vnx-project-format)
4. [Architecture](#architecture)
5. [Development setup](#development-setup)
6. [Building](#building)
7. [Testing](#testing)
8. [Release process](#release-process)
9. [Download page (GitHub Pages)](#download-page-github-pages)
10. [Limitations](#limitations)
11. [Roadmap](#roadmap)
12. [Security](#security)

---

## Features

### Editing

| Area | What is implemented |
| --- | --- |
| Import | Local video, image and audio through the Android storage picker; duration, dimensions, sample rate and MIME probed on import |
| Timeline | Multi-layer, canvas-drawn: drag to move with snapping, in/out trim handles, split at playhead, markers, pinch zoom, scrub |
| Layers | Video, image, audio, text, shape and group (null) layers; reorder, visibility, lock, mute, solo, rename, duplicate, delete |
| Keyframes | Add, delete, move and copy keyframes on position, scale, rotation, opacity and every effect parameter |
| Easing | Linear, hold and Bezier interpolation with named presets, editable in a graph editor that changes real playback |
| Text | Editable text layers: content, size, weight, italic, alignment, letter and line spacing, colour, stroke, shadow, background |
| Shapes | Rectangle, rounded rectangle, ellipse, line and polygon with fill, stroke, corner radius and side count |
| Masks | Rectangle, ellipse and path masks with position, size, rotation, feather, opacity, invert and add/subtract/intersect modes |
| Effects | Blur, brightness, contrast, saturation, hue, exposure, sharpen, glow, opacity, vignette, tint, invert, temperature — GPU accelerated, serialised in `.vnx` |
| Blending | Normal, multiply, screen, overlay, add/lighten, darken and more, per layer |
| Audio | Per-layer volume and mute, resampled sequential decoding for preview, A/V sync with re-seek on scrub, AAC in the export |
| Preview | OpenGL ES compositor driving a `SurfaceView`; play, pause, seek, scrub, zoom, fit-to-canvas and on-canvas transform handles (move, scale, rotate) |
| Export | MP4 (H.264 + AAC) with resolution, frame rate and bitrate control, live progress, cancellation and a size estimate |

### Project and reliability

- Save and open `.vnx` files, optionally **bundling the media** so one file is the whole edit.
- Project library with thumbnails, plus import of a `.vnx` from anywhere (share intent, downloads,Drive).
- **Missing media is never silent:** the editor shows a relink banner and an import screen lets you re-point each asset.
- Undo/redo with labelled, coalesced history.
- Local autosave plus a recovery prompt on the next launch.

---

## Offline architecture

Editing, preview and export are entirely local:

- Media is copied into the app's private storage on import and referenced by stable asset ids.
- The renderer, effect chain, audio mixer and encoder are all on-device components
  (OpenGL ES, `MediaCodec`, `MediaMuxer`, `AudioTrack`).
- Nothing in the editing path opens a socket.

The single optional network call is `UpdateRepository`, which performs one
`GET https://api.github.com/repos/pushparaj9749/Vynox/releases/latest` from the home
screen to show "a newer version exists". It is disabled by turning off
**Settings → Check GitHub for updates**, and failure is silent.

---

## The `.vnx` project format

`.vnx` is a versioned, text-first container:

- **Plain `.vnx`** — a JSON document (schema versioned by `VynoxSchema.CURRENT`) describing the
  canvas, assets, layers, transforms, keyframes, effects, masks, markers and settings.
- **Bundled `.vnx`** — the same document packaged as a zip together with the media files and a
  preview thumbnail, so a single file can be shared and opened elsewhere.
- Readers detect the container automatically (`VnxReader.isZip`) and older files are upgraded
  through migration steps, with warnings surfaced to the user instead of silent data loss.

Serialization lives in `core:vnx` and knows nothing about the UI: `ValueCodecs` encode
animated values, `VnxProjectCodec` maps the document model, and `AssetResolution` handles
missing/relinked media. The format is decoupled from any screen, so it can be tested,
versioned and extended independently.

---

## Architecture

```
core/                      Pure Kotlin modules — no Android, fully unit tested
  json/                    Minimal JSON parser/writer used by .vnx
  math/                    Vec2, Rect, Matrix3, Color, easing helpers
  animation/               Animatable values, keyframe tracks, Bezier interpolation
  model/                   Project, Layer, Transform, Content, Effects, Masks, Canvas
  effects/                 Extensible effect registry + built-in catalogue
  composition/             Evaluates a project at time T into a render plan
  timeline/                Timeline operations, snapping, undo/redo history
  vnx/                     .vnx read/write, asset resolution, migrations

app/                       Android application (Kotlin + Jetpack Compose)
  data/                    MediaProbe, AssetStore, ProjectStore, SettingsStore, UpdateRepository
  render/
    gl/                    EGL/GL renderer, shaders, framebuffers, effect planner
    source/                Video decoder, text/shape rasterizers, texture caches
    audio/                 Sequential PCM decoding, resampling, mixer, AudioTrack playback
    export/                MediaCodec surface encoder + AAC + MediaMuxer
  ui/
    theme/                 Original dark palette, shapes, typography
    navigation/            Routes + in-memory session
    splash, home, projects, newproject, importv
    editor/                Editor screen, preview engine, timeline, layer list, inspector, graph editor
    export, settings, about
```

The layering rule: **the engine never depends on the UI, and the UI never reimplements
engine logic.** Screens observe project state; every edit is a pure transformation of
`VynoxProject` pushed through `History`, which is what makes undo/redo reliable.

---

## Development setup

Requirements:

- JDK 17 (the CI uses Temurin 17)
- Android SDK with build-tools and platform 35 (`minSdk 26`, `targetSdk 35`)
- No accounts, no API keys, no local secrets required for a debug build

```bash
git clone https://github.com/pushparaj9749/Vynox.git
cd Vynox
./gradlew :core:math:test          # engine tests, no Android SDK needed
./gradlew assembleDebug            # debug APK
```

If you do not have the Android SDK locally, `ANDROID_HOME` / `ANDROID_SDK_ROOT` must point
at a valid SDK (or create `local.properties` with `sdk.dir=...`, which is git-ignored).

---

## Building

| Task | Command | Output |
| --- | --- | --- |
| Engine tests | `./gradlew test` | Unit test reports for all `core:*` modules |
| Debug APK | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/` |
| Release APK (unsigned) | `./gradlew assembleRelease` | `app/build/outputs/apk/release/` |
| Signed release APK | provide signing env vars, then `./gradlew assembleRelease` | signed, v1+v2+v3 |

### Signing

`app/build.gradle.kts` reads signing configuration **only from the environment** and builds
unsigned when it is absent, so a plain checkout always builds:

```
VYNOX_KEYSTORE_FILE        path to the .jks/.keystore
VYNOX_KEYSTORE_PASSWORD    keystore password
VYNOX_KEY_ALIAS            key alias
VYNOX_KEY_PASSWORD         key password
```

In GitHub Actions these come from repository secrets
(`VYNOX_KEYSTORE_BASE64`, `VYNOX_KEYSTORE_PASSWORD`, `VYNOX_KEY_ALIAS`, `VYNOX_KEY_PASSWORD`).
The release workflow decodes the keystore to a temporary file outside the workspace and
deletes it in an `always()` step. No keystore, password or Base64 blob is ever committed,
printed, uploaded as an artifact or exposed on the Pages site.

---

## Testing

CI (`.github/workflows/ci.yml`) runs on every push and pull request:

1. `./gradlew test` — engine unit tests (math, JSON, animation, timeline, effects, composition, `.vnx` round-trips, asset resolution)
2. `./gradlew assembleDebug`
3. `./gradlew assembleRelease` (unsigned; verifies the release variant and R8 config)
4. On failure, a filtered report is posted as a comment on issue #1 so the reason is visible without reading a full Gradle log.

What the tests cover:

- Math: transforms, rotation about an anchor, colour conversion, rectangle algebra
- Animation: keyframe insert/move/delete, interpolation, Bezier easing, shortest-arc angles
- Timeline: split, trim, move, snapping, undo/redo history, marker handling
- Effects: registry behaviour, parameter defaults, resolution of animated parameters
- Composition: evaluation at time T, layer bounds, hit testing, audio activity
- `.vnx`: full project round-trip, bundled packages, migrations, warning handling, missing-asset resolution

Manual verification checklist before a release: build debug → install → create a project →
import media → add text/shape → animate with keyframes and the graph editor → add effects and
masks → preview with audio → save → reopen from Projects → export MP4 → play the exported file.

---

## Release process

Releases are deliberate: pushing a `v*.*.*` tag or running the workflow manually. Ordinary
commits never publish a release.

1. Bump `versionCode` / `versionName` in `gradle/libs.versions.toml` (or `app/build.gradle.kts`).
2. Merge to `main` and confirm CI is green.
3. Tag and push:

   ```bash
   git tag -a v0.1.0 -m "Vynox v0.1.0"
   git push origin v0.1.0
   ```

4. The **Release** workflow (`.github/workflows/release.yml`) then:
   - checks out the tag and runs the tests
   - restores the keystore from secrets into a temp file (never into the repo)
   - builds the signed release APK (unsigned if the secrets are absent)
   - verifies the APK and prints its signature summary
   - creates the GitHub Release with generated notes (commit log since the previous tag)
   - appends the version to `CHANGELOG.md` and writes `docs/VERSION`
   - requests a Pages deployment
   - removes all temporary signing files in an `always()` step

An existing tag or release is never overwritten: if the release already exists the workflow
reports it and stops rather than clobbering published artifacts.

---

## Download page (GitHub Pages)

The site lives in `docs/` and is published by `.github/workflows/pages.yml` to
<https://pushparaj9749.github.io/Vynox/>.

It shows the name, description, features, install steps, repository link, current version and
a **Download APK** button. The button is resolved at page load: `docs/assets/script.js` calls
the GitHub API for the latest release and points the button at that release's APK asset. No
version-specific URL is hard-coded, so the page keeps working for every future release. If the
API is unreachable the button falls back to the releases page.

---

## Limitations

Honest notes about the current implementation:

- **Path masks** are supported in the data model and renderer, but the on-screen editor
  exposes them as positional/resizable rectangle and ellipse masks; free-hand vertex editing
  is not in the UI yet.
- **Waveform display** is not drawn; audio is edited by trim, level and mute.
- Video decode for preview is sequential with seek-to-previous-sync, which is accurate enough
  for editing but can show a short settle on some codecs.
- Export resolution is limited by the device encoder (typically 4K maximum) and encodes in
  real time or slower depending on effect complexity.
- Projects are stored on the device; there is no cloud sync (by design — Vynox is offline-first).

---

## Roadmap

- Vertex editing for path masks
- Waveform display and audio keyframe envelopes in the timeline
- Adjustment layers and per-group effects
- Motion blur and more effect modules (registered through the same extensible registry)
- Expression/property links between layers
- Optional project templates and asset packs

---

## Security

- Signing secrets exist only as GitHub Actions secrets. They are never printed, echoed,
  committed, uploaded as artifacts or rendered onto the Pages site.
- No keystore, `.jks`, Base64 key material, password or private key is present in the
  repository; `.gitignore` excludes keystores, keys and `local.properties` as defence in depth.
- The release workflow decodes the keystore to a temporary directory outside the workspace and
  deletes it in an `always()` cleanup step.
- CI failure reports are filtered (`grep`/`awk`) so logs posted to issues cannot leak secrets.

---

## Licence

Source is published in this repository; see the repository settings for licence details.
