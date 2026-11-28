# TerraUnlocked 0.4.79 — Global Objectives + Fluid Fog Reveal

This version changes only objective discovery/loading and the visual/manual
fog-reveal pipeline. Existing Dubai ReconObjectives, user-added SAVED objectives,
stable IDs, GPS proof, notes, themes, Journeys, and other map layers are retained.

## Global Objectives

- Five importance tiers: world (1), regional (2), city (3), community (4),
  local (5). As users zoom in, less prominent objectives appear; at continent/
  world zoom only the few iconic world landmarks remain.
- A small, sourced, read-only starting catalog covers notable landmarks in
  different continents. This is NOT a complete database, popularity ranking or
  confirmation that a place has been visited.
- From zoom >=9, OpenStreetMap Overpass requests target one bounded nearby
  geographic cell appropriate to the zoom band. The application NEVER queries
  the entire world. Only named museums, attractions, heritage, natural features
  and small cultural POIs are considered; restaurants and general businesses are
  not automatically mission objectives.
- Bounded (up to 240 items/cell), rate-limited (>=9s between requests) and
  cached by zoom band + geographic cell in local preferences. Each region is
  retained separately for offline use, with a six-hour refresh interval,
  at most 32 cached cells and a 160-item stable-ID retention set. Pan/zoom
  debounce is 440ms and networking runs on an executor, never inside map draw.
- Stable OSM IDs use `osm_node_<id>`, `osm_way_<id>`,
  `osm_relation_<id>` and always link to their own OSM feature.
- Existing Dubai objectives retain their original IDs. Incoming duplicates
  of Dubai or other cached places with the same normalized name within 400m
  are suppressed without deleting, modifying or completing local objectives.
- On map and in Missions/Discoveries/Tasks, global objectives use the SAME
  existing visual grammar, categories, GPS proof, plan/Journey and Field
  Journal logic. Chosen OSM features retain their metadata across re-fetches.
- Optional `global_objectives.json` backs up retained worldwide metadata;
  `objective_state.json` and `journey.json` also preserve those IDs. The
  archive format remains backward-compatible; cached public OSM tiles are
  never needlessly included in user backups.

## Fluid Fog Reveal

- Manual PAINT begins immediately under the first touch. An offscreen,
  half-resolution screen-space bitmap holds the cached fog so each frame
  requires only one inexpensive textured quad instead of a full-screen
  GPU saveLayer and replaying ~1800 projected stroke segments.
- Each interpolated soft radial stamp removes the fog over **210 ms**
  with smooth-step easing, initial opacity response and a subtle moving
  scanner halo. MotionEvent historical samples are consumed, and points
  are interpolated at radius-relative distances (spatial gaps filled even
  on quick strokes); any committed segments are still buffered in the
  production fog rasterizer.
- The temporary preview is retained until the background raster accepts
  new manual points, so there is no abrupt hard-marker -> delay -> reveal
  sequence. All saved records are still written in exactly the prior
  DiscoveryPoint JSON schema.
- A small pending manual-stroke journal is appended quickly; merging it
  into the large historical discovery JSON happens on the shared serial
  writer away from the UI thread. Reads include pending strokes; backups
  flush pending changes; undo/reset supersede queued changes so they cannot
  reappear after removal.
- Cached fog masks now use a **clamped bitmap shader** across the entire
  viewport instead of four hard edge strips and filtered edge pixels.
  Deferred raster snapshots no longer cancel themselves on every pan event,
  and expensive full-history projection waits until gesture activity stops.
- The goal is a visually smooth 60Hz interaction; achieving stable 60 FPS
  depends on device/GPU, map tile latency, prior history size and thermal
  conditions, and requires real-device profiling.

## Scope and constraints

- Overpass is a community-run data source. Coverage, place names and
  importance tags vary; network errors retain cached objects and world
  landmarks instead of creating false POIs.
- The worldwide fixed world seeds and tile centres are approximate points,
  never precise entrances or accessibility guarantees.
- Do not alter the original Dubai objective list, check-in distance/photo
  requirements, evidence history, Journeys, SNAP, taxi/mall layers,
  display themes or navigation controls.
- Real-world QA: Dubai and Paris at zoom 4/8/12/16, an offline reopening,
  a cross-border pan, original Dubai mission progress, a quick manual
  swipe and several seconds of continuous panning, undo/redo and ZIP
  export/restore. Automated JVM tests cover tier thresholds, identity,
  deduplication and brush interpolation; CI runs unit tests and Android build.

Coordinate references for the initial global landmark seeds include
[Duck & Compass](https://duckandcompass.com/pages/famous-coordinates),
[Simple Map Lab](https://www.simplemaplab.com/tools/latitude-longitude-map),
and [World Wonders geographic worksheet](https://ourfamilycode.com/wp-content/uploads/2021/03/Wonders-of-the-World_longitude-latitude-matching-activity.pdf).
