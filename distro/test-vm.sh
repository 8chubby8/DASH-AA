#!/usr/bin/env bash
# DASH-AA Linux — try it in a virtual machine on this computer before the real one (roadmap 1.2.1).
#
#   distro/test-vm.sh install   the newest image in distro/out as a USB stick, and a blank 64 GB disk
#   distro/test-vm.sh run       start what was installed on that disk
#   distro/test-vm.sh update    start it with the newest image plugged in as a stick, to update from (usb/update)
#   distro/test-vm.sh fresh     wipe the pretend disk and the pretend firmware's memory
#
# No root needed. HEADLESS=1 runs it with no window, a monitor socket at work/vm/monitor (screendump,
# sendkey), a QMP socket at work/vm/qmp (touches: input-send-event) and the serial console in
# work/vm/serial.log — how Claude drives it.
set -euo pipefail
cd "$(dirname "$0")"
VM=work/vm
mkdir -p "$VM"
FW=/usr/share/edk2/x64/OVMF_CODE.4m.fd
[ -f "$VM/vars.fd" ] || cp /usr/share/edk2/x64/OVMF_VARS.4m.fd "$VM/vars.fd"
[ -f "$VM/disk.qcow2" ] || qemu-img create -q -f qcow2 "$VM/disk.qcow2" 64G
args=(-enable-kvm -cpu host -smp 4 -m 4G -machine q35
      -drive if=pflash,format=raw,readonly=on,file="$FW" -drive if=pflash,format=raw,file="$VM/vars.fd"
      -drive file="$VM/disk.qcow2",if=none,id=disk -device nvme,drive=disk,serial=dash-disk,bootindex=1
      -device qemu-xhci,id=xhci -device usb-tablet -device usb-kbd
      -nic user,model=virtio-net-pci,hostfwd=tcp:127.0.0.1:2222-:22
      -audiodev pipewire,id=snd -device intel-hda -device hda-duplex,audiodev=snd
      -serial file:"$VM/serial.log")
if [ -n "${HEADLESS:-}" ]; then
    args+=(-device virtio-vga -display none -monitor unix:"$VM/monitor",server,nowait -qmp unix:"$VM/qmp",server,nowait)
else
    args+=(-device virtio-vga-gl -display gtk,gl=on)
fi
case "${1:-}" in
    install) img=$(ls -t out/dash_*.raw | grep -E "/dash_[0-9.]+(-[0-9]+)?\.raw$" | head -1); echo "USB stick: $img"
             cp --reflink=auto "$img" "$VM/usb.raw"
             exec qemu-system-x86_64 "${args[@]}" \
                 -drive file="$VM/usb.raw",if=none,id=usb,format=raw -device usb-storage,bus=xhci.0,drive=usb,removable=on,bootindex=0 ;;
    run)     exec qemu-system-x86_64 "${args[@]}" ;;
    update)  img=$(ls -t out/dash_*.raw | grep -E "/dash_[0-9.]+(-[0-9]+)?\.raw$" | head -1); echo "USB stick (to update from): $img"
             cp --reflink=auto "$img" "$VM/usb.raw"
             exec qemu-system-x86_64 "${args[@]}" \
                 -drive file="$VM/usb.raw",if=none,id=usb,format=raw -device usb-storage,bus=xhci.0,drive=usb,removable=on ;;
    fresh)   rm -f "$VM/disk.qcow2" "$VM/vars.fd" "$VM/usb.raw"; echo "Pretend disk wiped." ;;
    *)       echo "usage: $0 install | run | update | fresh"; exit 1 ;;
esac
