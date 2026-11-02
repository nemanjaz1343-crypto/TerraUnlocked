# TerraUnlocked Path Intelligence v0.4.40

## What works

1. **Fused location + low-rate accelerometer context**: Android Fused Location Provider remains authoritative for map position. Optional built-in linear-acceleration sensor indicates recent motion, supporting walking classification, and is unregisterd on stop; it never supplies guessed coordinates.
2. **Confidence score (0–100)**: quality of location *evidence*, not a mathematical probability of where the user was. Accuracy, staleness, speed/jumps and (at most a small bonus) inertial motion are evaluated. Only accepted GNSS/Fused observations can permanently reveal fog.
3. **OSM map matching**: When a gap remains in the trusted trace, an already-existing OSM smart-recovery view now projects endpoints onto actual polyline segments (not only way nodes), rejects overly long detours and ambiguous streets, then shows eligible paths as dashed estimates. No inferred path is persisted as a discovery.
4. **Smart fog**: Trusted 20-meter discoveries are retained permanently; uncertain fixes display temporarily as small dashed circles above fog; potential OSM routes are labelled EST with a confidence indicator and remain visually distinct from revealed terrain.
5. **Network and battery restraint**: Re-scans are gated by new samples and at least eight minutes; avoids OSM calls in offline mode; motion sensor uses a low update rate and stops with tracking.

## Test and safety constraints

Unit tests cover poor precision, staleness, teleports, actual projected OSM segments, and path detours. A passing Android build verifies compilation, not live GPS accuracy.

A GPS blackout does **not** prove the user traversed a specific path. If two mapped paths are plausible, neither is automatically revealed. GPS dead reckoning with IMU alone is not reliable for long walks with a phone held or pocketed in arbitrary orientation. The 20 m radius remains unchanged.

## Privacy

Exploration history is stored locally. Estimated weak fixes exist only in memory and expire after 90 seconds. OSM geometry matching uses a bounding box around the visible map area and a public Overpass service; when active, that approximate area is sent to the server. The app does not send the full recorded trail.
