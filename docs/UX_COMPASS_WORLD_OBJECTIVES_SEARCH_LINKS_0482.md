# TerraUnlocked 0.4.82 — Fluid compass, worldwide interests, centered search and softer markers

Scope: preserve existing saved discoveries, Journeys, camera proof, GPS service, backup and cached data. No schema migration.

## Changes

- **Heading-up map:** compass sensor readings update a target angle; the map rotates on the UI frame clock using time-based low-pass interpolation with a small angular deadband. Frame callbacks stop on pause/destroy and when heading-up is disabled. North-up remains available.
- **LOCATE:** repeated taps again progressively zoom 1.5x, capped at map maximum. A world view first moves to city scale; if only a stale GPS position is available, a fresh one-shot locate is attempted rather than claiming a live location. LOCATE does not record visited territory.
- **SEARCH:** existing worldwide address/place/coordinate/Google Maps link lookup gets its own magnifying-glass button in the exact center of a seven-slot bottom bar. Its native accessible touch target matches the visual button; prior actions retain their functionality.
- **Worldwide objectives:** less restrictive named OSM attraction, museum, history and park queries; city-level POIs become visible at earlier zoom levels even without Wikidata. Smaller regional request cells avoid oversized Overpass queries. Added 52 approximate landmark center seed points worldwide so common cities show discoverable points immediately and offline; the original 13 world landmarks remain at the farthest zoom.
- **Modernization:** anti-aliased rounded/chamfered objective and mall diamonds, light low-cost halos, softer panel borders and rounded action buttons; reduce repeated Canvas background grain work. 170–180 ms fade-in on search and objective info. Original MILITARY / EXPLORER / CIVIL palettes retained.
- **Reliable info links:** the objective bubble always includes a stable OSM map link. On OSM-backed objectives only, current official website tag is fetched lazily from the OSM API on a worker, cached for 24 hours and displayed if it has a safe HTTPS URL. Error/timeout retains the previously cached site or permanent map source. The app does not crawl or check every website at launch. Existing curated Dubai URLs remain optional legacy website suggestions, but no longer replace the permanent OSM source link.

## Limitations / QA

- OSM is community-maintained, so not every city attraction has relevant tags. The seed catalog is not exhaustive, and seed coordinates represent approximate centres, not entrances.
- A current OSM website tag can itself be stale. Never promise it is reachable; reliable source fallback is the OSM object or map coordinate URL.
- Public Overpass requests are throttled; a new city may require time and a data connection to load all smaller POIs.
- Real device QA required: repeatedly tap LOCATE and test with no/stale GPS; shake/rotate heading-up phone, screen lock and resume; pan/zoom fast at 360–600dp; search and map marker taps; Paris/London/Tokyo/Singapore/New York/Belgrade/UAE zoom 4, 8, 12, 16; toggle mobile data; open Objective INFO offline and test website fallback.
- GPS tracking data and discovery persistence are untouched. Android UI profiling is required before claiming stable 60 FPS.
