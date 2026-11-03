# TerraUnlocked v0.4.41 — Background tracking and GPS diagnostics

## Tracking lifecycle

- TRACK is an explicit opt-in; STOP in Terra or the persistent Android notification is an explicit opt-out.
- Tracking runs in a **visible foreground location service**, including when the Activity is not visible.
- Swiping Terra out of Recent Apps does not deliberately stop the service (`stopWithTask=false`).
- The requested tracking state is stored locally so Android `START_STICKY` can restore a killed service where platform rules permit. A null-intent restart never enables tracking unless the user previously pressed TRACK.
- No automatic tracking after device reboot and no bypass of Android Force stop. Samsung battery restrictions can still terminate background tracking.
- On Android 10+, users can explicitly enable **Allow all the time** through KIT → DATA → BACKGROUND GPS PERMISSION; Android 11+ opens app settings. Precise location should be enabled for 20m discoveries.
- The foreground service keeps a visible notification with a STOP action. If no fix arrives for 45 seconds, it records a no-fix diagnostic and updates the notification.

## GPS diagnostics

- KIT → DATA → GPS DIAGNOSTICS / EXPORT CSV.
- The file stays private on device until the user explicitly exports it. Contains precise locations, timestamp, accuracy, speed, confidence, mode, decision and reason.
- Accepted fixes: `SAVED / VERIFIED`. Other decisions include `REJECTED`, `SKIPPED`, `NO_FIX`, and service lifecycle `EVENT`.
- Diagnostic reasons include `INVALID_FIX`, `STALE_FIX`, `GPS_JUMP`, `POOR_ACCURACY`, `LOW_CONFIDENCE`, `SPEED_CONFLICT`, `IMPLAUSIBLE_STEP`, `BELOW_SPACING`, `NO_CALLBACK_45S`, `TRACK_START_REQUEST`, `STICKY_RESTART`, `TASK_REMOVED`, `SERVICE_DESTROYED`, and `TRACK_STOPPED`.
- The local CSV is bounded to roughly 1.5MB, retaining recent records. Clearing it does not erase discoveries. It is not automatically sent to any server.
- Logs start only after updating to this version; earlier rejected fixes cannot be reconstructed from the exploration backup.

## Scooter mode

A median of recent verified speed readings and hysteresis prevents a single zero-speed reading from switching SCOOTER to WALK. If Android reports zero/unknown speed, plausible positional speed can supplement it only when accuracy and elapsed time support the estimate. Inertial evidence alone does not create visited coordinates.

## Recovery and tests

Short gaps are connected only when `TraceReconstruction.shouldJoin` verifies time, speed, distance, accuracy and/or directional evidence. OSM smart path candidates remain dashed estimates, not permanent fog removal.

Unit tests include movement profile transitions and a synthetic 4 km scooter ride with two-sample GNSS outages and an impossible multipath jump. Passing tests do not prove reliability on a specific Samsung device. Field verification should include a ride with the screen locked, another with the Activity removed from Recents, then export the CSV.

## Update fallback

If the GitHub API check fails, Terra displays the error and provides **OPEN RELEASES** to continue in the browser.
