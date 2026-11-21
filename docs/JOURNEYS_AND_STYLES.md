# TerraUnlocked 0.4.72 — Journeys, three styles and visible user position

## Navigation identity

The existing color theme in **Settings → Display** also sets presentation vocabulary
and canvas typography; no stored objective IDs or category identifiers change:

| Function | Military | Explorer | Civil |
| --- | --- | --- | --- |
| Top navigation | Missions | Expeditions | Tasks |
| Individual goal | Mission | Discovery | Task |
| Multi-stop Journey | Operation | Expedition | Itinerary |
| Journal | Field Journal | Explorer's Log | Notes |
| Home/Office heading | Bases | Waypoints | Saved Places |
| Statistics | Intelligence | Exploration Stats | Activity |

TRACK, LOCATE, MARK, ERASE and SETTINGS remain consistent. A different
visual style never changes the requirements to verify a destination.

## Multi-stop Journey

- Open the top Missions/Expeditions/Tasks list. Use the prominently displayed
  **Operation/Expedition/Itinerary** control to plan several destinations.
- Check at least two objectives, up to 40. The order in which new stops are
  chosen is kept as the journey order. Tick off and reselect an existing stop
  to append it later in the sequence. The current sequence is displayed.
- **Save & Start** stores the ordered list and focuses the first unfinished
  objective. **Pause**, **Resume**, and **Clear** are available later.
- The objectives list displays completed/total and a **NEXT STOP** command;
  it sorts the current journey stops first.
- On the map, numbered badges show which objectives are in the current
  Journey. No straight segments are drawn as false road navigation, and no
  real-world route durations or distances are fabricated.
- When a photo-backed, fresh-GPS-confirmed objective is completed, its existing
  persisted completion flag is used for Journey progress. The next incomplete
  stop becomes the focused objective. Completed objective records, history
  and photos are not modified by editing or clearing Journey membership.
- The objective detail bubble can add/remove an individual stop to/from the
  current plan. Hiding/deleting an objective also removes it from the plan.
- Journey is an optional JSON list of stable objective IDs plus an enabled
  flag. It is backed up in optional **journey.json** and restored after
  places.json, with invalid/deleted IDs ignored. Legacy ZIP archives without
  journey.json remain compatible.

## User location above all map markers

DiscoveryFogOverlay owns the user-position drawing routine but no longer draws
the user pointer within the fog layer. The map installs a final drawing-only
overlay *after* notes, objectives, metro, taxi, HOME and OFFICE markers, so the
red user pointer cannot be hidden under the saved HOME house at the same point.
This is purely a map rendering change, not a GPS/tracking or location-data change.

## Verification

PR CI must pass unit tests (Journey normalization, ordering/progress, all three
style mappings) and debug Android build before merge. The main-branch release
build produces a consistently signed APK. On a real device verify overlapping
HOME + current location, theme changes, a two-stop plan, pause/resume, photo
completion, and backup export/import.

The app intentionally does not use this feature to generate road routing,
provide turn-by-turn directions, or bypass GPS and camera proof.
