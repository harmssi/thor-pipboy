#!/bin/bash
# Baut ThorPipBoy.dll ohne Windows: Quellen per rsync auf den Build-Host,
# dort clang-cl + lld-link + xwin im Docker-Image fo4-xbuild (docker/Dockerfile).
# Host kommt aus ~/.config/thorconquer/env (THOR_BUILD_HOST).
set -euo pipefail
cd "$(dirname "$0")"
[ -f ~/.config/thorconquer/env ] && . ~/.config/thorconquer/env
HOST=${THOR_BUILD_HOST:?THOR_BUILD_HOST fehlt}
REMOTE=/root/thor-pipboy-build/plugin
CFG=${CFG:-Release}
ssh "$HOST" "docker image inspect fo4-xbuild >/dev/null 2>&1" || {
  ssh "$HOST" "mkdir -p /root/thor-pipboy-build/docker"
  scp -q docker/Dockerfile "$HOST:/root/thor-pipboy-build/docker/"
  ssh "$HOST" "cd /root/thor-pipboy-build/docker && docker build -t fo4-xbuild ."
}
rsync -a --delete --exclude .git --exclude build --exclude out ./ "$HOST:$REMOTE/"
ssh "$HOST" "docker run --rm -v $REMOTE:/src -w /src fo4-xbuild bash -c '
  rm -f build/ThorPipBoy.dll; cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=$CFG -DCMAKE_TOOLCHAIN_FILE=cmake/clang-cl-xwin.cmake >/dev/null &&
  cmake --build build -j4 2>&1 | grep -v \"^\\[\" | grep -E -B2 -A6 \"error|Error|FAILED\" | head -150;
  test -f build/ThorPipBoy.dll'"
mkdir -p out
scp -q "$HOST:$REMOTE/build/ThorPipBoy.dll" out/
ls -l out/ThorPipBoy.dll
