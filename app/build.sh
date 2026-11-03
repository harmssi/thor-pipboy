#!/bin/bash
# Thor Pip-Boy bauen: Quellen zum Build-Host syncen, im Docker-Image "thor-build" bauen,
# APK nach dist/ThorPipBoy.apk holen. Optional: --install (adb install -r auf den Thor).
set -euo pipefail
cd "$(dirname "$0")"
[ -f ~/.config/thorconquer/env ] && . ~/.config/thorconquer/env
HOST=${THOR_BUILD_HOST:?set THOR_BUILD_HOST=user@linux-host-with-docker}
REMOTE=/root/thor-pipboy-build/app
ssh "$HOST" "mkdir -p $REMOTE/src"
rsync -a --delete --exclude .git --exclude build --exclude app/build --exclude .gradle --exclude dist \
    --exclude local.properties ./ "$HOST:$REMOTE/src/"
ssh "$HOST" "docker run --rm -v $REMOTE:/work thor-build bash /work/src/docker-build.sh"
mkdir -p dist
scp -q "$HOST:$REMOTE/out/ThorPipBoy.apk" dist/ThorPipBoy.apk
echo "APK: $PWD/dist/ThorPipBoy.apk"
if [ "${1:-}" = "--install" ]; then adb install -r dist/ThorPipBoy.apk; fi
