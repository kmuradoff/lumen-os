# UI resolution: 1080p, 4K switchable but off in 1.0.1 (ro.z9x.uires.allow=0; image side)

The Android UI renders at **4K (3840x2160, default, 1:1 on the panel)** or 1080p, picked in Projector
settings. 2K (2560x1440) is not offered: it is a hidden debug mode (section 5). Every mode has the same
960x540 dp layout. A start the projector cannot show correctly falls back to 1080p by itself, with no
remote and no user. Measurements are in the header of `z9x_uires.sh`.

Why the 1.0.1-20261009b switch alone could not work, and what changed: the vendor GOP scales the HWC
display by panel / *Display Region* (`Dst = vendor.display-size x 3840x2160 / region`), and the region is
`osdWidth` / `osdHeight` of the active panel ini (1920 / 1080), read once by the MI daemon when it starts.
So 2K gave Dst 5120:2880 and 4K 7680:4320 (cropped). Now `z9x_uires.sh fs` puts a RAM copy of that ini
with the mode's region over the vendor file (read-only bind mount) before the MI daemon starts: region =
display-size, so Dst = the panel. The vendor partition is never written; the copy is gone at every restart.

How the copy is made read-only (changed after the device test of 2026-10-09, image lumen-1.0.1-20261009c,
root shell, toybox `mount`). The first method bound a copy on the `/dev` tmpfs with `mount -o bind,ro`:
on the Z9X that returns 0 but the bind is **read-write** (mountinfo `... /z9x_dbg/p.ini <ini>
rw,nosuid,relatime shared:2 - tmpfs tmpfs rw,seclabel,mode=755`), so `fs` safely fell back to 1080p ("bind
mount not read-only (unmounted)"). Its repair, `mount -o remount,bind,ro <ini>`, made toybox remount the
**whole `/dev` tmpfs superblock** (`mount: 'tmpfs'->'<ini>': Device or resource busy`, exit 1): it failed only
because `/dev` was busy, and a read-only `/dev` breaks the system. Neither command is used any more. The
method verified on the device instead:

1. `mount -t tmpfs -o size=576k,mode=0755 z9x_uires /dev/z9x_uires`: a small tmpfs of our own (only if
   `/dev/z9x_uires` is not a mount point already, and only used once mountinfo shows that tmpfs there);
2. the copy written and verified in it as before, `chmod 0444`, `chcon` to the original's label;
3. `mount -o remount,ro /dev/z9x_uires`: our own mount point is the only thing ever remounted; mountinfo
   must then show it read-only (device: `/ /dev/z9x_dbg ro,relatime shared:50 - tmpfs z9x_uires
   ro,seclabel,size=256k,mode=755`);
4. `mount -o bind <copy> <ini>`, a plain bind: a bind of a file on a read-only tmpfs is read-only (device:
   `/p.ini <ini> ro,relatime shared:50 - tmpfs z9x_uires ro,seclabel,size=256k,mode=755`, `-r--r--r-- root
   root u:object_r:tv_config_file:s0 21641`, `cmp` equal, `echo test >> <ini>`: `Read-only file system`;
   both umounts returned 0). mountinfo decides, not an exit code: read-only = `ro` in the mount's own
   options or in its superblock's (after the ` - tmpfs z9x_uires`). Not read-only: the bind and the tmpfs
   are unmounted, 1080p.

The device test used 256k; the image uses 576k (a limit, not an allocation): the copy and its round-trip
check (section 3) are both in the tmpfs, up to 2 x 256 KiB.

Why 1.0.1-20261009d still fell back to 1080p, and the fix in 20261009e: **WindowManager's max UI width**.
On the owner's projector (slot `_a`) the 4K start of 20261009d did everything above right at `on fs`: the
OSD region bind (tmpfs `ro`, bound `ro`), `vendor.display-size` 3840x2160, `ro.config.size_override
3840,2160`, `ro.config.density_override 640`, SF display mode and client target 3840x2160, HWC Display
Region 3840x2160, GOP Layer / Dst 3840:2160 at 17 s uptime. But `wm size` said `Physical size: 3840x2160` /
`Override size: 1920x1080` and `dumpsys window displays` `init=3840x2160 640dpi base=1920x1080 640dpi`, so
`check` failed (`check: WM 1920x1080, GOP 1920x1080->3840x2160`) and restarted once into 1080p, as designed.
The cap: `cmd overlay lookup android android:integer/config_maxUiWidth` = **1920**, from
`/system/product/overlay/TvFrameworkOverlay.apk` (com.android.tv.overlay.framework, AOSP
`device/google/atv/overlay/TvFrameworkOverlay/res/values/config.xml:173`; the framework default is 0 = no cap).
WindowManagerService hands it to `DisplayContent`, whose `updateBaseDisplayMetrics` clamps the base width to
it (and scales the height) also for a forced size, and `setForcedSize` clamps too: neither
`ro.config.size_override` nor `wm size 3840x2160` gets past it. Fix: our framework RRO
`org.z9x.overlay.framework` (`apps/Z9xFrameworkKeysOverlay`, `/system/product/overlay/Z9xFrameworkKeysOverlay.apk`,
static, priority 2000: after TvFrameworkOverlay and the Lineage product RRO in `cmd overlay list android`, so
its value wins) sets `config_maxUiWidth` **3840**. 1080p (1920 wide) is below the cap, so nothing changes
there; 2K debug (2560) is no longer capped either. Build guard: the `lumen_v1.sh` preflight refuses an APK set
whose `Z9xFrameworkKeysOverlay.apk` does not set it to >= 3840 while `ro.z9x.uires.allow=1` (section 1).

## 1. Files into the image (for the integrator, `tools/lumen_v1.sh`)

Unchanged list: no new image file, no new rc, no policy change.

| Source (`overlay/v1/z9x_uires/`) | Image path | Mode | Label | Note |
|---|---|---|---|---|
| `z9x_uires.sh` | `system/etc/z9x/z9x_uires.sh` | 0755 | system_file | dir `system/etc/z9x` already made for the OTA files |
| `init.lineage.atv.scaling.rc` | `system/product/etc/init/init.lineage.atv.scaling.rc` | 0644 | system_file | **replaces** the LineageOS member of the same path (it set `ro.config.size_override 1920,1080` and `ro.config.density_override 320`). **Must stay in product** (section 2) |
| `product_prop.txt` | appended to the product build.prop snippet | | | `ro.surface_flinger.max_graphics_width=3840`, `_height=2160`, `ro.z9x.uires.allow=1` |
| `test/` | nothing | | | host test only |

`lumen_v1.sh` (as before; the test now also needs nothing but the host's `cmp`, `tail`, `od`):

```sh
UIRES=$V1/z9x_uires
# preflight
for f in z9x_uires.sh init.lineage.atv.scaling.rc product_prop.txt; do need "$UIRES/$f"; done
[ "$(head -n1 "$UIRES/z9x_uires.sh")" = '#!/system/bin/sh' ] || die "z9x_uires.sh: first line"
shlint "$UIRES/z9x_uires.sh"
grep -qE '^ +exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_uires\.sh fs$' "$UIRES/init.lineage.atv.scaling.rc" \
  || die "init.lineage.atv.scaling.rc does not exec z9x_uires.sh fs"
[ "$(grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep 'sys\.powerctl' | tr -s ' ')" = ' setprop sys.powerctl reboot,z9x-uires' ] \
  || die "z9x_uires.sh: the only sys.powerctl use must be reboot,z9x-uires"
sh "$UIRES/test/run.sh" > "$WORK/uires_test.log" 2>&1 || { tail -n 30 "$WORK/uires_test.log"; die "z9x_uires host tests"; }
# members (after 'mkd system/etc/z9x 755')
add system/etc/z9x/z9x_uires.sh "$UIRES/z9x_uires.sh" 755
repl system/product/etc/init/init.lineage.atv.scaling.rc "$UIRES/init.lineage.atv.scaling.rc" 644 u:object_r:system_file:s0
```

- Product props: append `product_prop.txt` after `product_prop_v1.txt` in the
  `system/product/etc/build.prop` `--sub`. Add it to the CRLF, backslash, compat-prop and duplicate
  checks of the three snippets.
- Spec lines: `T system/product/etc/build.prop ro.surface_flinger.max_graphics_width=3840`, `... _height=2160`,
  `... ro.z9x.uires.allow=1`.
- Base check: add `system/product/etc/init/init.lineage.atv.scaling.rc` to `NEED_FILES` and
  `system/etc/z9x/z9x_uires.sh` to `NEW_PATHS`. Also assert that the base file still holds exactly
  LineageOS's two setprops, so a rebase that changes it is noticed. In the 1.0.1 base, no other
  rc/prop/sh member sets `ro.config.size_override`, `density_override`, `vendor.display-size`,
  `resize.framebuffer` or `max_graphics_*` (checked 2026-10-09). Also assert that no image member
  sits under `system/vendor` or touches `/vendor/tvconfig`: this script is the only one that mounts there.
- `tools/sign/check_image.py`: compare the two members byte for byte with these sources (like
  `OTA_FILES`). Check the effective `ro.surface_flinger.max_graphics_width=3840` / `_height=2160`.
  Fail when any `PROP_FILES` member sets `ro.config.size_override` or `ro.config.density_override`:
  only the rc may set them, and only once.
- WindowManager's cap (20261009e, see "Why" above): not a file of this directory, but 4K depends on it.
  `apps/Z9xFrameworkKeysOverlay/res/values/config.xml` sets `<integer name="config_maxUiWidth">3840</integer>`,
  and the preflight runs, while `product_prop.txt` has `ro.z9x.uires.allow=1`,
  `python3 tools/lumen/lumen_checks.py maxui overlay/apps_v1/Z9xFrameworkKeysOverlay.apk 3840`: the value is read
  from the APK's `resources.arsc` (tool-free, `apkinfo.py res`) and cross-checked with `aapt2 dump resources`
  when an aapt2 is found (`$AAPT2`, `$BUILD_TOOLS`, the Mac SDK, `tools/bt` on the laptop); it must be set
  once, in the default configuration, a plain integer >= 3840. The 20261009d APK (no value) fails it.

Runtime paths (nothing in the image):

| Path | What | Mode / label |
|---|---|---|
| `/dev/z9x_uires/` | mount point made by `fs` on tmpfs `/dev`; our own tmpfs on it (`mount -t tmpfs -o size=576k,mode=0755 z9x_uires`), remounted `ro` once the copy is in it (gone at every restart; removed again after a failure) | 0755 root |
| `/dev/z9x_uires/UD_VB1_16LANE_CSOT_URSA.ini` | the generated copy of the panel ini named by `Customer_1.ini` | 0444 root, **the original's label** (`u:object_r:tv_config_file:s0`, read with `stat -c %C`, else `ls -Zd`) |
| `/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini` | plain bind mount of the copy (`mount -o bind`), read-only because its tmpfs is; checked in `/proc/self/mountinfo` (`ro` in the mount's or the superblock's options), else unmounted with the tmpfs and 1080p | the vendor file itself is not written: `/vendor/tvconfig` is ext4 `ro` |
| `/metadata/z9x_uires/` | state and log (section 4), as in 1.0.1-20261009b, plus `debug` (section 5) and the marker `osd_override` (section 4) | root |

SELinux: XGIMI's bootloader passes `androidboot.selinux=permissive` (stock and Lumen), so nothing is
denied today. The labels are still the correct ones: the readers (MI daemon `u:r:midaemon:s0`, the
HWC, `libvsyncbridge` in the media processes) see the same `tv_config_file` type as on stock, through the
same path. For a future enforcing policy, the `su` domain would need `mount` / `remount` / `unmount` on the
tmpfs filesystem type, `mounton` on the `/dev/z9x_uires` directory and on `tv_config_file`, `relabelfrom`
the tmpfs file type / `relabelto tv_config_file` on files, and `tv_config_file` an `associate` with tmpfs;
the readers need nothing new. The script runs as `u:r:su:s0` (userdebug only, like
z9x_rescue / z9x_ota).

## 2. When: `on fs` in the product rc (evidence from the vendor dumps)

| What | Where (vendor dump `z9x-firmware-dump/fs/vendor.tar`, = the device) | Stage |
|---|---|---|
| `/vendor/tvconfig` mounted | `/vendor/etc/fstab.mt9952` line `/dev/block/by-name/tvconfig /vendor/tvconfig ext4 ro,noatime,nosuid,nodev,noexec wait,slotselect`: no `first_stage_mount` (system/vendor/product/metadata have it). Mounted by `/vendor/etc/init/hw/init.common.rc:50-51` `on fs` / `mount_all /vendor/etc/fstab.${ro.hardware} --early` (imported by `/vendor/etc/init/init.fusion.rc:2`). Same fstab on the friend's device: `logs/friend_<serial>/for_101/fstab.mt9952` | `on fs` (vendor) |
| **our `exec ... z9x_uires.sh fs`** | `/product/etc/init/init.lineage.atv.scaling.rc` `on fs` | `on fs`, after every vendor `on fs` action |
| MI daemon (reads the panel ini; fixes the region) | `/vendor/etc/init/init.fusion.rc:22-27` `on post-fs` ... `start intertaca` / `start midaemon`; `/vendor/etc/init/midaemon_user.rc` `service midaemon /vendor/bin/logwrapper /vendor/bin/midaemon.sh`, `class core` (`class_start core` is only at `on boot`, stock `/system/etc/init/hw/init.rc:1212`); `midaemon.sh`: `LD_LIBRARY_PATH=...:/vendor/tvconfig/config:...`, `exec /mnt/vendor/tvservice/bin/midaemon` | `on post-fs` |
| HWC, mmdisp, SurfaceFlinger | `/vendor/etc/init/init.fusion.rc:14-20` `on late-fs`: `start hidl_memory`, `start tv-mtkrm-hal-1-0`, `start mstarmmdisp`, `start vendor.hwcomposer-3`, `start surfaceflinger` | `on late-fs` |
| boot video | `/vendor/etc/init/init.fusion.rc:45,52` `on early-boot` / `start bootvideo` | `on early-boot` |
| `panelini_tool` | started by no rc in `/vendor/etc/init` and called by no `/vendor/bin/*.sh`; its strings (`&panel_cus_setting {`, `OSD_Width = <%d>;`) show an ini-to-DTS converter, not a boot service | never at boot |

- Order inside `on fs`: Android 14 init parses `/system/etc/init/hw/init.rc`, `/system/etc/init`,
  `/system_ext/etc/init`, `/vendor/etc/init` (with its imports), `/odm/etc/init`, `/product/etc/init`, and
  runs the actions of one trigger in that order. So our `on fs` runs after the vendor's `mount_all --early`,
  and as an `exec` it blocks init until it returns, before `post-fs` (MI daemon, intertaca) and `late-fs`
  (HWC). The product rc is live on Lumen: LineageOS's `ro.config.size_override 1920,1080` from this very
  file is set (`logs/friend_<serial>/for_101/display_4k/props.txt`), product fingerprint
  `lineage_gsi_tv_arm64`. **Moved to `/system/etc/init`, its `on fs` would run before the vendor's
  mount_all**: the script would find no `Customer_1.ini` and start 1080p (tested: `osd_no_customer`).
- No later point works: the vendor's `on post-fs` starts the MI daemon before any product `on post-fs`
  action, and a property trigger raised during `fs` runs after `boot` (`on late-init` queues early-fs ...
  boot at once: stock `/system/etc/init/hw/init.rc:532-547`; LineageOS's init.rc has the same).
- Measured on a Lumen start (`logs/v2_boot/z9x_boot_1/props.txt`, `ro.boottime.*`): `mount_all.early`
  272 ms (`mount.tvconfig` 8 ms); vold 3.483 s (early-fs); optee 4.139, intertaca 4.146, **midaemon 4.155 s**
  (post-fs); hidl_memory 4.403, mstarmmdisp 4.411, **vendor.hwcomposer-3 4.423 s**, surfaceflinger 4.446 s
  (late-fs). The first panel ini read in dmesg is the MI daemon's (`panel path =
  [/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini]`, pid 229, after `Set Connect process
  name is midaemon`); the saved dmesg logs begin at 5.7-6.1 s (ring buffer), so this shows the MI
  daemon reads it, not that nothing reads it earlier (see the residual risk below).
- Mount namespaces: `enter_default_mount_ns` is at `post-fs-data` (stock `init.rc:713`), so our exec, the MI
  daemon and the HWC all run in init's bootstrap namespace and see the mount directly; `/` is shared
  (init), so it also propagates to the default namespace (apps, media, adb shell).
- Not blocking. Residual risk: `init.insmod.rc:34-41` loads the MI kernel modules (`iniparser.ko`,
  `kdrv_xc.ko`, `mik.ko`) with `exec_background` in the vendor's `on fs`, i.e. while our exec runs. In the
  logs they read inis only on the MI daemon's behalf. If one read the panel ini at load, the region would
  stay 1920x1080: `check` sees it (section 3) and falls back once.
- Cost of `fs`: a few file reads, 6 short awk passes over the 21 KB ini, `cmp`, `chcon`, three mount calls
  (the tmpfs, its remount, the bind) and a few reads of `/proc/self/mountinfo`; no sleep, no dumpsys (well under 100 ms; check `log.txt` `up` vs a 1080p start, device check 9). The vendor PWM
  watchdog needs early-boot in time; this is far inside it.

## 3. Modes (`MODES` in `z9x_uires.sh`: the only place to change a value)

| mode | OSD region (panel ini) | vendor.display-size | resize.framebuffer | ro.config.size_override | density | dp | who |
|---|---|---|---|---|---|---|---|
| 1080 | vendor's (no bind mount) | 1920x1080 | 1 (stock) | 1920,1080 | 320 | 960x540 | app |
| **2160 (default)** | 3840x2160 | 3840x2160 | 1 | 3840,2160 | 640 | 960x540 | app |
| 1440 (debug only) | 2560x1440 | 2560x1440 | 1 | 2560,1440 | 427 | 959x539 | `/metadata/z9x_uires/debug` |

- Region = display-size in every mode, so `Dst = display-size x panel / region` = the panel: 1:1 at 4K
  (the stock relation, 1920x1080 both, scaled up), 2x at 1080p (stock), 1.5x at 2K (unverified, hence debug).
- `vendor.mstar.resize.framebuffer` stays **1** (stock): no measurement showed it changing Dst (0 and 1
  gave the same 5120:2880 / 7680:4320), and 1 with region = display-size is exactly the stock setup. The
  check decides on the device; if 4K needs 0, change the 2160 row (one value).
- `vendor.mstar.osd_size` is not touched (stock `2160p`; measured without effect on Dst).

The panel ini copy (`fs`, 4K/2K only; any failure = this start 1080p, nothing mounted, nothing recorded,
the why in `sys.z9x.ui_res.why` and `log.txt`, tried again at the next start):

1. `/vendor/tvconfig/config/model/Customer_1.ini`: exactly one `m_pPanelName =` line (case-insensitive like
   iniparser; `;`/`#` comments do not count), quoted or not, naming a plain `*.ini` path under
   `/vendor/tvconfig/` (no `..`, `//`, spaces).
2. That file: 1 byte to 256 KiB, exactly one `osdWidth =` and one `osdHeight =` line, each a plain decimal
   (`0x780`, `1920.0` are refused). Already the mode's values: no copy, no mount.
3. Our tmpfs: `/proc/self/mountinfo` must be readable and `/dev/z9x_uires` not a mount point yet (else
   1080p, nothing touched there) nor a symlink (the tmpfs would land at its target); `mkdir -p`, `mount -t
   tmpfs -o size=576k,mode=0755 z9x_uires /dev/z9x_uires`, and mountinfo must show a `tmpfs` named
   `z9x_uires` at it before anything is written there or remounted (a remount of a path that is not a mount
   point would reach `/dev` itself). A mount there that mountinfo does not show as ours is unmounted again
   (nothing was mounted there before), unused.
4. The copy (`/dev/z9x_uires/<name>`): only the digits after `=` on those two lines change; spacing,
   comments, CRLF and a missing final newline are kept. Verified before use: exactly those two lines
   differ (line count), the copy parses as one osdWidth/osdHeight with the new values, and putting the old
   values back gives the original byte for byte (`cmp`; catches anything awk cannot carry, e.g. NUL).
5. `chmod 0444`, `chcon` to the original's label, label read back.
6. `mount -o remount,ro /dev/z9x_uires` (the only remount, of our own mount point); mountinfo must show our
   tmpfs there, read-only.
7. `mount -o bind copy original` (plain); the vendor path must then read as the copy (`cmp`) and the top
   mount there must be read-only in mountinfo (`ro` in its own options or in the superblock's), else the
   bind is unmounted. If it can be neither read-only nor unmounted, the mode follows what the vendor path
   shows (4K values, logged `WRITABLE`): 1080p values over a 4K region would show a quarter picture. When
   the vendor path does not read as the copy, the panel ini is unmounted only if mountinfo shows a mount of
   ours there (a mount of another's is never undone; no exit code decides).

Any failure from step 3 on unmounts our tmpfs (after the bind, if one was made): no copy and no mount stay
(if that umount fails too, the why ends in `(/dev/z9x_uires not unmounted)`; the vendor path is not bound to
it then). No exit code is trusted: each step is checked in `/proc/self/mountinfo` or with `cmp`.

The four values are published before the mount and again (1080p) after a failure, so the rc never sets
1080p values while the 4K copy is in effect.

## 4. Fallback

- A 4K/2K start writes `/metadata/z9x_uires/pending`. About 6 s after `boot_completed` (once the display is
  on; it waits through standby and boot-dark), `check` reads `dumpsys SurfaceFlinger` and requires all of:
  display mode (`displaySpace`), SF client target (`framebufferSpace`) and WM size (`layerStackSpace`) =
  the mode; **the HWC's Display Region = the mode** (the data row under the first `Timing[W x H] | ... |
  Display Region[W x H] |` header, column found by its title); the GOP line `CustomerSize
  X:Y|LayerW:LayerH|DstW:DstH[...]` of the enabled FrameBufferTarget window with Layer = the mode and Dst =
  `3840:2160`. A mismatch or an incomplete dump is looked at again, 3 looks 5 s apart, so one odd dump (a
  keystone run, a GOP reconfiguration) never restarts. Pass: pending is removed. Fail: `failed.<mode>`,
  `sys.z9x.ui_res.why`, waits up to 5 min while `sys.z9x.ota.busy=1`, then `reboot,z9x-uires`. The next
  start is 1080p with no bind mount.
- A 4K/2K start that hangs before `boot_completed` without restarting by itself is restarted by `watch`
  (service `z9x_uires_watch`): no `boot_completed` within 360 s writes `failed.<mode>` and restarts with
  `reboot,z9x-uires`. A hang before early-boot is restarted by the vendor PWM watchdog and caught by
  `pending`. The OTA gate's own deadline (480 s after post-fs-data) comes later.
- `pending` still there at the next `on fs` (restart before the check passed, or a start that never
  reached `boot_completed`): `failed.<mode>`, and this start is 1080p. When `failed.<mode>` cannot be
  written, every start that finds `pending` is 1080p anyway: no retry without a record, so no loop.
- Records of 1.0.1-20261009b (same files, but no OSD override: its 2K/4K could not work) are no evidence
  against this build: its first start on a device writes the marker `osd_override` and, only when that
  write worked, drops every `failed.*` there, also the one a `pending` of that build just gave (log:
  `failed.2160 of the build before the OSD override dropped (...)`), so the default 4K is tried once more. Its own records stay (the marker is there). A
  `/metadata` that cannot keep the marker drops nothing.
- `failed.<mode>` is final until the user picks that mode again (debug 2K: `rm failed.1440`). One failed
  start costs one restart, well inside z9x_rescue's 3 and the OTA gate's 3.
- Kill switches: `ro.z9x.uires.allow=0` (image) or the file `/metadata/z9x_uires/off` (adb root): always
  1080p, no tmpfs, no bind mount, no check.

What a `why` means (`sys.z9x.ui_res.why`, `log.txt`):

| why contains | meaning |
|---|---|
| `2160 needs OSD 3840x2160: no Customer_1.ini (tvconfig not mounted?)` | the rc ran before the vendor's mount_all (moved out of product?) |
| `... needs OSD ...: m_pPanelName '...'` / `no <ini>` / `panel ini: not one numeric osdWidth/osdHeight (n/n)` | the vendor files are not what this was built for: 1080p, nothing changed |
| `... needs OSD ...: /dev/z9x_uires is a mount point already` | something is mounted where our tmpfs goes (`fs` run by hand?): not touched, 1080p |
| `... needs OSD ...: no /proc/self/mountinfo` | no mount could be verified: nothing mounted, 1080p |
| `... needs OSD ...: cannot make /dev/z9x_uires` / `cannot mount a tmpfs at ...` / `tmpfs at ... not in effect` / `... not read-only after remount` | our tmpfs could not be set up: 1080p, tried again next start |
| `... needs OSD ...: the copy differs ...` / `cannot label the copy` / `cannot write the copy` / `bind mount failed` / `not in effect` / `not read-only` | the RAM copy or its mount did not work: 1080p, tried again next start |
| `... (/dev/z9x_uires not unmounted)` | as above, and our tmpfs stayed mounted (harmless: nothing bound from it) |
| `check: region 1920x1080, GOP 3840x2160->7680x4320` | the MI daemon did not take the copy (read before `on fs`, or another ini): the bind mount is too late or wrong |
| `check: ... GOP 3840x2160->...` with region 3840x2160 | region right, the GOP still scales: try resize.framebuffer 0 in the 2160 row |
| `check: mode 1920x1080` | the HWC did not take `vendor.display-size` |
| `check: WM 1920x1080, GOP 1920x1080->3840x2160` with SF mode and target 3840x2160 | WindowManager's max UI width: `config_maxUiWidth` 1920 (TvFrameworkOverlay) is in effect, i.e. our framework RRO's 3840 is not (20261009d had none; disabled or missing overlay?). `wm size` shows `Override size: 1920x1080`; check `cmd overlay lookup android android:integer/config_maxUiWidth` (section 6, check 1) |
| `check: SF target 1920x1080` / `WM 1920x1080` | `max_graphics_*` / `ro.config.size_override` not in effect (or a user `wm size`) |
| `restart before the ... check passed` / `did not complete boot` / `no boot_completed within 360s` | pending at the next start / hung start (`watch`) |
| `... (not recorded)` | as above, but `failed.<mode>` could not be written |

## 5. Contract with org.z9x.projector (Projector settings)

- `persist.z9x.ui_res` = `1080` | `2160`. Unset or empty = the default **2160**. Any other value is
  ignored, **including `1440`** (2K is not offered; a `1440` stored by 1.0.1-20261009b is treated as unset,
  so that device starts in 4K). Offer exactly two entries: 4K and 1080p. Set it **only on an explicit user
  choice, never at boot or in a sync**: a set after `boot_completed` is a pick and clears that mode's
  fallback. To retry 4K after a fallback, set `2160` again (init fires on every set, even unchanged).
- The choice applies from the next start. Before offering "Restart now", wait (up to 2 s) until
  `sys.z9x.ui_res.want` equals the value. Any `PowerManager.reboot` reason works.
- Read: `sys.z9x.ui_res.active` (this start: `1080` | `2160`, `1440` only in debug: show it as "2K (debug)"),
  `.want`, `.why` (non-empty: show it, e.g. "4K could not be shown, using 1080p"), `.failed` (e.g.
  "2160"), `.check` (`off` / `pending` / `wait` / `ok` / `failed`), `.osd` (`3840x2160` | `stock`).
- Debug 2K (adb root, not for users): `echo 1440 > /metadata/z9x_uires/debug`, restart; it overrides the
  app's choice (`.want` = 1440) until `rm /metadata/z9x_uires/debug`. After a 2K fallback:
  `rm /metadata/z9x_uires/failed.1440` to retry.
- SELinux for the app (unchanged): `persist.z9x.*` is `default_prop` and `sys.*` `system_prop`;
  `platform_app` may neither set nor read them under an enforcing policy. It works because the device is
  permissive (like `persist.z9x.cec_wake`); fixing it needs a property type plus allow rules in the product
  policy, a separate tested change. `z9x_uires.sh` runs as `su` and is unaffected.

## 6. Device checks (first boot with this; no flashing of anything else)

Never run `mount -o remount,...` by hand on the panel ini or on anything under `/dev` (section 1: toybox
remounts the `/dev` superblock); `umount` of the bind, then of `/dev/z9x_uires`, is safe (both measured).

1. Fresh start (default 4K): `adb shell su 0 sh /system/etc/z9x/z9x_uires.sh state`. Expect
   `sf: On 3840 2160 3840 2160 3840 2160 3840 2160 3840 2160 3840 2160` (mode, SF target, WM, **region**,
   GOP Layer, GOP Dst), `ini: /vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini osdWidth x osdHeight
   3840x2160, bound ro,relatime of z9x_uires:/UD_VB1_16LANE_CSOT_URSA.ini (tmpfs
   ro,seclabel,size=576k,mode=755)` and `ram: /dev/z9x_uires ro,relatime tmpfs z9x_uires
   ro,seclabel,size=576k,mode=755` (this from the adb shell's namespace: proves propagation), `check=ok`,
   `osd=3840x2160`; `cmd overlay lookup android android:integer/config_maxUiWidth` prints **`3840`** (in every
   mode; `1920` = TvFrameworkOverlay's cap is in effect, our RRO is not: see "Why" above); `wm size` prints
   only `Physical size: 3840x2160` and **no `Override size` line** (20261009d: `Override size: 1920x1080`),
   `dumpsys window displays` `init=3840x2160 640dpi` with **no `base=` part** (DisplayContent.dump prints
   `base=` only when it differs from `init`; 20261009d: `base=1920x1080 640dpi`); `wm density` 640; a sharp
   full-screen picture, no zoom, crop or quarter picture. `log.txt` has the line `OSD: ... -> 3840x2160 (lines 149/150):
   /dev/z9x_uires/UD_VB1_16LANE_CSOT_URSA.ini u:object_r:tv_config_file:s0, tmpfs ro, bound ro`.
   `ls -lZ /dev/z9x_uires /vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini`: both
   `-r--r--r-- root root u:object_r:tv_config_file:s0 21641`. Otherwise read `log.txt`: it must have
   fallen back (the why says where) and, if the check failed, restarted once into 1080p by itself.
2. Mounts (adb root): `grep -e z9x_uires -e ' /dev ' /proc/self/mountinfo`. Expect exactly two `z9x_uires`
   lines, the tmpfs (`/ /dev/z9x_uires ro,relatime ... - tmpfs z9x_uires ro,seclabel,size=576k,mode=755`)
   and the bind (`/UD_VB1_16LANE_CSOT_URSA.ini /vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini
   ro,relatime ... - tmpfs z9x_uires ro,...`), and the `/dev` line itself still **`rw`** in both option
   fields (nothing remounted it). `sh -c 'echo test >> /vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini'`:
   `Read-only file system` (it could only reach the RAM copy; the vendor partition is ext4 `ro`).
3. `dumpsys SurfaceFlinger | grep -A3 'Display Region'`: `3840 x 2160` in the 4th column. `logcat | grep
   'Display Region'` (HWC init): `3840` / `2160`. `logcat | grep Wrapper_Set_OsdInfo` (video):
   `osdWidth: 3840, osdHeight: 2160`.
4. Boot: XGIMI boot logo (bootloader), boot video (`bootvideo`, early-boot, video plane), boot animation: all
   full screen.
5. Keystone (manual and auto), the AK pattern, focus: the GOP line stays `Dst 3840:2160`, the trapezoid
   warp (`vendor.mstar.trapezoid.*`, 4K ARGB path, dual GOP) looks right.
6. Video: YouTube/Kinopoisk (incl. 4K content), HDMI input, PiP/overlays: window position and size, A/V sync.
7. Apps at 640 dpi: launcher, Settings, Projector settings, a few third-party apps; UI smoothness and
   `dumpsys meminfo` (4x the 1080p pixels on the Mali).
8. 1080p: pick it, restart. `state`: `ini: ... 1920x1080, vendor file (no bind mount)`, `ram:
   /dev/z9x_uires not mounted`, `osd=stock`, `1920x1080`, resize 1, 1920,1080 @ 320, no dumpsys in the
   log, no `z9x_uires` line in `/proc/self/mountinfo`; `wm size` expected `Physical size: 1920x1080` and no
   `Override size` (the 3840 cap is above it; not measured yet). Pick 4K again, restart: back to 1.
9. Fallback drill (adb root): `echo "2160 _b" > /metadata/z9x_uires/pending` (your slot), restart. Expect
   1080p, `failed.2160`, no second restart, no bind mount and no `z9x_uires` line in mountinfo. Pick 4K in
   the app: it comes back.
10. Boot time: `log.txt` `up` of the `start on` line and `ro.boottime.*` (midaemon, vendor.hwcomposer-3)
    vs a 1080p start; `/metadata/z9x_rescue/log.txt` has no failed starts.
11. Boot-dark / standby right after boot: `check=wait`, decides once the display is on; never restarts a
    dark projector.
12. Optional, debug 2K: `echo 1440 > /metadata/z9x_uires/debug`, restart: the check tells whether the GOP
    scales 1.5x (`check=ok`) or falls back (`why`); `rm` it afterwards.

## 7. Host test

`sh overlay/v1/z9x_uires/test/run.sh` (also `SH=dash` / `SH=ksh` for the script's shell; the harness
itself also runs under dash and ksh): 86 cases, about 145 s. A fake root has a read-only
`vendor/tvconfig` with `Customer_1.ini` (line 12) and the panel ini in the Z9X's shape (lines 149/150
`osdWidth                = 1920;` / `osdHeight               = 1080;`); stubs stand in for `mount` /
`umount`, `chcon`, `stat -c %C`, `ls -Zd`. The `mount` stub knows only the script's three forms, as toybox
behaved on the Z9X: our tmpfs (a read-write tmpfs line in mountinfo), its `remount,ro` (only of a stub tmpfs
mount point: anything else fails the case, as toybox would remount `/dev`), and a plain bind of a file of
it that inherits the tmpfs's `ro` / `rw` in both option fields (the vendor path reads as the bound file;
the original is kept and restored at every simulated restart); every other form (`bind,ro`,
`remount,bind,ro`, ...) fails the case. `chcon` fails on a read-only filesystem. Every case ends with a
restart and fails if a vendor file differs from the original. The lint allows exactly the tmpfs mount of
`"$ram"` (`$R$RAM`, `RAM=/dev/z9x_uires`), its `remount,ro`, the plain bind and the umounts of the bind and
the tmpfs, and forbids `remount,bind`, `bind,ro`, `remount,rw`, `-o rw` and any write to the vendor file.
Covered: default 4K with the copy checked byte by byte independently of the script (`cmp -l`: only the
digits of lines 149/150 differ; label, 0444 at the remount, the only file there; the exact mount calls in
order; tmpfs and bind `ro` in mountinfo); 1080p (no mount);
picked 4K; debug 2K and its fallback on the real 2K-test GOP line (`1920:1080|5120:2880`, region
1920x1080); 1440 from the app ignored, a stale `want=1440`, stale `failed.*` / `pending` of 20261009b
dropped once (not when the marker cannot be written), `fs` run twice in one start refused; CRLF, no final
newline, other line order and trailing comments, unquoted `m_pPanelName`; missing `Customer_1.ini` / panel ini, bad or duplicate
`m_pPanelName`, missing / duplicate / non-decimal osd lines, a NUL byte (1080p with an awk that cannot carry
it, like the device's one-true-awk and the Mac's; with mawk / gawk, as on the Linux build host, the copy is
byte-exact and checked like any other: integration fix 20261009c), a too large file, the values
already there; label from `ls -Z`, no label, chcon failing, `/dev` not writable; `/dev/z9x_uires` already
a mount point (left alone) or a symlink (nothing mounted), no readable mountinfo (nothing mounted), the tmpfs
mount failing / returning 0 without effect (nothing written, no remount) / mounted but not shown as ours
(nothing written, no remount, unmounted again; its umount failing is said) / full, its remount failing /
returning 0 but still `rw`, the bind failing / not in effect (no umount of the panel ini, also with a mount
of another's there) /
read-write (unmounted with the tmpfs, never remounted) / read-write with the tmpfs not unmountable /
read-write and not unmountable (mode follows the mount), every failure after the tmpfs mount leaving no
mount line and no copy, a cleanup umount failing; the Z9X's real mountinfo lines (the read-write bind of the
old method: refused; the working tmpfs and bind lines: accepted; `ro` only in the mount's or only in the
superblock's options: accepted; no optional fields; look-alikes such as `rootcontext=ro`: refused); the
Display Region check with the real lines (the 1080p HWC row `3840 x 2160 | 3840 x
2160 | 60 | 1920 x 1080`, the GOP lines `1920:1080|3840:2160`, `3840:2160|7680:4320`), the real dump file
as a 4K start, region-only mismatch, missing HWC table; and every 1.0.1-20261009b fallback case (pending,
failed boot, hung start, transient mismatch, dual GOP, display off, OTA busy, unrecordable state, kill
switches, data wipe, re-pick).
