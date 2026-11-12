# TerraUnlocked v0.4.65 — Background tracking and GPS diagnostics

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

## November 2026 locked-screen scooter fix

- ACTIVE GNSS requests are 3 seconds (minimum 1.5 seconds); ECO remains slower only after a confident extended stop. Android may still batch or lose fixes under interference.
- The watchdog now monitors **verified** fixes separately from any location callback. A stream of poor-quality GPS results cannot keep it from attempting recovery every 90 seconds when active.
- Scooter profile switches to WALK only after sustained reliable low-speed readings; missing speed does not count as proof of walking.
- Positional-speed inference accepts well-separated, reasonably accurate verified points up to 90 seconds apart (previously 25 seconds), but only for motion classification/sampling, **never** as invented route coordinates. Long/ambiguous gaps remain covered by fog.
- New diagnostic events: `GPS_RESTART_NO_CALLBACK`, `GPS_RESTART_NO_VERIFIED_FIX`, `VERIFIED_GPS_AFTER_RECOVERY`, `MODE_WALK_TO_SCOOTER`, etc.
- Battery optimization must still be disabled manually on Samsung; nothing here bypasses platform restrictions or guarantees a fix beside high-rise buildings.

## v0.4.66: Accurate resumed-screen status

- Reopening Terra with a recent (<25-second) verified cached location now displays **GPS RECENT / age / accuracy**, not the misleading **REFRESHING GPS** message. The next foreground update replaces it with the live verification status.
- Older cached positions show **AWAITING FRESH GPS** and do not imply the provider is live.
- If Android has interrupted tracking but the user previously requested it, the interruption warning takes priority over the cached-position label.
- This is a UI correctness fix only: genuine missing GNSS callbacks and long, unverified travel gaps cannot be solved by connecting unknown geography.
