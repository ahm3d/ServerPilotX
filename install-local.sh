#!/usr/bin/env bash
# Installs the app-image for the current user (no root): ~/.local/opt + menu entry + icon.
set -euo pipefail
cd "$(dirname "$0")"
IMG=target/dist/ServerPilotX
[[ -d "$IMG" ]] || ./package.sh image

OPT="$HOME/.local/opt/ServerPilotX"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/512x512/apps"
BIN="$HOME/.local/bin"

rm -rf "$OPT"
mkdir -p "$(dirname "$OPT")" "$APPS" "$ICONS" "$BIN"
cp -r "$IMG" "$OPT"
cp packaging/serverpilotx.png "$ICONS/serverpilotx.png"
ln -sf "$OPT/bin/ServerPilotX" "$BIN/serverpilotx"

cat > "$APPS/serverpilotx.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=ServerPilotX
GenericName=Linux Server Manager
Comment=Manage Docker, Nginx, SSL, firewall, SSH and server metrics
Exec=$OPT/bin/ServerPilotX
Icon=serverpilotx
Terminal=false
Categories=System;Network;Monitor;
Keywords=server;docker;nginx;ssl;firewall;ssh;admin;
StartupWMClass=io.serverpilotx.App
DESKTOP

command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" >/dev/null 2>&1 || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" >/dev/null 2>&1 || true
echo "Installed. Launch “ServerPilotX” from your app menu or run: serverpilotx"
