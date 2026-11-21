# TerraUnlocked v0.4.73 — Quick SNAP and Expedition UX

## SNAP: capture first, place later
- Sixth bottom control **SNAP** opens TAKE PHOTO, RECORD SHORT VIDEO, or RECORD VOICE MEMO.
- Camera returns are staged in the app's private files area. Successful captures show MY CURRENT GPS LOCATION, PICK A POSITION ON THE MAP, or KEEP FOR LATER. Discard deletes a temporary capture.
- A current-location attachment is permitted only with a coordinate received within 30 seconds and reported horizontal accuracy at most 60 metres. Otherwise the user must select the position on the map.
- PICK ON MAP activates a temporary map mode and leaves a visible instruction bar. Tapping any desired point creates a FieldNote. The user can pan/zoom before tapping.
- Photos, short video clips (max 30 seconds/24 MB requested of camera), and audio recordings remain ordinary FieldNote attachments and use the existing backup/export mechanism. Videos are shared with an external video player through FileProvider and removed only when the user chooses removal.
- No capture completes objectives or bypasses mission photo/GPS evidence; SNAP and mission-proof images are separate features.
- A staged capture survives activity state restoration when the staged private file remains available, but is not a cloud upload or background capture.

## Planner: tap destinations on the map
- From Missions/Expeditions/Tasks, press PLAN OPERATION / EXPEDITION / ITINERARY to enter an on-map planner.
- Tap destination icons to append numbered stops or remove existing selected stops. The map shows prominent numbers and directional high-contrast straight connectors indicating visit order only, **not actual street routing**.
- Compact floating panel: ORDER, CLEAR, SAVE, EXIT. ORDER opens a reorder list supporting long-press drag-and-drop handles plus ↑ / ↓ accessible alternatives. CLEAR confirms before erasing saved plan; EXIT discards unsaved edits.
- The existing next-stop/proof rules remain intact; each objective must still meet the original live GPS and camera evidence requirements.

## Journal and voice controls
- Keep consistent compact gutters, controls and media rows in the pin-detail dialog. The dialog window and ScrollView now use opaque parchment, preventing unintended translucent panels after microphone/camera activity.
- A pin details screen displays separate photo, video and voice rows. Audio includes PLAY and REMOVE; videos open via a safe content URI and can also be removed with confirmation.
- The voice recorder has a live amplitude meter using MediaRecorder.maxAmplitude sampled at ~95ms, an elapsed timer, STOP, PLAY, RETAKE, DELETE and SAVE. Temporary files are not saved to the Journal until explicit confirmation.
- MARK uses a legible ballpoint-pen silhouette instead of a feather quill.

## Privacy and validation
- No new runtime permission besides existing RECORD_AUDIO, location and camera intents.
- SNAP videos are saved locally (as other FieldNote media); backups can grow significantly with video clips, and video camera output/quality varies between Android devices.
- GPS discovery and fog unlock rules are untouched.
- Automated Android unit tests and a debug build are required before merge. On-device checks needed for camera app compatibility, rotation/resume, microphone metering, video players, plan tap/drag geometry, opacity and UI readability on the Galaxy A53.
