# TerraUnlocked 0.4.77 — brush, fog and heading-up map

## Brush draw performance
Before 0.4.77 every PAINT MotionEvent could add discoveries and serialize the
entire stored history, save all path segments and invalidate an expensive
blurred fog mask. On large histories this blocked the UI thread.
- During touch/drag, keep a transient array of brush screen-world points.
- Render the active stroke directly as a transparent clearing in the
  already composited fog mask (PorterDuff DST_OUT). No permanent writes or
  recomputation of the organic bitmap per MOVE.
- On ACTION_UP: take one undo snapshot, downsample stroke based on brush radius,
  commit discovery and segment lists once, then recompose fog from permanent data.
- On multi-touch cancellation: discard the uncommitted preview and return
  control to pan/zoom; never write unintended GPS or manual points.
- Brush size and all saved discoveries remain backward compatible. Undo remains
  one event per continuous brush stroke.

## Fog edge stability
- Old fog bitmap overscan was 160 screen pixels and its temporary pan
  translation could exceed the bitmap area for a long drag, exposing abrupt
  seams. New overscan is half the larger viewport dimension with safe
  translation constraints and recomposition when the cache exceeds bounds.
- An osmdroid map rotation must not invalidate the fog mask just because
  compass heading changed. Orientation is applied by the MapView canvas
  projection; the organic bitmap key now depends on actual discovery content,
  viewport size and radius rather than every compass angle.
- The fog compositor extends its layer rectangle for the currently rotated
  viewport so map corners remain covered when orientation changes.
- These adjustments are GPU-mask/cache changes only: they do not reveal
  unverified GPS locations or merge previously distinct route segments.

## Heading-up / user-position map lock
- New on-map **N / ⇧ UP** button under the top right, also controlled by
  tapping the existing compass header.
- N = north-up. ⇧ UP = follow verified user position and rotate the map based
  on device orientation for easy relative left/right.
- Rotation vector remapped to actual device screen rotation; magnetic compass
  converted toward true north using cached GeomagneticField declination.
- Angular shortest-path smoothing and minimum update interval avoid abrupt
  spins or jitter across 359°→0°. Orientation toggle saved locally.
- A deliberate 22dp map drag releases follow+heading lock; a small finger
  wobble while tapping does not. Existing LOCATE can still recenter.
- No camera/location permission changes; no route or location claims.
- If rotation-vector sensor unavailable, the app remains in north-up.

## GPS inspection from user-provided exports
The supplied GPS diagnostics cover Nov 24 22:48 to Nov 25 22:17 Dubai
time. 334 SAVED (215 Fused, 119 GNSS) with median 18.9m accuracy;
7,422 REJECTED, including 5,108 POOR_ACCURACY and 1,558 LOW_CONFIDENCE.
2,913 EVENT records included 708 PROVIDER_AVAILABLE and 708
PROVIDER_UNAVAILABLE transitions, warranting follow-up but not proof of
equivalent GPS hardware resets. The ZIP trace contains 3,556 accepted
positions across multiple days with median 9m accuracy. No GPS filtering
thresholds or provider recovery timings are changed in this release.

## Verification
- Kotlin unit tests for heading-up rotation math, normalizing angles,
  shortest-turn crossing north and jitter thresholds.
- GitHub Actions unit tests/debug APK required before merging, followed by
  signed release APK from main.
- Real-device QA: continuous 20+ second brush stroke with thousands of
  historical discoveries, quick map pans across screen edges, compass
  rotation near 0/360°, manual pan unlocking, north-up restoration and
  preserved GPS discovery history.
