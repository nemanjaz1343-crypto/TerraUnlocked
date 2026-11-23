# TerraUnlocked 0.4.75 — MALLS discovery layer

Dubai has a wide variety of retail centres, with distinctive architectural
characters. This feature adds named shopping destinations from OpenStreetMap,
including community centres rather than only internationally famous malls.

## Layer
- PLACES → MALLS in LAYERS, with cached count and Show/Hide.
- SCAN MALLS IN THIS AREA, plus MALLS ON/OFF under Settings → Display.
- Bounded nearby Overpass query for shop=mall, shop=shopping_centre,
  shop=shopping_center, named building=mall and selected named
  landuse=retail features. Only actual OSM objects become map pins.
- Async cache with cooldown and offline fallback, reused while panning.
- At city zoom, show curated landmarks for legibility; at neighbourhood
  zoom show smaller, otherwise obscure shopping centres as well.
- Solid themed shopping bag markers and optional labels on close zoom.
- User position, SNAP and Expedition mode drawing/input priorities remain
  unchanged.

## Field Journal and objectives
- Tapping a mapped mall displays the name, location, distance where known,
  optionally a distinctive short description and nearby mall journal count.
- Each venue offers Write a Mall Field Note and Save as Destination, retaining
  the existing GPS/photo confirmation requirement. Open in Maps is available.
- HTTPS site link is displayed only if present in OSM mapping. No visitor
  popularity, live crowd count or star rating is inferred.
- Editorial identity examples: themed Ibn Battuta courts, Wafi architecture,
  Mercato renaissance arcade and the Dubai Festival City waterfront.
- All data stays local except fetching the displayed map area from public
  Overpass services. No GPS tracks or journal files are uploaded.

## Verification and limitations
- OSM data may be incomplete and mapped venue centre points are not
  guaranteed entrances. The user can always create a custom destination
  if a mall is missing.
- Cache persists between app launches and can be displayed offline.
- No changes to existing discoveries, fog, routes, missions, tracking
  permissions or ZIP format.
- PR unit and debug builds plus signed release are required.

## v0.4.76 — visible diamond markers and offline landmarks

- The MALLS icon is now a solid, high-contrast diamond with a thick theme-aware
  border and contrasting central facet. Important destinations use a larger
  17dp diamond; other mapped centres use 14dp.
- Hit targets are enlarged to 38dp, featured names become visible at zoom 13.4,
  and markers start appearing at zoom 10.8; lower de-cluttering keeps more
  destinations on screen.
- Before the first successful live Overpass scan, seven Dubai landmarks are
  preloaded from published coordinate references (Dubai Marina Mall, Ibn Battuta
  Mall, Mall of the Emirates, Dubai Hills Mall, Dubai Mall, Wafi City and
  Dubai Festival City Mall). Coordinates are approximate complex centres, not
  entrances. Live OSM places still supplement these and the local cache is
  preserved; the bundled set prevents an empty screen if Overpass is down.
- Overpass background scanning can begin from zoom 11.2, avoiding a dead zone
  where the mall layer was switched on yet still had no scanned centres.
- Regression unit test verifies the bundled landmarks are unique and fall
  within the Dubai area. Map appearance and tap targets also need a real-device
  check because JVM unit tests cannot verify Canvas drawing.
