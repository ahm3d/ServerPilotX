#!/usr/bin/env bash
# Builds ServerPilotX as a native Linux app with a bundled Java runtime.
#   ./package.sh            -> app-image + .deb (if dpkg-deb is available)
#   ./package.sh image      -> app-image only (target/dist/ServerPilotX)
#   ./package.sh rpm        -> app-image + .rpm (needs rpmbuild)
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.0.0
NAME=ServerPilotX
DEST=target/dist
MODE="${1:-deb}"

echo "==> Building with Maven"
mvn -q -B package -DskipTests

echo "==> Preparing module path"
MP="target/lib:target/serverpilotx.jar"

COMMON=(
  --name "$NAME"
  --app-version "$VERSION"
  --vendor "ServerPilotX"
  --description "Self-hosted Linux server manager: Docker, Nginx, SSL, firewall, SSH and metrics"
  --copyright "ServerPilotX contributors — AGPL-3.0"
  --module-path "$MP"
  --module io.serverpilotx/io.serverpilotx.App
  --add-modules jdk.crypto.ec,jdk.localedata
  --icon packaging/serverpilotx.png
  --java-options "-Dprism.lcdtext=false"
  --java-options "-Xss2m"
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6"
  --dest "$DEST"
)

rm -rf "$DEST"
mkdir -p "$DEST"

echo "==> jpackage app-image"
jpackage --type app-image "${COMMON[@]}"

if [[ "$MODE" == "deb" ]] && command -v dpkg-deb >/dev/null; then
  echo "==> jpackage .deb"
  jpackage --type deb "${COMMON[@]}" \
    --resource-dir packaging/linux \
    --license-file LICENSE \
    --linux-package-name serverpilotx \
    --linux-app-category admin \
    --linux-menu-group "System;Network;" \
    --linux-shortcut \
    --linux-deb-maintainer "naamna7@gmail.com" \
    --linux-package-deps "openssh-client"
fi

if [[ "$MODE" == "rpm" ]] && command -v rpmbuild >/dev/null; then
  echo "==> jpackage .rpm"
  jpackage --type rpm "${COMMON[@]}" \
    --resource-dir packaging/linux \
    --license-file LICENSE \
    --linux-package-name serverpilotx \
    --linux-app-category "Applications/System" \
    --linux-menu-group "System;Network;" \
    --linux-shortcut \
    --linux-package-deps "openssh-clients"
fi

echo
echo "Done:"
ls -1 "$DEST"
