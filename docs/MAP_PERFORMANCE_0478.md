# TerraUnlocked 0.4.78 — Fluid pan / zoom rendering

## Root cause

Previous versions calculated the fog bitmap and its CPU smoothing inside
`DiscoveryFogOverlay.draw()` whenever map position/zoom exceeded a small
cache threshold. At the same time the GPS route was reprojected, tested and
painted as thousands of Canvas line segments on *every* animation frame.
With 3,000+ saved discoveries, panning and pinch zoom could freeze the UI.

## Change

- Heavy fog rendering moved to a single dedicated background executor.
- Build an immutable projection snapshot on the UI thread only after map
  movement has settled (~300ms debounce); all organic-disc drawing, route
  raster generation, bridge calculations, bitmap allocations, and blur
  pixel loops happen off-thread.
- During dragging/pinch zoom, reuse the cached fog and pre-rendered route
  with fast affine translation and scaling. An old raster is not recycled
  while Android could still draw it.
- Outdated background results are rejected if the viewport changed during
  the build; the latest stable map position gets its own raster. The UI
  thread receives the result asynchronously and refreshes when ready.
- A submitted raster job is tracked to prevent repeatedly scheduling
  thousands of builds before the very first bitmap is available.
- The main screen now returns the original discovery and note lists
  directly during normal play instead of filtering/copying every point
  every animation frame. Time Machine filtering is unchanged.
- Off-screen journal icons are rejected before constructing paint objects
  or distance labels.
- All verified GPS history, separate manual discovery records and stored
  route segments are read-only inputs to rendering. No records are
  deleted, forged or merged by this optimization.

## Known limitations / testing

- The first fog rendering after opening a very large map can briefly
  display intact fog while the background raster is prepared.
- While rapidly zooming, cached silhouettes are temporarily scaled. The
  accurate full-resolution fog appears after zoom stops.
- This changes how fog and GPS route are *drawn* only, not the underlying
  GPS fix acceptance, history, mission completion, location recording or
  saved backups.
- JVM unit checks cover policy and snapshot properties; Android automated
  tests/debug build must pass before merge, signed main release after.
- **Important real-device regression checks:** continuous 15-second pan,
  two-finger pinch in/out at several zoom levels, map rotation, switching
  fog on/off and live GPS track, existing note pins, brush, 3,500+ point
  histories, long pan beyond cached bounds. A production device frame
  benchmark is not available in the GitHub CI environment.
