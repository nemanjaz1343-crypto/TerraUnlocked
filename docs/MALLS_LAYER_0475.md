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
