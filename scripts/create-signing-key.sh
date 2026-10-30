#!/usr/bin/env bash
set -euo pipefail
umask 077
if ! command -v keytool >/dev/null; then
  echo "Missing keytool. Install a Java JDK first (e.g. pkg install openjdk-17 in Termux)." >&2
  exit 1
fi
if ! command -v base64 >/dev/null; then
  echo "Missing base64 utility." >&2
  exit 1
fi
folder="${HOME}/terra-signing"
mkdir -p "$folder"
keyfile="$folder/terra-release.jks"
if [ -e "$keyfile" ]; then
  echo "ERROR: $keyfile already exists. Never replace your permanent signing key." >&2
  exit 1
fi
printf '%s\n' "Creating a PRIVATE Android signing key on this device."
printf '%s\n' "Choose a strong password and remember it. This step runs locally."
keytool -genkeypair -v -keystore "$keyfile" -alias terra -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12
chmod 600 "$keyfile"
echo
echo "Your private keystore is stored at: $keyfile"
echo "Back it up securely OFF the phone. Do not upload it to public GitHub files."
echo "Set these four GitHub Actions repository secrets:"
echo "TERRA_KEY_ALIAS  -> terra"
echo "TERRA_KEYSTORE_PASSWORD -> password you entered in keytool"
echo "TERRA_KEY_PASSWORD -> same password (unless you configured a separate key password)"
echo "TERRA_KEYSTORE_BASE64 -> base64 encoding of the keystore"
echo
echo "To copy Base64 to Android clipboard in Termux (after installing termux-api AND the Termux:API app):"
echo "base64 < \"$keyfile\" | tr -d '\\n' | termux-clipboard-set"
echo "Do NOT paste that Base64 string in chat or into a public issue."
