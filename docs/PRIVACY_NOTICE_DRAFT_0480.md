# TERRA Privacy Notice — DRAFT for owner review

**Not a published legal policy.** Review app behavior, hosting/provider terms,
app-store requirements and provide a verified support contact before public beta.

## Data stored locally
TERRA can store precise GPS coordinates, movement times and accuracy/quality,
recorded/discovered areas, manually erased or painted areas, objectives and
completion evidence, saved destinations, journal entries, photos, short videos,
voice recordings and optional GPS diagnostics. Most are stored in Android app
private preferences/files. Background tracking begins only after the user
explicitly activates TRACK and grants the necessary permission; a foreground
notification indicates that recording is enabled.

## External network services
When network map features are enabled, your device contacts external providers
for map tiles and place/objective data. OSM Overpass requests include the map
area being displayed. Other online functions may use Open-Meteo, Photon search
and GitHub release checks. These third parties may receive network information
such as your IP address and the requested map region. TERRA does not control
their logging or retention practices.

## User choices
- TRACK can be stopped from the application or Android notification.
- LOCATION and microphone permissions can be denied/revoked in Android Settings.
- An exported ZIP/CSV is written to a user-chosen location, and the user is
  responsible for who receives that sensitive location history.
- Importing a ZIP may replace the current personal profile, and now requires
  preview and confirmation. Existing data is staged for recovery.
- RESET EXPLORATION deletes discovery/GPS records as documented; verify
  separately how related media and user journal entries are handled.
- Uninstalling the app ordinarily removes its private local data, except
  separately exported files and data held by external map/network providers.

## Permissions requiring owner approval
Precise and background location, foreground service, notifications,
microphone and install-package permission (for optional APK self-updates)
must be explained in the publication listing. Browser-based issue reporting
is user-initiated.

## Before publishing
Confirm external providers and their privacy policies, the absence/presence
of any analytics/crash SDKs, complete data deletion behavior, retention rules,
minors' access if applicable, official contact email, legal entity and local
jurisdiction. Do not publish this draft as a finished policy.
