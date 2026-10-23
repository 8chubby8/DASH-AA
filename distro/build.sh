#!/usr/bin/env bash
# DASH-AA Linux — build the system image, which is also the installer USB (roadmap 1.2.1).
#
#   distro/build.sh      →  distro/out/dash_<version>.raw     write it to a USB stick (README.md): it installs,
#                                                             and it carries the update file as well
#                           distro/out/dash_<version>.update  the release, sealed: what System › Updates installs
#
# The seal: every release is signed with the release key, which lives only on the computer that builds
# (~/.config/dash-release/release.pem, or DASH_RELEASE_KEY=<file>; made the first time). Its public half is
# built into the system, and a machine installs only what that key sealed (Roger, 2026-10-21).
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
RELEASE_KEY=${DASH_RELEASE_KEY:-$HOME/.config/dash-release/release.pem}

[ -x "$MKOSI" ] || git clone -q --depth 1 -b v26 https://github.com/systemd/mkosi.git "$W/mkosi"

VERSION=$(grep -m1 -oP '^## Version \K[0-9]+\.[0-9]+\.[0-9]+[a-z]?(?=\s*$)' changelog.md)
# The version is the changelog's: three numbers (Roger, 2026-10-22), and a letter for a test build of it (1.2.3a,
# Roger 2026-10-23). Each build is also told
# apart by when it was made, so a second build of the same version can still update a machine that has the
# first: the release is <version>-<yyyymmddHHMM> (dash_1.2.2-202610222310), shown as "1.2.2, built 22 Oct 23:10".
IMAGE_VERSION=$VERSION-$(date +%Y%m%d%H%M)
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
if [ ! -s "$RELEASE_KEY" ]; then
    echo "==> Making the release key: $RELEASE_KEY (keep it — releases sealed with another are refused)"
    mkdir -p "$(dirname "$RELEASE_KEY")"; chmod 700 "$(dirname "$RELEASE_KEY")"
    (umask 077; openssl genpkey -algorithm ed25519 -out "$RELEASE_KEY")
fi
mkdir -p "$W/trees/files/usr/lib/dash"
openssl pkey -in "$RELEASE_KEY" -pubout -out "$W/trees/files/usr/lib/dash/release.pub"
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

# --- The update file ------------------------------------------------------------------------------------
# dash_<version>.update: a plain tar of release.txt (what is in it, each part's checksum), release.sig (the
# seal on release.txt), the kernel images, and the system, packed. Read by DASH (System › Updates) and by
# /usr/lib/dash/dash-update, which installs it. In that order, so the first two are found without reading on.
echo "==> Sealing the update file"
OUT=$D/out
R=$W/release
rm -rf "$R"; mkdir -p "$R"
release=$R/release.txt
{
    echo "version=$IMAGE_VERSION"
    echo "dash=$VERSION"
    echo "date=$(date +%F)"
    echo "uuid=$ROOTUUID"
    # A release that changes how DASH keeps its settings says so (DASH_SETTINGS_CHANGE=yes): going back to an
    # older one then means going back to the settings from before too.
    echo "settings=$([ "${DASH_SETTINGS_CHANGE:-}" = yes ] && echo changed || echo same)"
    # What is new, a line each (distro/summary.txt).
    if [ -s "$D/summary.txt" ]; then sed 's/^/summary=/' "$D/summary.txt"; fi
} >"$release"
for uki in $(mdir -b -i "$OUT/dash_$IMAGE_VERSION.esp.raw" ::/EFI/Linux | grep -o "dash_${IMAGE_VERSION}_[^/]*\.efi$"); do
    name=$(echo "$uki" | sed -E 's/\+[0-9]+(-[0-9]+)?\.efi$/.efi/')
    mcopy -n -i "$OUT/dash_$IMAGE_VERSION.esp.raw" "::/EFI/Linux/$uki" "$R/$name"
    kind=generic; [[ "$name" == *-surface.efi ]] && kind=surface
    echo "kernel=$name $(sha256sum <"$R/$name" | cut -c1-64) $kind" >>"$release"
done
grep -q '^kernel=' "$release" || { echo "No kernel images found in the boot partition."; exit 1; }
zstd -q -T0 -12 -f "$OUT/dash_$IMAGE_VERSION.root-x86-64.raw" -o "$R/system.zst"
echo "system=system.zst $(sha256sum <"$R/system.zst" | cut -c1-64) $(stat -c %s "$R/system.zst") $(stat -c %s "$OUT/dash_$IMAGE_VERSION.root-x86-64.raw")" >>"$release"
openssl pkeyutl -sign -inkey "$RELEASE_KEY" -rawin -in "$release" -out "$R/release.sig"
( cd "$R" && tar --format=gnu -cf "$OUT/dash_$IMAGE_VERSION.update" release.txt release.sig $(ls *.efi) system.zst )

# --- The stick carries it too ---------------------------------------------------------------------------
# One stick both installs and updates: its data partition (the last) is made again, big enough for the update
# file beside what the installer hands on, and System › Updates finds it there on a machine already installed.
echo "==> Putting the update file on the stick image"
raw=$OUT/dash_$IMAGE_VERSION.raw
stick=$W/stick
rm -rf "$stick"; mkdir -p "$stick/usb"
cp -a "$W/trees/usb/dash-usb/." "$stick/usb/"
cp "$OUT/dash_$IMAGE_VERSION.update" "$stick/usb/"
read -r start <<<"$(sfdisk -J "$raw" | python3 -c 'import json,sys; print(json.load(sys.stdin)["partitiontable"]["partitions"][2]["start"])')"
size=$(( ($(du -sb "$stick" | cut -f1) * 11 / 10 + 64 * 1024 * 1024) / 1048576 * 1048576 ))
rm -f "$W/stick.img"
mke2fs -q -t ext4 -L dash-data -d "$stick" -E root_owner=0:0 "$W/stick.img" "$((size / 1024))k"
truncate -s $(( start * 512 + size + 1048576 )) "$raw"
dd if="$W/stick.img" of="$raw" bs=4M seek=$((start * 512)) oflag=seek_bytes conv=notrunc,sparse status=none
sfdisk -q --relocate gpt-bak-std "$raw"
echo ", $((size / 512))" | sfdisk -q --no-reread --no-tell-kernel -N 3 "$raw"
rm -f "$W/stick.img"

echo
echo "==> Done: $(ls "$OUT"/dash_"$IMAGE_VERSION".raw) ($(du -h --apparent-size "$raw" | cut -f1)), and $(ls "$OUT"/dash_"$IMAGE_VERSION".update) ($(du -h "$OUT/dash_$IMAGE_VERSION.update" | cut -f1))"
