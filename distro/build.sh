#!/usr/bin/env bash
# DASH-AA Linux — build the system image, which is also the installer USB (roadmap 1.2.1).
#
#   distro/build.sh      →  distro/out/dash_<version>.raw     write it to a USB stick (README.md)
#                           distro/out/dash_<version>.efi     the release's UKI    } an update is these
#                           distro/out/dash_<version>.root…   its system partition } two (stage 2)
#
# Built with mkosi (systemd's image builder) as the ordinary user: no root, no password. mkosi fetches its
# own tools into a sandbox the first time (distro/work/mkosi, its source, is fetched here too).
#
# The SSH key allowed in: ~/.ssh/dash_ed25519.pub, or DASH_SSH_KEY=<file>. It goes on the USB's own data
# partition and from there onto the installed machine — never into the system image, which is public.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
D=$REPO/distro
W=$D/work
APP=$REPO/app/build/compose/binaries/main/app/dash
MKOSI=$W/mkosi/bin/mkosi
KEY=${DASH_SSH_KEY:-$HOME/.ssh/dash_ed25519.pub}
# The Java bundled into DASH: a generic one (Eclipse Temurin 17), fetched once into distro/work/jdk. Never the
# build computer's own — the G14's is CachyOS's Zen 4 build, and DASH would not start on an older processor
# (the Surface Pro 3: "CPU ISA level is lower than required", 2026-10-22).
JDK=$(ls -d "$W"/jdk/jdk-17*/ 2>/dev/null | head -1)

[ -x "$MKOSI" ] || git clone -q --depth 1 -b v26 https://github.com/systemd/mkosi.git "$W/mkosi"

VERSION=$(grep -m1 -oP '^## Version \K[0-9]+\.[0-9]+\.[0-9]+' changelog.md)
# A second build of the same version, to update a machine that has the first: DASH_IMAGE_VERSION=1.2.1.1.
IMAGE_VERSION=${DASH_IMAGE_VERSION:-$VERSION}
echo "==> DASH $VERSION, system $IMAGE_VERSION"

if [ -z "$JDK" ]; then
    echo "==> Fetching a generic Java (Eclipse Temurin 17)"
    mkdir -p "$W/jdk"
    url=$(curl -sS --retry 5 -o /dev/null -w '%{redirect_url}' "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk")
    for _ in $(seq 1 30); do curl -fsSL -C - --connect-timeout 30 -o "$W/jdk/jdk17.tar.gz" "$url" && break; sleep 3; done
    tar -C "$W/jdk" -xzf "$W/jdk/jdk17.tar.gz"
    JDK=$(ls -d "$W"/jdk/jdk-17*/ | head -1)
fi
rm -rf "$REPO/app/build/compose"
DASH_JAVA_HOME=${JDK%/} ./gradlew --console=plain -q createDistributable
[ -x "$APP/bin/dash" ] || { echo "DASH did not build."; exit 1; }
# Every program in DASH must run on any 64-bit PC: refuse a build that asks for more than the baseline's v2.
if find "$APP" -type f \( -name '*.so' -o -path '*/bin/*' \) -exec readelf -n {} \; 2>/dev/null | grep -q 'x86 ISA needed:.*x86-64-v[34]'; then
    echo "DASH was built for a newer processor than every PC has (x86-64-v3/v4) — check the Java in $JDK."; exit 1
fi

# This release's system slot: a fresh partition UUID, which its UKI finds it by.
ROOTUUID=$(cat /proc/sys/kernel/random/uuid)
rm -rf "$W/repart" "$W/trees"
mkdir -p "$W/repart" "$W/trees/files/etc/udev/rules.d" "$W/trees/usb/dash-usb" "$W/cache" "$W/pkg/cache/pacman/pkg"
for f in "$D"/repart/*.conf; do sed "s/@ROOTUUID@/$ROOTUUID/" "$f" >"$W/repart/$(basename "$f")"; done
cp "$REPO/packaging/60-dash.rules" "$W/trees/files/etc/udev/rules.d/"
install -m 755 "$D/usb/update" "$W/trees/usb/dash-usb/update"
if [ -s "$KEY" ]; then
    cp "$KEY" "$W/trees/usb/dash-usb/authorized_keys"
    echo "==> SSH key allowed in: $KEY"
else
    echo "==> No SSH key ($KEY): nobody will be able to log in from another computer."
fi

cd "$D"
"$MKOSI" --image-version="$IMAGE_VERSION" \
    --repart-directory="$W/repart" \
    --extra-tree="$APP:/opt/dash" \
    --extra-tree="$W/trees/files" \
    --extra-tree="$W/trees/usb" \
    --kernel-command-line="root=PARTUUID=$ROOTUUID" \
    --cache-directory="$W/cache" --package-cache-dir="$W/pkg" \
    -f build
echo
echo "==> Done: $(ls "$D"/out/dash_"$IMAGE_VERSION".raw)"
