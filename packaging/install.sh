#!/usr/bin/env bash
# DASH — one-time setup on the machine that runs it.
#
#   packaging/install.sh            udev and polkit rules (asks for sudo) + app menu entry
#   packaging/install.sh --autostart   … and start DASH when you log in
#
# Builds the self-contained app first if it is not built. Nothing here needs root except the two rules,
# and they are the only things sudo is asked for.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="$ROOT/app/build/compose/binaries/main/app/dash/bin/dash"

if [[ ! -x "$APP" ]]; then
  echo "Building DASH…"
  ./gradlew --console=plain -q createDistributable
fi

echo "Installing the udev rule (phone access for Android Auto; ModemManager kept off modules)…"
sudo install -m 0644 packaging/60-dash.rules /etc/udev/rules.d/60-dash.rules
sudo rm -f /etc/udev/rules.d/60-dash-aa.rules   # its name before 1.1.9
sudo udevadm control --reload
sudo udevadm trigger --subsystem-match=usb --subsystem-match=tty

# polkit reads its rules again by itself; nothing to restart.
echo "Installing the polkit rule (DASH sets the clock and the time zone without asking for a password)…"
sudo install -m 0644 packaging/50-dash.rules /etc/polkit-1/rules.d/50-dash.rules
sudo rm -f /etc/polkit-1/rules.d/50-dash-aa.rules   # its name before 1.1.9

for g in uucp dialout; do
  if getent group "$g" >/dev/null && ! id -nG | grep -qw "$g"; then
    echo "Note: you are not in the '$g' group, so USB serial modules will be refused."
    echo "      Fix with:  sudo usermod -aG $g $USER   (then log out and in)"
  fi
done

mkdir -p ~/.local/share/applications
rm -f ~/.local/share/applications/dash-aa.desktop   # the menu entry's name before 1.1.9
sed "s#@APP@#$APP#g" packaging/dash.desktop > ~/.local/share/applications/dash.desktop
echo "Added DASH to the app menu."

if [[ -e ~/.config/autostart/dash-aa.desktop ]]; then
  rm -f ~/.config/autostart/dash-aa.desktop
  set -- --autostart   # it started at login under its old name, so it still does
fi
if [[ "${1:-}" == "--autostart" ]]; then
  mkdir -p ~/.config/autostart
  cp ~/.local/share/applications/dash.desktop ~/.config/autostart/dash.desktop
  echo "DASH will start when you log in."
fi

echo "Done. Unplug and replug the phone once so the new rule applies to it."
