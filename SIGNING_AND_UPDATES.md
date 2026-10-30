# TerraUnlocked signing and updates

The development/debug APK produced by GitHub Actions is **not** an update channel. Android requires the exact same signing certificate for upgrades.

## One-time release key creation

On a trusted computer with JDK installed:

```sh
keytool -genkeypair -v -keystore terra-release.jks -alias terra -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 terra-release.jks
```

Copy the encoded keystore into GitHub repository Settings → Secrets and variables → Actions as **TERRA_KEYSTORE_BASE64**. Also create:
- **TERRA_KEYSTORE_PASSWORD**
- **TERRA_KEY_ALIAS** (e.g. `terra`)
- **TERRA_KEY_PASSWORD**

Keep the .jks file and passwords in a separate, securely backed-up location. Losing the keystore means the existing app cannot be upgraded with a new key. **Never commit the key or passwords to Git.**

Once all four secrets are set, GitHub Actions builds a **TerraUnlocked-signed-release** artifact in addition to the debug APK. Use only that signed-release channel for installed versions.

## Migration from the existing debug builds

1. In the old installed app, export **DATA → EXPORT BACKUP ZIP** and verify the ZIP is safely saved outside the app.
2. Uninstall the old debug-signed app. This removes the old app's local storage.
3. Install the signed release APK, then import the ZIP.
4. For all future versions, download and install a newer APK signed by the **same** permanent key.

Do not uninstall or migrate until a working signed release artifact is available and you have verified the backup.

## Update checker

DATA → CHECK FOR UPDATES reads the latest *published GitHub Release* and opens its release page. It does not treat debug Actions artifacts as installable upgrades. A future release workflow can publish signed APKs to GitHub Releases.

To publish a release, attach the *signed-release* APK under a GitHub Release tag (e.g. `v0.4.5`). Never attach unsigned or debug APKs to the upgrade channel.
