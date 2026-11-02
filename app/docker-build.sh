#!/bin/bash
# Läuft IM Container "thor-build" (siehe build.sh). /work = Arbeitsverzeichnis auf dem Build-Host.
set -euo pipefail
W=/work
mkdir -p $W/keystore $W/out
if [ ! -f $W/keystore/thorpipboy.jks ]; then
    keytool -genkeypair -keystore $W/keystore/thorpipboy.jks -storepass thorpipboy -keypass thorpipboy \
        -alias thorpipboy -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Thor PipBoy, O=harmssi, C=DE"
fi
export THOR_KEYSTORE=$W/keystore/thorpipboy.jks
export GRADLE_USER_HOME=$W/gradle-home
echo "sdk.dir=${ANDROID_HOME:-/opt/android-sdk}" > $W/src/local.properties
cd $W/src
gradle --no-daemon -q assembleRelease
cp app/build/outputs/apk/release/app-release.apk $W/out/ThorPipBoy.apk
ls -la $W/out/ThorPipBoy.apk
