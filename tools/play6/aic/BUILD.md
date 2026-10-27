# AIC8800D80X2 USB modules for XGIMI Play 6, stock firmware V1.7.116

Target kernel: `Linux 4.19.116+`, vermagic `4.19.116+ SMP preempt mod_unload modversions ARMv7`
(32-bit ARM, MStar/MediaTek MT5889, CONFIG_MODVERSIONS=y, no module signing).
Chip: AICSemi AIC8800D80X2, USB 368b:8d90 (ROM, before firmware) then 368b:8d91 (after firmware).

Device test 2026-10-28 on a stock V1.7.116 Play 6 with this chip (manual insmod as root, nothing written):
the chip took the firmware and came back as 368b:8d91, wlan0, p2p0 and /dev/aicbt_dev appeared, and Android
Wi-Fi worked with the stock MediaTek Wi-Fi HAL and wpa_supplicant (svc wifi enable, 29 networks in
`cmd wifi list-scan-results`). Bluetooth is not wired to Android yet. Lumen loads these modules through
overlay/play6/play6_aic.rc + play6_aic.sh, only when the USB chip is 368b:8d90 or 368b:8d91.

## Where the files are

The binaries (modules, firmware) and the rebuilt stock export table are not in the repository: the build
keeps them in `build/play6/aic/` (`modules/`, `firmware/`, `build-inputs/Module.symvers.play6-1.7.116`);
`SHA256SUMS` here pins the modules and firmware that tools/play6/compose.sh accepts. This folder holds
the recipe: the two patches, the MT5889 machine headers, `ksyms.py`, `kocheck.py` and `loadcheck.txt`.

## Contents

- `modules/aic_load_fw.ko`, `modules/aic8800_fdrv.ko`, `modules/aic_btusb.ko` (debug info stripped)
- `firmware/` the full SDK D80X2 set: u03 + u05 BT patch/adid/patch_table (+ u05 ext0/ext1),
  `fmacfw_8800d80x2.bin`, `lmacfw_rf_8800d80x2.bin`, `aic_userconfig_8800d80x2.txt`,
  `aic_powerlimit_8800d80x2.txt`, `aicbt.conf`
- `SHA256SUMS`
- `build-inputs/` everything needed to repeat the build and the check:
  - `aic8800-play6.patch` driver changes (Makefiles + one source fix)
  - `kernel-arm-Makefile.patch` change to kernel.org `arch/arm/Makefile`
  - `mach-mt5889/include/{mach,mstar}` MT5889 machine headers (from TCLOpenSource/mt9221)
  - `Module.symvers.play6-1.7.116` rebuilt stock export table (vmlinux + cfg80211 + firmware_class)
  - `ksyms.py` (Module.symvers from the boot.img kernel), `kocheck.py` (CRC/vermagic checker)
  - `loadcheck.txt` output of the final check

## Sources

- Driver: https://github.com/radxa-pkg/aic8800 commit `fce88024ed40279def034e203128e0892fdbcac3`
  (2026-10-20), which carries AIC SDK `aic8800d_linux_sdk_V5.0_2026_0123_5f7be68d`.
  Path used: `src/USB/driver_fw/drivers/{aic8800/aic_load_fw,aic8800/aic8800_fdrv,aic_btusb}`.
  radxa `debian/patches` NOT applied.
- Kernel: kernel.org `linux-4.19.116.tar.xz`, sha256
  `8ee65d23ec23abb4ae195ff354a15da7d6d20ed6dcecc40db02bd06b076b7b64`.
- Device headers: `kheaders.ko` of the stock firmware (include/, arch/arm/include/, generated
  autoconf.h, bounds.h, asm-offsets.h, utsrelease.h). 252 headers really differ from kernel.org
  (sk_buff, net_device, cfg80211, usb, sched, mm, module ...), so the full set was overlaid.
- Device config: `.config` extracted from the stock boot.img; `include/config/auto.conf` was
  written from it (it has CONFIG_ARCH_MSTAR / CONFIG_PLAT_MSTAR / CONFIG_MP_* that kernel.org
  Kconfig drops).
- `CONFIG_NEED_MACH_MEMORY_H=y` and `CONFIG_PLAT_MSTAR=y`: `asm/memory.h` pulls `<mach/memory.h>`,
  which is not in kheaders. Taken from TCLOpenSource/mt9221 (TCL TV kernel, also 4.19.116, MStar
  tree) commit `098aa173622fa59a5531b9b01d8f9fc3b0e539f8`, path
  `drivers/mstar2/hal/mt5889/cpu/arm/include/{mach,mstar}`. Verified: the virt_to_phys code it
  generates in aic8800_fdrv.ko is instruction for instruction the same pattern as in the stock
  cfg80211.ko (same 4 imports lx_mem_left_size, lx_mem2_addr, lx_mem2_left_size, lx_mem3_addr,
  same constants 0xC0000000, 0x1FFFFF, 0x600000, 0x60200000).

## Toolchain

Docker `ubuntu:22.04` under colima: `clang-11` 1:11.1.0-6 (Ubuntu clang 11.1.0) +
`binutils-arm-linux-gnueabihf` 2.38. The stock kernel was built with Android clang 11.0.1
(r383902); clang 11 was chosen to keep CONFIG_CC_IS_CLANG and the clang code paths/flags of the
device build (a gcc build would flip CC_IS_GCC, change compiler-*.h paths and ABI flags).
`-meabi gnu` added for clang as in the vendor arch/arm/Makefile.

## Commands (inside the container)

```
cd /b/linux-4.19.116
cp <play6_1.7.116.config> .config
export ARCH=arm CROSS_COMPILE=arm-linux-gnueabihf-
make CC=clang-11 HOSTCC=gcc LOCALVERSION=+ olddefconfig
make CC=clang-11 HOSTCC=gcc LOCALVERSION=+ modules_prepare     # kernel.release = 4.19.116+
cp -a <kheaders>/include/. include/ ; cp -a <kheaders>/arch/arm/include/. arch/arm/include/
mkdir -p arch/arm/mach-mt5889/include && cp -a mach mstar arch/arm/mach-mt5889/include/
grep -E '^CONFIG_' <play6_1.7.116.config> > include/config/auto.conf
patch arch/arm/Makefile < kernel-arm-Makefile.patch   # machine-$(CONFIG_MSTAR_MT5889) += mt5889, -meabi gnu
cp Module.symvers.play6-1.7.116 Module.symvers
# drivers: copy src/USB/driver_fw/drivers to /b/drv, then (cd /b/drv && patch -p1 < aic8800-play6.patch)
make -C /b/linux-4.19.116 M=/b/drv/aic8800 CC=clang-11 HOSTCC=gcc modules   # aic_load_fw + aic8800_fdrv
make -C /b/linux-4.19.116 M=/b/drv/aic_btusb CC=clang-11 HOSTCC=gcc modules
arm-linux-gnueabihf-strip --strip-debug *.ko
```

Driver build settings (aic8800-play6.patch): CONFIG_PLATFORM_UBUNTU=n, `-DANDROID_PLATFORM`
in all three, CONFIG_USE_P2P0=y (creates p2p0, the stock wpa_supplicant is started with
`-N -ip2p0`), CONFIG_GKI=n, CONFIG_USE_FW_REQUEST=n (files read directly from aic_fw_path),
CONFIG_PREALLOC_RX_SKB=y, CONFIG_PREALLOC_TXQ=y, CONFIG_USB_BT=y, CONFIG_USE_WIRELESS_EXT=y.
aic_btusb: CONFIG_BLUEDROID=1 (own char device /dev/aicbt_dev, needs AIC libbt-vendor).
Source fix: `rwnx_wakelock.c` uses the 2-argument `wakeup_source_register(dev, name)` that the
Android 4.19 kernel has backported.

## Module parameters

- aic_load_fw: `aic_fw_path` (string, max 200; files are read flat from `<aic_fw_path>/<name>`,
  default when empty `/vendor/etc/firmware`), `testmode` (0 normal), `aicwf_dbg_level`,
  `adap_test`, `ble_scan_wakeup_reboot_time`, `paringid`, `flash_erase_len`, `rx_buff_list_ava`
- aic8800_fdrv: path comes from aic_load_fw (get_fw_path). Useful: `country_code` (default "00"),
  `wifi_mac_addr`, `aicwf_dbg_level`, `ps_on`, `he_on`, `vht_on`, `use_80`; full list via modinfo
- aic_btusb: `bt_support`, `btdual`, `testmode`, `mp_drv_mode`

## Load order

```
# firmware_class.ko and cfg80211.ko are already loaded by the stock init (init.fusion.rc)
insmod aic_load_fw.ko aic_fw_path=/path/to/firmware     # binds 8d90, uploads BT patch + fmacfw
insmod aic_btusb.ko                                     # binds 8d91 interface E0/01/01
# wait until the device re-enumerates as 368b:8d91
insmod aic8800_fdrv.ko                                  # binds 8d91 vendor interface, wlan0 + p2p0
```

## Offline proof

`kocheck.py check build-inputs/Module.symvers.play6-1.7.116 (+ aic_load_fw exports) *.ko`:
every `__versions` entry exists with the same CRC, every undefined symbol is exported by the
stock kernel/cfg80211/firmware_class/aic_load_fw, vermagic is identical. 415 entries
(77 + 243 + 95), 0 problems. `module_layout` CRC 0xe22991c4 and `.gnu.linkonce.this_module`
size 512 equal the stock modules. Relocation types are the same set as stock wlan_mt7663.ko.

The rebuilt Module.symvers (10716 vmlinux symbols from __ksymtab/__ksymtab_gpl/__kcrctab at
VA 0xc1010fac/0xc1025e94, base 0xC0008000) was validated against the 30 stock ARM modules:
2644 vmlinux-resolved and 86 cfg80211-resolved __versions entries, 0 CRC mismatches.
