# TerraUnlocked v0.4.80 — Pre-release Audit Remediation

Basis: `TERRA_PreRelease_Audit_v0.4.79_2026-11-28.md`.
Scope: stabilize existing features without replacing user data.
**This is a developer update, not a declaration that public beta gates passed.**

## Per-issue implementation

| ID | Change in v0.4.80 | Validation still required |
|---|---|---|
| TERRA-001 P0 | GPS/brush Undo/Lasso/Erase use canonical cross-instance store lock and atomic read-modify-write. A stale UI snapshot cannot remove newly saved GPS rows. Pending strokes are journaled durably. | Instrumented simultaneous GPS/Undo/Erase, 1,000 iterations on Android |
| TERRA-002 P0 | Backup restoration stages a full rollback ZIP with a durable restore marker; if interrupted, startup attempts recovery before loading user stores. Media/GPS use bounded disk streaming, 256MB total cap, 600-entry cap and duplicate/unsafe-name rejection. | Valid, corrupt, low-storage and kill-during-restore tests on actual devices |
| TERRA-003 P1 | Read-only backup summary and explicit replacement dialog; import/export off UI thread; import refused while GPS tracker is running. | Accessibility, canceled restore and activity recreation |
| TERRA-004 P1 | First installation shows neutral world map; returning users see saved viewport or last known fix. No forced Dubai. | Clean profiles in multiple countries; offline |
| TERRA-005 P1 | Journey resolves saved/pinned global objectives, retains missing IDs and no longer claims 'all complete' for unloaded stop metadata. | Multi-country plan, zoom 5→17, offline relaunch |
| TERRA-006 P1 | Global objective worker queues bounded, centre-priority visible geographic cells, supports rotated viewports and incremental cached results. | Paris/London/UAE–Oman, rate-limit and mobile network switches; zoom <9 still uses a small curated 13-site world catalog |
| TERRA-007 P1 | Existing GPS recovery is retained; service failure/not-started notifications and one-shot LOCATE are made clearer, avoiding fake visited positions. | 30-minute urban + open-sky + locked-screen reference route, multiple phones |
| TERRA-008 P1 | Explain fix age / accuracy / distance check-in failures with manual RETRY, without weakening anti-spoof GPS+photo requirements. | 20 indoor + 20 outdoor check-ins |
| TERRA-009 P1 | One-time 3-step explanation of TRACK, LOCATE and MISSIONS with clear user choice. Tracking remains opt-in. | Five new-user 60-second walkthroughs |
| TERRA-010 P2 | Objective taps and Journey map selection use markers *actually drawn* after decluttering. | Dense clusters, different zoom levels, tap gestures |
| TERRA-011 P2 | Six main custom Canvas bottom commands have independent accessible native Button hit targets, while retaining expedition visuals. | TalkBack / Switch Access; advanced Mark palette still needs a real-device check |
| TERRA-012 P2 | Increase primary command/text sizes; make objectives and data menus fit smaller screens and scroll. | 360dp / 412dp / 600dp at 100–200% font scale |
| TERRA-013 P2 | Name-index global POI dedup and padded viewport spatial filtering reduce full-history fog projection work. | Perfetto p50/p95/p99 frames and 1x/10x/30x history |
| TERRA-014 P2 | Fog worker, queued callbacks and bitmap resources explicitly release in Activity teardown; stale worker results are discarded. | 20 rotations/recreations + 10 min pan/zoom; memory profiler |
| TERRA-015 P2 | Errors preserve cache and show user-visible notice; explicit retry for global Objectives; viewport queue remains throttled. | 50 connectivity toggles and Overpass rate-limit test |
| TERRA-016 P2 | LOCATE no longer zooms by 1.5x on every click. | Repeated clicks at zoom 13/16/18 |
| TERRA-017 P2 | Manual pan pauses following without silently resetting heading-up orientation. | Compass and pan under all headings |
| TERRA-018 P2 | Denied permissions and foreground-service failure shown explicitly, including settings link; block cleartext HTTP. | Android 10/12/13/14/15/16 permission matrix; policy review |

## Verification completed

- GitHub CI runs `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`.
- New JVM tests exercise 1,000 deterministic *stale snapshot* merge cases
  and viewport-cell bounds/dedup. These are not real GPS/race instrumentation.
- All five supplied ZIP backups were inspected offline: required JSON
  files parse, ZIP CRC passed, uncompressed totals stay inside new bounds.
  **Not an Android import/export round-trip.**
- No change to stored schema for discoveries, Journal notes, original Dubai
  objective IDs, device GPS trace or real-user files in this repository.

## Blocking before PUBLIC BETA

1. Reproduce/verify rollback on a real Android device with force-stop / low
   storage, corrupt ZIP and active GPS prevention. Never call this P0 closed
   solely because code builds.
2. Test reliable GPS with independent reference recording in a dense urban
   canyon and open sky, both screen-on and locked, for at least 30min each.
3. Measure cold start, memory (PSS), battery/hour, mobile data, and frame
   p50/p95/p99; do not claim 60 FPS until measured.
4. Five first-time users, high font scale, TalkBack and tablet orientation.
5. Publish reviewed privacy policy, permissions rationale, user recovery help,
   support/reporting channel and complete platform distribution requirements.
6. Global zoom <9 still has a curated but incomplete world landmark seed; do
   not claim exhaustive worldwide ranking. `Trace Mode` with time expiry and
   `Pro` payments are not implemented and were out of remediation scope.

## Suggested manual test matrix

| Scenario | Expected outcome |
|---|---|
| TRACK, simulate service GPS append, Undo manual stroke | Verified GPS remains byte-for-byte in canonical discovery history |
| Long brush drag + immediate force-stop | Committed strokes present via pending journal after restart |
| Corrupt ZIP, cancel import | Old data unchanged |
| Kill during restore | Startup recovers prior ZIP or fails closed; no silent mixed profile |
| Cross-country Journey and reopen offline | IDs, next stop and progress persist |
| Map pan/zoom across adjacent cells | Newly mapped objectives load without losing persisted entries |
| No GPS permission on new install | Neutral map still usable and location rationale visible |
| GPS becomes unavailable while TRACK active | UI/service clearly distinguishes uncertain from verified locations |
| Tap overlapped hidden objective area | Only drawn symbol is selectable |
| Rotate Activity 20 times | Fog bitmaps/workers do not accumulate |

This documentation is a code audit completion checklist, not a substitute
for Android instrumentation, real-device performance, Play policy or user QA.
