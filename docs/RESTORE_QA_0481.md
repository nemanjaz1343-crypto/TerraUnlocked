# TerraUnlocked 0.4.81 — Backup restore integrity hardening

This is a focused continuation of the v0.4.79 pre-release audit and the
v0.4.80 audit remediation. No new map layers, gamification or user data migrations.

## Additional correctness fixes

- `TerraArchive.importFrom` sets a process-wide import-in-progress gate,
  and refuses to restore while the tracking service is active.
- `TrackingService` rejects late starts while import or interrupted-restore
  recovery is in progress (including when started without an open Activity).
  It does not append GPS diagnostics into a CSV being replaced by restore.
- `MainActivity` disables new TRACK starts during import and recreates its
  Activity after a successful import to rebuild all caches and state. Previously
  the UI reloaded only journal notes, discoveries and paths, leaving cached
  global objectives and Journey potentially stale.
- `TerraArchive.applyImport` drains unknown ZIP entries through the same
  decompressed-byte budget as known entries. `ZipInputStream.closeEntry()`
  alone could have decompressed and discarded an oversized unknown payload
  without counting it in the safety limit.
- The restore path verifies synchronous `SharedPreferences.commit()` results
  for anchors, discovery JSON, objectives, global objective pins and Journey.
  Any reported write failure triggers the existing durable rollback path.
  Ordinary UI/cache updates keep their prior nonblocking `apply()` policy.

## Android runtime tests

`app/src/androidTest/java/com/terraunlocked/TerraArchiveInstrumentedTest.kt`
covers four isolated emulator scenarios:
1. Full ZIP round-trip preserves a verified discovery.
2. A simulated persisted in-progress rollback marker restores pre-import state.
3. An oversized unknown ZIP member is rejected without replacing the history.
4. Import fails while the GPS tracking service is reported running.

`.github/workflows/android-instrumentation.yml` adds a separate Android 35
emulator lane. The unit-test/assemble/build/release pipeline is unchanged.

These tests do **not** replace: force-stop at every restore stage, true
concurrent GPS callbacks, low-storage checks, large dataset frame profiling,
TalkBack checks, screen-locked field GPS trials and multi-device beta QA.

## Publication gate

A signed APK and green automated tests are necessary, but are NOT sufficient
to declare closed/public beta ready. Keep the v0.4.80 remediation document's
remaining on-device gates open until measured.
