# Build Lumen OS yourself

English | [Русский](ru.md)

If you would rather not install a ready-made image, you can build all of Lumen OS from source on your
own computer and sign it with your own keys. You get the same edition without Google as in the releases.

Before you start:

- The image is signed with your keys. Our over-the-air updates will not install on it: you update it
  with a new build. To go back to our releases, install one with the installer again, with a data wipe.
- Your image will not match ours byte for byte: other signatures, its own build date, and libraries and
  apps built by your compiler. The content and the checks are the same.
- The first LineageOS build takes several hours.

## What you need

- A Linux x86-64 computer (tested on Ubuntu) with 16 GB of RAM and 32 GB of swap (32 GB of RAM is
  better) and about 400 GB of disk: the LineageOS source takes about 150 GB, the build about 200 GB more.
- Docker for the LineageOS build (or the packages listed in `lineage/Dockerfile`).
- `python3`, `openssl`, `rsync`, `unzip`, `debugfs` (e2fsprogs), `dump.erofs` (erofs-utils), and for the
  boot animation `Pillow` and `numpy` (`pip install pillow numpy`).
- [Android NDK r29](https://developer.android.com/ndk/downloads) (29.0.14206865) for the AirPlay receiver.
- Your XGIMI Z9X on firmware V6.15.58 or V6.15.19. As with a normal install, the MediaTek video codec and
  audio files come from it. They are not in this repository or in the image.
- `MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip`, linked in `tools/ota/image/gapps_allow.txt`.
  It is needed only so the base comes out the same as ours. The edition without Google does not keep
  these apps.

## 1. LineageOS source

`lineage/manifests/lumen-lineage-21.xml` lists every LineageOS 21 project at exactly the version our
base was built from.

```sh
mkdir ~/lineage && cd ~/lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-21.0 --git-lfs
cp ~/lumen-os/lineage/manifests/lumen-lineage-21.xml .repo/manifests/
repo init -m lumen-lineage-21.xml
repo sync -c -j8
```

Our patch for the MediaTek codecs:

```sh
cd ~/lineage/frameworks/av
git apply ~/lumen-os/lineage/patches/frameworks_av_C2Store_igba_hidl.patch
```

## 2. Build LineageOS

```sh
cd ~/lineage
source build/envsetup.sh
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
breakfast gsi_tv_arm64
m systemimage apksigner
```

`apksigner` is built from source because the one in `prebuilts` is too old to sign APEX modules.
Ahead-of-time compilation (`WITH_DEXPREOPT`) is required. Without it the first start takes too long and
XGIMI's watchdog restarts the projector. Our `lineage/z9x_build_loop.sh` builds the same way, in the
container from `lineage/Dockerfile`.

The result is `~/lineage/out/target/product/generic_arm64/system.img`.

## 3. Files from your projector

Connect the projector over USB with USB debugging on, as in the [install guide](../install/en.md), and
run from the repository:

```sh
bash installer/lumen-install.sh blobs
```

The installer copies the codec and audio files, checks them against `blobs_allow.txt` and saves them as
`~/Lumen-backup/blobs-<serial>.img`. This works on stock XGIMI firmware and on Lumen OS.

## 4. Build Lumen OS

```sh
cd ~/lumen-os
export LINEAGE=~/lineage ANDROID_NDK=~/android-ndk-r29
tools/selfbuild/build.sh keys
tools/selfbuild/build.sh all ~/lineage/out/target/product/generic_arm64/system.img \
    ~/Downloads/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip ~/Lumen-backup/blobs-<serial>.img
```

- `keys` creates your keys in `~/.lumen-self-keys`. Keep two copies of that folder (a USB stick, a
  password manager). Without the `platform` key, a new build can only be installed with a data wipe.
- `all` runs the base (`base`), the build tree (`tree`), every app from source (`apps`) and the image
  (`image`) in turn. Each step can also run on its own.

The finished files land in `build/selfbuild/release/`: the image, `SHA256SUMS`, its signature
`SHA256SUMS.sig` made with your key, and a copy of the installer that trusts your key.

## 5. Install

From here on, follow the [install guide](../install/en.md), but use the installer from the build folder:

```sh
build/selfbuild/release/installer/lumen-install.sh --image build/selfbuild/release/lumen-os-1.0.2-nogms-system.img
```

## How this was checked

- From the same LineageOS image, `tools/selfbuild/make_base.sh` rebuilds our base exactly: all 6560 files
  match in content, permissions and SELinux labels. Only three remote key layouts differ, and the build
  replaces those with its own anyway.
- The whole build was run on Linux from a clean copy of the repository with other keys. The image has
  exactly the same 6444 files as our 1.0.2 release, and 6326 of them match ours byte for byte. The other
  118 differ only because of the keys: app and APEX signatures, their precompiled code, the update
  certificate, the SELinux rules that name the platform certificate, and the build number in build.prop.
- The image build runs the same checks as our releases: no MediaTek or XGIMI files, no Google apps, every
  app and APEX module signed with your keys, every file in the image identical to what was built. The one
  difference: instead of the checksums of our own builds (`libcodec2_vndk`, TvInput, AirPlay), yours are
  checked.

If something does not build, open an [issue](https://github.com/kmuradoff/lumen-os/issues) with the
command output.
