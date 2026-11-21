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

## v0.4.67: Minimum GPS spacing and durable tracks

- Active Fused Location Provider request is 1.5 seconds (minimum 0.75 seconds). Actual delivery remains subject to Android hardware, power policies and urban signal quality.
- The watchdog now **removes then re-registers** its Fused callback on a missing callback/verified-fix interval. Re-register success is not evidence of a fresh fix; diagnostics now distinguish `GPS_RESUBSCRIBE_OK` from `VERIFIED_GPS_AFTER_RECOVERY`.
- Walking/moving samples use **3 m at <=10 m reported accuracy**, **5 m at <=18 m**, **7 m at <=25 m**, and **10 m otherwise**. Unproven movement and stationary fixes retain larger spacing; inaccurate and implausible positions still cannot permanently reveal fog.
- `terra_gps_raw.csv` stores recent raw received coordinates. `terra_gps_trace.csv` stores verified fixes grouped into separate plausible trace segments. Both are private and exported with the voluntary Terra backup, along with `terra_gps_diagnostics.csv`.
- Route overlay now defaults to visible; only verified, physically plausible short segments connect. Long gaps are not silently invented.
- RESET EXPLORATION deletes raw/trace histories and diagnostics too, because they also contain precise past coordinates.
- Real-world comparison with Google Maps on the same route is still necessary. Resubscribing cannot manufacture GPS fixes when the provider is unavailable or the sky view is obstructed.

## v0.4.68: Multi-source GNSS and urban canyon continuity

- While TRACK is active, the foreground service subscribes to three Android location streams: Google Play Services `FusedLocationProviderClient`, Android `LocationManager.GPS_PROVIDER` (direct hardware GNSS) and `LocationManager.NETWORK_PROVIDER` (Wi-Fi/cell estimates).
- `GPS_PROVIDER` can use any satellite constellation supported and enabled by the **phone's hardware and firmware**, including GPS, Galileo, GLONASS and BeiDou. The app cannot manually connect to unrelated orbital satellites or overcome blocked sky view.
- Direct GNSS and Fused candidates share **one** chronological, confidence/accuracy-filtered discovery pipeline. Rejected or older fixes cannot reset its verified-fix anchor; duplicate provider outputs cannot independently unlock the same location.
- Network fixes are **preview-only** when GNSS/Fused verified fixes are stale and network estimates are recent with reported horizontal accuracy <=120 m. They never remove fog or become confirmed route segments.
- Diagnostics and exported raw/verified GPS CSV histories label the receiving source (`FUSED`, `GNSS`, `NETWORK`). The optional GNSS status callback periodically logs visible satellites, satellites used in a fix and supported constellation labels.
- The watchdog checks Fused and direct GNSS streams separately; it can re-subscribe a stalled GNSS listener. Fused resubscribe remains rate-limited. The check runs every 15 seconds.
- The map status line labels the location source. Network hints remain visibly `GPS ESTIMATE` instead of `GPS VERIFIED`.
- This increases GPS and CPU activity and may raise battery consumption. It **does not** guarantee fixes beside skyscrapers; compare locked-screen walks and diagnostic CSV with v0.4.67 before attributing improvement.

## v0.4.69: Continuous GNSS acquisition instead of repeated restart

- The exported November 20, 2026 diagnosis included 303 direct-GNSS resets and no direct GNSS coordinate fixes on that date. Satellite-status samples repeatedly reported 1 visible, 0 used. Fused continued providing coordinate callbacks while direct GNSS did not.
- Keep direct Android GNSS subscribed while GNSS satellite-status callbacks show an active search, even with zero satellites used for a fix. Recover the direct listener only after 5 minutes without either GNSS fixes or satellite-status activity, and at least 10 minutes since the previous attempt.
- Keep the Google Fused listener subscribed if fresh callbacks arrive but accuracy is poor. Re-subscribe only after a genuine 60-second callback outage, with a 3-minute minimum cooldown.
- The 15-second heartbeat remains; diagnostic `GNSS_SEARCHING_NO_DIRECT_FIX` is now rate-limited to 5 minutes. Real GNSS signal blockage is reported rather than papered over with fabricated coordinates.
- Conservative discovery thresholds, 3-m minimum moving spacing, and protection against unverified fog removal remain unchanged.
- This change is a hypothesis-driven recovery fix, not proof that the physical GNSS chipset will acquire a satellite fix beside skyscrapers. Test under clear sky and inside JLT and compare satellite counts, accepted fixes and gap durations.

## v0.4.70: Adaptive REST battery saver for bounded GPS jitter

- TRACK now supports a temporary REST mode without stopping the foreground tracking service or losing saved progress.
- Enter REST **only after at least 90 seconds of stable, verified high-accuracy (<=25 m) fixes** in a small region (90th-percentile radius <=13 m; maximum radius <=19 m), predominantly low reported speed, and at least 75 seconds without meaningful inertial activity. This recognizes the noisy "fly-like" urban GPS drift without mistaking every small zigzag for real travel.
- During REST, temporarily unsubscribe this app's high-frequency direct GNSS callbacks; keep the Android network provider for coarse movement hints and switch Google Fused to BALANCED_POWER_ACCURACY at a 10-second target interval. A deliberately REST-mode location cannot itself unlock new fog.
- Immediately wake high-accuracy Fused + direct GNSS on sustained linear acceleration, verified displacement beyond the rest zone, or two consistent coarse network/Fused positions substantially beyond it. Coarse hints **never** independently reveal fog.
- A maximum 90-second REST interval prompts a precise acquisition probe even if the phone's sensors miss a gentle start; the probe stays ACTIVE until repeated reliable stationary evidence again supports REST. May spend more power near skyscrapers, where fixes are unreliable, to avoid silent coverage gaps.
- UI shows GPS REST / BATTERY SAVER / AUTO WAKE and diagnostic CSV logs REST_ENTER and REST_EXIT reasons, plus REST_JITTER skips.
- No extra Android runtime permissions required; uses the existing linear-acceleration sensor. Actual energy saved depends on device hardware, other applications, and Android's shared GNSS scheduling, and must be field-measured rather than assumed.

## v0.4.71: Expedition UX and map panning

- **Pan responsiveness:** while a map drag or pinch is active, translate the existing validated fog mask instead of re-blurring all visited geometry every ~80 pixels on the UI thread. Deferred invalidation recomposes after the gesture settles. Debounce objective bubble alignment and throttle compass-triggered map redraws. This is a frame-latency improvement, not altered exploration data.
- **Settings:** bottom KIT becomes SETTINGS (gear icon). Three persisted interface palettes appear in Settings → DISPLAY:
  - MILITARY (original olive + brass, default, unchanged theme choice)
  - EXPLORER (pine green #162720 + aged-gold #D4A66B + parchment #F3E3C2)
  - CIVIL (navy #142735 + cool blue #82BDD5 + copper #E6A066).
  Styling concept informed by Pinterest design/palette articles; these are original UI color definitions.
- **Field Journal:** antique parchment texture behind journal and note editor/detail cards; high-contrast sepia ink for legibility. The paper is drawn locally (no remote images or uploads).
- **Known Locations:** long-press the Journal side tab or tap its new KNOWN LOCATIONS entry; choose HOME or OFFICE, then go to it, save current GPS, set map center, or remove the saved anchor. Existing anchor preferences remain compatible.
- **MARK:** replaced the generic pencil with a quill icon.
- **Saved pin note icons:** choose a new symbol from a visible selection strip; selection now immediately saves to local Field Notes and redraws the map (title/body still use the SAVE button).
- **Ruler:** high-contrast dark outline, theme-colored bright line and a readable distance badge rendered at its midpoint, as well as the existing caption bar.
- No location permissions, tracking policy, backup schema or existing saved locations are modified.
- Validate pan smoothness with large histories, Map/Journal editing and each palette on a real Android phone; unit/build CI cannot measure frame timing.
