# libbt-vendor-aic.so for XGIMI Play 6 (AIC8800D80X2 USB)

Bluetooth vendor library (`BLUETOOTH_VENDOR_LIB_INTERFACE`) for the AICSemi AIC8800D80X2
USB chip, built for the stock Play 6 Bluetooth HAL (Android 11 vendor,
`android.hardware.bluetooth@1.0-service` + AOSP `@1.0-impl` VendorInterface, which
dlopens `libbt-vendor.so`). Lumen OS bind-mounts it over `/vendor/lib/libbt-vendor.so`
on AIC units only.

## Outputs

| File | What |
|---|---|
| `out/libbt-vendor-aic.so` | the library (ELF32 ARM EABI5, soname `libbt-vendor.so`, stripped) |
| `out/system/etc/bluetooth/aicbt.conf` | libbt config, goes to `/system/etc/bluetooth/aicbt.conf` |
| `aic-libbt-play6.patch` | source patch against the SDK libbt (`patch -p1` inside `libbt/`) |
| `out/check.txt` | output of `tools/check.sh` (symbol / NEEDED / layout checks) |
| `SHA256SUMS` | hashes of all of the above plus shims, headers, scripts |

## Inputs

- Source: `radxa-pkg/aic8800` (https://github.com/radxa-pkg/aic8800), commit
  `fce88024ed40279def034e203128e0892fdbcac3` (2026-10-20), AIC SDK
  `aic8800d_linux_sdk_V5.0_2026_0123_5f7be68d`, path `src/libbt-vendor/aicbt/libbt`
  (`AICBT_RELEASE_NAME` in the code: `2025_0515_BT_ANDROID_4.4-14`).
  Pristine copy: `orig/libbt`, patched tree: `tree/libbt`.
- AOSP headers: `platform/system/bt` tag `android-11.0.0_r48`, fetched one file at a time from
  `https://android.googlesource.com/platform/system/bt/+/refs/tags/android-11.0.0_r48/<path>?format=TEXT`
  (base64) into `aosp/`. Only these are needed:

  | path | sha256 |
  |---|---|
  | `hci/include/bt_vendor_lib.h` | `5534d626961f9b0a8c7fdc0087492b792bafd1fdb521bcb750a3f4c8f53d098b` |
  | `hci/include/bt_hci_bdroid.h` | `ef071c374094cb4bcb16a5ed3135fc8bc33ea57ba65cc0ed5dc41ed072100392` |
  | `hci/include/userial.h` | `aa2cc09039a304675cc8d7ec403f2df80708aa2530bbe835c2946409e657d882` |
  | `device/include/esco_parameters.h` | `2941b0e6fa6e7bc98ce9ccf49bc58772ebb3ef258d0bc2e8529ad26d4c7a7622` |

  `bt_utils.h` is not needed (its only include is commented out in `aic_poll.c`);
  `bdroid_buildcfg.h` is not needed (`-DHAS_NO_BDROID_BUILDCFG`, as Android.mk does without a
  board buildcfg dir).
- NDK r29 `29.0.14206865`, clang 21.0.0 (Android 13989888, based on r563880c),
  target `armv7a-linux-androideabi30` (armeabi-v7a, API 30), Thumb-2, NEON, softfp.

## Generated header

`gen/vnd_buildcfg.h` is made from `include/vnd_generic.txt` by
`tools/gen-buildcfg-portable.sh`: same sed program as the SDK `gen-buildcfg.sh`
(strip spaces, drop comments and blank lines, `KEY = V` becomes `#define KEY   V`, guard
`_VND_BUILDCFG_H`), written with `sed -E` so it runs with BSD sed, and without the date in
the banner so the output is reproducible. Relevant values: `VOHCI_SUPPORT FALSE` (no SCO over
HCI, so the SBC/PLC codec code is not linked), `USE_CONTROLLER_BDADDR TRUE`,
`BT_WAKE_VIA_PROC TRUE` (UART only), `FW_PATCHFILE_LOCATION` (unused for USB).

## Shims (`shim/`, header only, nothing extra is linked)

- `utils/Log.h` includes `log/log.h`.
- `log/log.h`: `ALOGV/D/I/W/E`, `LOG_PRI`, `ALOG` on NDK `<android/log.h>`
  (`__android_log_print`), `LOG_TAG` expanded at the call site, `ALOGV` compiled out unless
  `LOG_NDEBUG` is 0, same rules as liblog `log_main.h`.
- `cutils/properties.h`: `PROPERTY_KEY_MAX`/`PROPERTY_VALUE_MAX` (= `PROP_NAME_MAX`/`PROP_VALUE_MAX`, 32/92),
  `static inline property_get()` on `__system_property_get()`, libcutils contract (returns the length;
  unset or empty property copies the default, truncated to 91 chars).
- `cutils/sockets.h`: `ANDROID_SOCKET_NAMESPACE_ABSTRACT/RESERVED/FILESYSTEM`,
  `socket_make_sockaddr_un`, `socket_local_server_bind`, `socket_local_server`,
  `socket_local_client_connect`, `socket_local_client`, `socket_close` as static inline
  functions, behaviour of libcutils `socket_local_server.cpp` / `socket_local_client_unix.cpp`
  (AF_LOCAL; abstract name = leading NUL, addrlen = offsetof(sun_path) + 1 + strlen;
  `/dev/socket/` prefix for RESERVED; unlink + SO_REUSEADDR before bind; listen backlog 4).
  In this SDK version the live code only includes the header (the `socket_local_server_bind`
  calls are in `userial_vendor.c.bak`, which is not built), so none of them ends up in the binary.

## Source patch (`aic-libbt-play6.patch`)

1. `src/bt_vendor_aic.c`: `AICBT_CONF_FILE` `/vendor/etc/bluetooth/aicbt.conf` to
   `/system/etc/bluetooth/aicbt.conf`.
2. `src/aic_heartbeat.c`: `AICBT_HEARTBEAT_CONF_FILE` to `/system/etc/bluetooth/aicbt_heartbeat.conf`
   (the heartbeat module is not referenced by this SDK's code, so it is dropped by
   `--gc-sections`; patched anyway for consistency).
3. `Scan_Usb_Devices_For_AIC()`: accept `idVendor` `0x368b` (AIC V2, D80X2 after the firmware
   load re-enumerates as `368b:8d91`) besides `0xa69c`, so the `?<uart>` fallback picks
   `/dev/aicbt_dev` even with no `BtDeviceNode` in the conf.
4. Same function: `opendir()` results are checked for NULL (sysfs or SELinux denial would
   otherwise crash in `readdir(NULL)`), and the first `stat()` error log printed an
   uninitialised buffer (`newpath`), now prints `path`.

`userial_vendor.c.bak` (unused backup in the SDK) is not part of the tree (diff run with
`-x '*.bak'`).

Compiler warnings: built with the SDK flags `-Wall -Werror -Wno-switch -Wno-unused-function
-Wno-unused-parameter -Wno-unused-variable` plus one relaxation, `-Wno-unused-but-set-variable`
(18 hits; that warning is new in clang 13+ and was not part of `-Wall` in the Android 11 clang).
No other warnings.

## Build

```sh
cd libbt
./build.sh                 # NDK=... to override; result out/libbt-vendor-aic.so
check.sh <Play 6 V1.7.116 /vendor/lib dump> > out/check.txt
```

`build.sh` does, for every source in the SDK Android.mk `LOCAL_SRC_FILES`:

```sh
CC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi30-clang
$CC -march=armv7-a -mthumb -mfloat-abi=softfp -mfpu=neon -fPIC -O2 -g0 \
    -ffunction-sections -fdata-sections -fno-common -std=gnu99 \
    -DANDROID -DHAS_NO_BDROID_BUILDCFG \
    -Wall -Werror -Wno-switch -Wno-unused-function -Wno-unused-parameter -Wno-unused-variable \
    -Wno-unused-but-set-variable \
    -Igen -Ishim -Itree/libbt/codec/sbc -Itree/libbt/codec/plc -Itree/libbt/include \
    -Iaosp/hci/include -Iaosp/device/include -c <src> -o obj/<src>.o
```

then links:

```sh
$CC -shared -o out/libbt-vendor-aic.so obj/*.o \
    -Wl,-soname,libbt-vendor.so -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,defs \
    -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1 -Wl,--hash-style=both \
    -Wl,--pack-dyn-relocs=none -Wl,--version-script=libbt.map \
    -nostdlib++ -llog -ldl -lm -lc -s
```

- `libbt.map` exports only `BLUETOOTH_VENDOR_LIB_INTERFACE`; everything else is local.
- soname `libbt-vendor.so`, the same as the stock MTK library it replaces and the name the
  HAL dlopens.
- `--pack-dyn-relocs=none`: plain `R_ARM_RELATIVE`, no `DT_ANDROID_RELR` (works with any linker).
- No C++ runtime (`-nostdlib++`); compiler-rt builtins (`__aeabi_uidiv` etc.) are static.
- Before compiling, `tools/layout_check.c` is built with `-fsyntax-only`: static asserts that
  `bt_vendor_interface_t` is 16 bytes (`size, init, op, cleanup` at 0/4/8/12),
  `bt_vendor_callbacks_t` is 40 bytes with 10 members (`a2dp_offload_cb` at 36), `HC_BT_HDR` is
  8 bytes, opcode numbering `POWER_CTRL=0 ... A2DP_OFFLOAD_STOP=11`, `CH_MAX=4`.
- The build is reproducible (two runs give the same sha256).

## Checks (`tools/check.sh`, result in `out/check.txt`: ALL CHECKS PASSED)

- `ELF 32-bit LSB shared object, ARM, EABI5 version 1 (SYSV)`, e_flags `0x5000200`
  (EABI5, soft-float ABI), stripped, 46872 bytes.
- `NEEDED`: `liblog.so libdl.so libm.so libc.so` only. `SONAME`: `libbt-vendor.so`.
  `BIND_NOW`, no `TEXTREL`, `GNU_STACK` RW (not executable).
- Defined dynamic symbols: exactly one, `BLUETOOTH_VENDOR_LIB_INTERFACE`, `OBJECT GLOBAL DEFAULT`,
  size 16 (same as the stock MTK `libbt-vendor.so`: `OBJECT GLOBAL DEFAULT`, size 16).
- Version needs: `libc.so` `LIBC` and `LIBC_N` only (nothing above `LIBC_R`).
- 78 undefined symbols, each found in the NDK API 30 stub of libc (76) or liblog
  (`__android_log_print`); lowest API that has each: 21 for all except `__register_atfork` (23):
  `__FD_SET_chk __aeabi_memclr4 __aeabi_memclr8 __aeabi_memcpy __android_log_print __assert2
  __cxa_atexit __cxa_finalize __errno __register_atfork __system_property_get asprintf bind
  cfmakeraw cfsetispeed cfsetospeed close closedir epoll_create epoll_ctl epoll_wait fclose fflush
  fgets fopen fputs free gettimeofday inotify_add_watch inotify_init1 ioctl localtime malloc memcmp
  nanosleep open opendir poll prctl pthread_attr_init pthread_attr_setdetachstate pthread_create
  pthread_join pthread_mutex_destroy pthread_mutex_init pthread_mutex_lock pthread_mutex_unlock
  read readdir remove rename rewind select sem_destroy sem_post shutdown snprintf socket socketpair
  sprintf stat strchr strcmp strcpy strerror strftime strlen strnlen strstr strtol tcflush
  tcgetattr tcsetattr time timer_delete timer_settime usleep write`.
  No `pthread_cancel` or other non-bionic calls.
- Relocations: 78 `R_ARM_JUMP_SLOT`, 36 `R_ARM_RELATIVE`. No `IRELATIVE`, no TLS relocations,
  no `PT_TLS`, no IFUNC symbols.
- Strings present: `/system/etc/bluetooth/aicbt.conf`, `/dev/aicbt_dev`, `/sys/bus/usb/devices`.
- Stock HAL `vendor/lib/hw/android.hardware.bluetooth@1.0-impl.so` (Play 6 v1.7.116) contains
  `libbt-vendor.so` and `BLUETOOTH_VENDOR_LIB_INTERFACE`; its strings (`sco_config_cb`,
  `low_power_mode_cb`, `sco_audiostate_cb`, `epilog_cb`, `a2dp_offload_cb`,
  `internal_command_event_match`, H4/MCT protocol) are the unmodified AOSP Android 11
  VendorInterface, i.e. the same 10-member callback struct the layout check asserts.

## aicbt.conf (`/system/etc/bluetooth/aicbt.conf`)

The SDK has two different files with this name:

- `src/USB/driver_fw/fw/aic8800D80X2/aicbt.conf` (= `aicbuild/out/firmware/aicbt.conf`,
  keys `BTMODE BTPORT UART_BAUD UART_FC LPM_ENABLE TXPWR_LVL`) is for the kernel firmware
  loader (`aic_load_fw` `aicbt_parse_config()`), compiled only with `CONFIG_LOAD_BT_CONF=y`
  (default `n`, also `n` in our module build), so today it is not read at all. libbt does not
  read those keys. Do not enable `CONFIG_LOAD_BT_CONF` with it as shipped: `BTPORT=2` selects the
  UART port, the USB default is `1` (mailbox/USB).
- `src/libbt-vendor/aicbt/vendor/etc/bluetooth/aicbt.conf` is the libbt file. Ours is based on
  it, with LF line endings, without the unused `DevClass*` lines, and with
  `BtDeviceNode=/dev/aicbt_dev`.

Keys libbt reads (no inline comments: the value runs to end of line):

| Key | Value | Meaning |
|---|---|---|
| `BtDeviceNode` | `/dev/aicbt_dev` | HCI node. `/dev/aicbt_dev` = USB transport, H4. A leading `?` means "scan USB, else use this UART". If the conf cannot be read, the default is `?` + `persist.vendor.bluetooth_port` (or `/dev/ttyS1`) and the USB scan (now 0x368b aware) still finds `/dev/aicbt_dev`. |
| `AicBtsnoopDump` | `false` | AIC's own btsnoop file inside the vendor lib |
| `BtSnoopFileName` | `/data/misc/bluedroid/btsnoop_hci.cfa` | base name for that file (time stamp appended) |
| `BtSnoopSaveLog` | `true` | keep the previous snoop file instead of overwriting |
| `AicbtLogFilter` | `1` | bit0: hide heartbeat vendor packets (0xFC94) from snoop |
| `H5LogOutput` | `0` | H5 (3-wire UART) slave log, UART only |
| `BtCoexLogOutput` | `0` | BT/WiFi coex log (coex module not linked in this config) |
| `AicBtsnoopNetDump` | `false` | stream snoop over UDP (debug) |
| `AicFwLogEnable` | `false` | ask the controller to emit firmware log (vendor config 0x31) |
| `AicBtAutoRestart` | `true` | parsed, not used anywhere in this SDK version |
| `AicBtLogLevel` | `0x03` | log mask: 0x01 error, 0x02 info, 0x04 trace, 0x08 debug, 0x10 data, 0x20 fw |

`aicbt_heartbeat.conf` is optional and unused (heartbeat module not linked).

## Runtime behaviour on the USB path (from the code)

- init: reads the conf, picks `/dev/aicbt_dev` (H4 over USB), starts an inotify thread on
  `/dev` (watches for a file named `shutdown`; if the watch is denied it gives up after 4 tries,
  harmless).
- `BT_VND_OP_POWER_CTRL`: tries rfkill under `/sys/devices/platform/aic-bt/rfkill` and
  `/proc/bluetooth/sleep/*` (Rockchip UART leftovers, absent on Play 6: only error logs),
  sleeps 200 ms.
- `BT_VND_OP_USERIAL_OPEN`: `open("/dev/aicbt_dev", O_RDWR)` (10 tries, no delay), then a
  socketpair whose one end is returned as the single H4 fd (return 1) and the other end is
  bridged to the char device by the library's own threads. Standard for the AOSP HAL
  (H4Protocol for fd_count 1).
- `BT_VND_OP_FW_CFG`: `ioctl(GET_USB_INFO = _IOR('E',180,int))` on `/dev/aicbt_dev` (the
  driver opens its URBs here, `compat_ioctl` exists), then HCI only: `HCI_Reset (0x0C03)`, AIC
  vendor config (assert resume, fw version, fw log), Read BD_ADDR, write it back
  (`USE_CONTROLLER_BDADDR`: the controller OTP address wins over the HAL's), Read Local
  Features, then `fwcfg_cb(SUCCESS)`. No firmware or patch download in libbt for USB.
- `BT_VND_OP_EPILOG` on USB: immediate success. LPM ops: no-op on USB, idle timeout non-zero.
- Firmware: for the D80X2 the BT patch, patch table and ext patches
  (`fw_patch_8800d80x2_u0x*.bin`, `fw_patch_table_*`, `fw_adid_*`) are downloaded by the
  kernel module `aic_load_fw` (`aic_compat_8800d80x2.c`, `aicbt_patch_table_load()`) before
  the chip re-enumerates as `368b:8d91`; `aic_btusb` only downloads patches itself for
  DC/D80N, and its ioctl download calls are commented out. libbt never touches firmware.
