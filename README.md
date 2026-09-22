# Multi Model Viewer

A single-Activity Android app that loads bundled `.glb` models into
independently draggable, resizable, rotatable containers, with live
part-label overlays read from each model's `extras.prop` node data.

> **Before you submit:** this repo is a working architecture and starter
> implementation, not yet built, run, or profiled on a device. Open it in
> Android Studio, drop your 5 `.glb` files into
> `app/src/main/assets/models/`, build, and test on real hardware before
> filling in the "Tested on" section below and recording your walkthrough.
> Treat every `TODO` / bracketed placeholder in this file as something to
> replace with your own measured results.

## 3D library: Google Filament, via raw `gltfio` (not SceneView)

Sceneform is dead — Google archived it in 2021, and the community
"Sceneform Maintained" fork is itself archived as of March 2026. The
actively developed layer on top of Filament today is **SceneView**, but
this app uses Filament's `gltfio`/`filament-android` APIs directly instead
of the SceneView wrapper, for one specific reason: SceneView's `Scene`
composable is designed around one Filament `View`/render surface per
composable. This app needs the opposite — **one shared `Engine` /
`Renderer` / `SwapChain`, with N lightweight `View`s each rendering into
its own screen rectangle of the same surface** — because spinning up 5
separate GL contexts (5 EGL contexts, 5 render threads) is the single
biggest risk to hitting 30fps on a 2–3GB device with 5 models loaded.
Filament supports this multi-viewport-on-one-surface pattern natively;
that capability is why Filament (rather than, say, a WebView + three.js
`<model-viewer>`, which pays JS-bridge and WebView compositing overhead)
was chosen at all.

## Architecture

- **`SceneManager`** owns the one shared `Engine`/`Renderer`/`SwapChain`.
  Each loaded model gets its own `Camera` + `Scene` + `View` (cheap), and
  a per-frame loop (driven by one `Choreographer.FrameCallback` in
  `MainActivity`) sets each `View`'s `viewport` from its container's
  current on-screen rect and renders all of them before `endFrame()`.
- **Containers are just rectangles**, not Android Views. Dragging or
  resizing a model mutates a `RectF` field — there is no view
  measure/layout pass involved, which is what keeps the drag "follow the
  finger" requirement (1.4) lag-free.
- **`OverlayView`** is a single transparent full-screen `View` that draws
  every container's border, its 3 buttons, and its labels, and owns all
  touch handling. One view for everything, regardless of model count, so
  the view tree never grows with the number of loaded models.
- **`GlbLabelParser`** reads the `.glb`'s embedded JSON chunk directly to
  build a `node name -> extras.prop text` map, because Filament's
  `gltfio` importer preserves node names but not arbitrary `extras`
  fields. After `AssetLoader` parses the model, entities are matched back
  to labels by name.
- Every frame, labeled node world positions are projected through each
  container's current view/projection matrices (`SceneManager
  .updateProjectedLabels`), so labels track the part correctly while it's
  being rotated/zoomed in interaction mode (2.2).
- Gesture routing (`OverlayView`) locks the touch target and its mode
  (`NORMAL` vs `INTERACTION`) on `ACTION_DOWN` and never re-evaluates it
  mid-gesture, so the two modes can't mix within one drag/pinch (1.7).

## Performance optimisations applied

- Single shared Engine/Renderer/SwapChain instead of one per model —
  avoids N EGL contexts (the dominant cost on weak GPUs with 5 models).
- Per-view `DynamicResolutionOptions` enabled (`QualityLevel.LOW`, scale
  down to 0.5x on demand) so the renderer can shed resolution under load
  rather than dropping frames.
- MSAA and `AntiAliasing` disabled per view; bloom and ambient occlusion
  disabled per view — all meaningful per-pixel costs multiplied by 5
  simultaneous views.
- Shadows disabled (`castShadows(false)`) — shadow maps are one of the
  more expensive features to pay 5x for.
- Containers are plain `RectF` state, not Android Views — no
  measure/layout/invalidate cascades from dragging or resizing.
- Labels and buttons for *all* containers are drawn by one overlay
  `View.onDraw`, not per-label/per-button Android views — draw calls stay
  flat as model count grows.
- Frame-loop scratch buffers (matrices, vectors in `SceneManager`) are
  preallocated fields, not allocated per frame, to avoid GC-pause frame
  drops — a large risk factor for "steady fps" on a weak CPU.
- `Engine.destroyAsset` / `destroyView` / `destroyScene` / entity
  destruction is wired into `removeModel` so Close (1.6) actually frees
  GPU + native memory rather than leaking it across add/remove cycles.

## Trade-offs made

- **Interaction-mode zoom scales the model, not the camera.** Simpler to
  reason about alongside label re-projection, but it means very large
  zoom-out factors can push the model outside its container's near/far
  planes in extreme cases; a camera-dolly approach would avoid that at
  the cost of more bookkeeping.
- **Buttons and labels are hand-drawn glyphs/shapes on a `Canvas`**, not
  real vector icon assets — kept the view tree flat and avoided pulling
  in extra drawables, but it looks placeholder-y; swap in real icons
  before a real submission.
- **No instancing** for a model added more than once — `gltfio` supports
  sharing GPU buffers across instances of the same asset
  (`AssetLoader.createInstance`), which would matter more if 5 concurrent
  models were commonly the *same* model; not implemented here for time.
- **Fixed portrait orientation** — sidesteps re-deriving all 5 camera
  aspect ratios and container rects on rotation; a real app would want
  landscape support.

## What I'd improve with more time

- Actual Perfetto-measured frame timings with 5 models loaded + active
  drag, to validate the 30fps target rather than relying on the above
  choices being sufficient in theory.
- Camera-dolly zoom instead of model-scale zoom in interaction mode.
- Real vector icons and a nicer button/label visual style.
- Asset instancing for repeated models, and LOD/texture-size reduction
  for the low-end target specifically.
- Landscape support.
- A resize handle in the corner in addition to pinch, for precision.

## Known bugs / limitations

- Not yet built or run — treat this as a starting point to compile,
  debug, and profile, not a finished submission.
- `GlbLabelParser` assumes the `.glb`'s first chunk is JSON (per spec)
  and does not handle GLBs with the JSON chunk padded oddly outside the
  4-byte alignment glTF requires — fine for spec-compliant files, but add
  a fallback if your 5 files turn out not to be strictly compliant.
- No handling yet for a `.glb` that fails to parse (corrupt file, missing
  node extras) beyond letting the exception propagate — add a try/catch
  + user-facing error in `SceneManager.loadModel` before shipping.

## Tested on

- [ ] TODO — fill in the actual device(s), Android version, and RAM you
  profiled on, plus the fps you observed with 5 models loaded and
  actively being dragged/rotated/zoomed.

## Setup

1. Open this folder in Android Studio (Koala/Ladybug or newer, AGP 8.5+,
   JDK 17).
2. Copy your 5 `.glb` files into `app/src/main/assets/models/` (delete
   the placeholder `.txt` there).
3. Run on a device or emulator. Use the **Add model** button to load
   models; each gets its own draggable/resizable container with
   interaction-toggle, label-toggle, and close buttons in its top-right
   corner.
