#!/bin/sh
# Build + install the debug APK on the connected Thor.
set -e
root="$(dirname "$0")"

# Spotify does not publish App Remote to Maven and the AAR is not kept in this repo:
# fetch the official release once, then check it is byte for byte the file we expect.
aar="$root/app/libs/spotify-app-remote-release-0.8.0.aar"
sha="b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"
url="https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar"

if [ ! -f "$aar" ]; then
    echo "fetching the Spotify App Remote SDK"
    mkdir -p "$root/app/libs"
    curl -fsSL "$url" -o "$aar"
fi
if command -v sha256sum >/dev/null 2>&1; then
    echo "$sha *$aar" | sha256sum -c - >/dev/null || {
        echo "the App Remote AAR does not match the published checksum" >&2
        exit 1
    }
fi

JAVA_HOME="$HOME/jdk21" "$HOME/gradle-8.14.3/bin/gradle" --project-dir "$root" assembleDebug "$@"
"$HOME/android-sdk/platform-tools/adb" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
