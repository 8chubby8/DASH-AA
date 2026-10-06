#!/usr/bin/env bash
# DASH-AA — one-time setup on the machine that runs it.
#
#   packaging/install.sh            udev rule (asks for sudo) + app menu entry
#   packaging/install.sh --autostart   … and start DASH-AA when you log in
#
# Builds the self-contained app first if it is not built. Nothing here needs root except the udev rule,
# and that is the only thing sudo is asked for.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="$ROOT/app/build/compose/binaries/main/app/dash-aa/bin/dash-aa"

if [[ ! -x "$APP" ]]; then
  echo "Building DASH-AA…"
  ./gradlew --console=plain -q createDistributable
fi

echo "Installing the udev rule (phone access for Android Auto; ModemManager kept off modules)…"
sudo install -m 0644 packaging/60-dash-aa.rules /etc/udev/rules.d/60-dash-aa.rules
sudo udevadm control --reload
sudo udevadm trigger --subsystem-match=usb --subsystem-match=tty

for g in uucp dialout; do
  if getent group "$g" >/dev/null && ! id -nG | grep -qw "$g"; then
    echo "Note: you are not in the '$g' group, so USB serial modules will be refused."
    echo "      Fix with:  sudo usermod -aG $g $USER   (then log out and in)"
  fi
done

mkdir -p ~/.local/share/applications
sed "s#@APP@#$APP#g" packaging/dash-aa.desktop > ~/.local/share/applications/dash-aa.desktop
echo "Added DASH-AA to the app menu."

if [[ "${1:-}" == "--autostart" ]]; then
  mkdir -p ~/.config/autostart
  cp ~/.local/share/applications/dash-aa.desktop ~/.config/autostart/dash-aa.desktop
  echo "DASH-AA will start when you log in."
fi

echo "Done. Unplug and replug the phone once so the new rule applies to it."
