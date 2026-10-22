# DASH-AA Linux

A small Linux, built on Arch, whose only job is DASH. Power on and DASH is the screen: no desktop, no
login prompt, no password. Roadmap 1.2.x. The first machine it is for is a Surface Pro 3, and it should
run on any UEFI PC.

## How it is put together

**The disk:**

| Partition | What it holds |
|---|---|
| ESP (1 GiB) | systemd-boot, and one kernel image (UKI) per system slot |
| Slot A (8 GiB) | the whole system, **read-only** — labelled with its version, `dash_1.2.1` |
| Slot B (8 GiB) | the spare slot: the next update goes here, and the old one stays to roll back to |
| Data (the rest) | everything that is the user's: `/home`, `/var`, and every change to `/etc` — the time zone, Wi-Fi, Bluetooth pairings, the machine's SSH identity. An update or roll back never touches it. |

**Frozen.** Every package is as the Arch Linux Archive had it on one day (`Snapshot=` in `mkosi.conf`).
A release is a DASH version plus that date, tested together. Nothing updates itself.

**Three tries.** A new release has three starts to get DASH on the screen (`dash-up.service`). If it
does not, the machine starts the other slot instead — systemd-boot's boot counting, the UKI named
`dash_<version>+3.efi`.

**No passwords.** greetd logs `dash` in at power-on and starts labwc with DASH in it (`dash-session`,
`dash-keeper`, which restarts DASH if it ever closes). `sudo` asks for nothing. From another computer, by
key only. Nothing of DASH runs as root.

**Claude Code** installs itself natively, with Anthropic's own installer, into `~/.local` the first time
the machine has internet (`claude-code-install.service`). It is not in the image — it is not ours to pass
on — and it lives on the data partition, so updates never remove it. Run `claude` in Developer › Terminal
and log in once.

## Build it (on the G14)

```
distro/build.sh
```

No root and no password: mkosi, systemd's image builder, does it all as you, fetching its own tools the
first time (into `distro/work`). The result in `distro/out/`:

- `dash_<version>.raw` — the whole disk image. Written to a USB stick, it is the installer.
- `dash_<version>.efi` and the system partition beside it — what an update will be (stage 2).

The version is the changelog's; `DASH_IMAGE_VERSION=1.2.1.4 distro/build.sh` gives a test build its own
(an update needs a version different from the one installed). The build refuses a Java runtime or launcher
that needs a newer processor than plain x86-64 (`x86-64-v3`/`v4`): the Surface's i5 cannot run them.

The SSH key allowed in is `~/.ssh/dash_ed25519.pub` (or `DASH_SSH_KEY=<file>`). It goes onto the USB's
own data partition and from there onto the machine — never into the system image itself.

## Try it in a virtual machine first

```
distro/test-vm.sh install   # the image as a USB stick, and a blank 64 GB disk
distro/test-vm.sh run       # start what it installed
distro/test-vm.sh update    # start what it installed, with the newest image plugged in as a USB stick
distro/test-vm.sh fresh     # wipe the pretend disk
```

While it runs, `ssh -p 2222 dash@localhost -i ~/.ssh/dash_ed25519` gets in.

## Put it on the Surface

1. Write the image to a USB stick. **This erases the stick.** Find its name with `lsblk` (for example
   `/dev/sdb`), then:
   `sudo dd if=distro/out/dash_<version>.raw of=/dev/sdX bs=4M status=progress oflag=sync`
2. On the Surface, turn Secure Boot off once: hold **Volume Up** and press **Power** to open the Surface
   UEFI menu, then Security › Secure Boot › Disabled. From then on there is a red bar at power-on; it is
   only cosmetic.
3. Plug in the USB stick and a keyboard (the Type Cover works). Hold **Volume Down** and press **Power**.
4. The installer starts by itself. Choose the disk and type `erase`. It needs no internet.
5. Take the USB out and restart. DASH comes up.

## Update from a USB stick

Until System › Updates (stage 2), a newer release goes on from a USB stick holding its image, without
erasing anything: it goes into the spare slot, the one running now stays to fall back to, and everything
of yours on the data partition is kept.

1. Write the new image to the stick, as above.
2. With DASH running, plug the stick in, press **Ctrl+Alt+F2** and log in as `dash` (or `ssh dash`).
   Do not start the machine *from* the stick: that is the installer.
3. `lsblk` — the stick is the disk with `dash-data` that is not the machine's own (for example `sdb`).
4. `sudo mount /dev/sdb3 /mnt && sudo /mnt/usb/update`, and type `update`.
5. Take the stick out, then `sudo reboot`. The new release has three tries; if it fails all three, the
   machine goes back to the old one by itself.

## Getting in

- **From the G14:** `ssh dash` (the `Host dash` entry in `~/.ssh/config`: `dash@dash.local`, the DASH key).
- **On the machine:** Developer › Terminal. `sudo` works, with no password. The system is read-only:
  `pacman` cannot install anything into it, by design. Your own programs go in your home folder.
- **Text console:** Ctrl+Alt+F2, log in as `dash`.

## Files

| | |
|---|---|
| `mkosi.conf` | the packages, the snapshot date, the kernel and boot settings |
| `repart/` | the partitions of the image (the build fills in the slot's UUID) |
| `mkosi.extra/` | DASH-AA Linux's own files, laid over the packages |
| `mkosi.postinst.chroot` | the user, the services — run inside the system as it is built |
| `initrd/`, `mkosi.finalize` | the piece of the initrd that puts the data partition in place |
| `mkosi.sandbox/` | the linux-surface package repository and its signing key, for the build |
| `surface/` | the linux-surface signing key as published, from which the keyring was made |
| `usb/update` | the update from a USB stick, put on the stick's data partition |
| `test-vm.sh` | the image in QEMU on the G14 |
