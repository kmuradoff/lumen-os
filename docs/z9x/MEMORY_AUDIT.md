# Z9X memory audit (owner device, Lumen 1.0.7)

Date: 2026-10-30. Device: owner Z9X (horse_popular, G0082), Lumen OS 1.0.7 (20261028anp),
adb <lan-ip>:5555. Uptime at capture: 16 h 31 min (59436 s).
Method: read-only (cat /proc, dumpsys meminfo/activity/procstats, getprop, logcat -d, zoneinfo,
dma-buf bufinfo). Nothing was changed on the device. A film was paused in com.xgimiru.player
during the capture; it was not touched.

Question from the owner: "Of 2.8 GB only 176 MB is free. Is something wrong for a fresh system?"

Short answer: no. "Free" on Linux/Android means "not used for anything at all", and the kernel
deliberately keeps that number small by filling spare RAM with file cache. The number that
matters is "available": about 875 MB right now, plus about 195 MB of cached apps that Android
kills on demand. No leak was found after 16 h, there were no out-of-memory kills, no direct
reclaim stalls, and Lumen's own apps stay inside their usual ranges.

## 1. Where the 3 GB goes

The board has 3 GiB of DRAM (kernel command line: `mmap_filename=mmap_mt5877_mtk_3g_cd_an_iommu`).

| Layer | MiB | Source |
|---|---:|---|
| Physical DRAM | 3072 | /proc/zoneinfo: present pages 360448 + 163840 + 262144 |
| Withheld at boot (vendor carveouts, kernel image, page tables for RAM) | 284 | present minus managed pages (DMA32 227, Movable 57) |
| What Android sees as "Total" | 2788 | MemTotal 2854904 kB (this is the owner's "2.8 GB") |

The 284 MiB is decided by the XGIMI/MediaTek bootloader and device tree, not by Lumen. Lumen keeps
the stock kernel, dtb and vendor, so a stock Z9X shows the same MemTotal. The no-map regions in the
device tree (`/sys/firmware/devicetree/base/reserved-memory`) include hw_reserved_1 (96 MiB at
0x40000000), hw_reserved_2 (about 92 MiB at 0x78600000), hw_reserved_3 (about 2 MiB),
reserved_disp_gce (4 MiB), ramoops (1 MiB) and cma_23 (8 MiB); the rest is the kernel image and
the struct page array (about 48 MiB for 3 GiB). These hold video decoder, frame rate conversion
(FRC/MEMC) and display engine buffers and cannot be given back.

## 2. Where the 2788 MiB goes (snapshot)

/proc/meminfo, dumpsys meminfo summary:

| Bucket | MiB | Meaning |
|---|---:|---|
| MemFree | 183 | Truly unused. The owner's "176" is this number at another moment. |
| Page cache (Cached + Buffers) | 999 | Copies of files (app code, libraries, media). Reclaimable instantly. |
| MemAvailable | 874 | What a new app can get without killing anything (free + reclaimable cache). |
| Cached apps (PSS) | 195 | YouTube 145, byedpi 30, providers.tv 10, org.z9x.updater 10. Killed on demand. |
| Running apps and services (PSS) | 945 | Everything that is visible, persistent or native (table 3). |
| Kernel | 691 | dumpsys "kernel used": slab, page tables, vmalloc, stacks, graphics/video buffers. |
| Lost RAM (dumpsys cannot attribute) | 208 | Mostly vendor video/graphics buffers and vmalloc that are not mapped into a process. |
| zram (swap in RAM, compressed) | 81 | 436 MB of idle app memory stored in about 81 MB of RAM (about 5:1). |

Kernel detail (kB): Slab 181268 (SUnreclaim 118136, SReclaimable 63132), PageTables 54904,
KernelStack 28528, ShadowCallStack 7148, VmallocUsed 154568, Shmem 18356, Iommu 184648,
CmaTotal 24576 (CmaFree 572). Swap: 819196 total, 383036 free.

Video and graphics buffers (dma-buf, /sys/kernel/debug/dma_buf/bufinfo): 259 objects, 356.5 MiB.

| Heap | MiB | What it is |
|---|---:|---|
| mtk_carveout-uncached | 96.8 | Video pipeline carveout (likely inside hw_reserved_1/2, outside MemTotal) |
| mtk_system-uncached | 76.8 | Decoder and display buffers from normal RAM |
| mtk_xc_frc_l_uncached | 62.3 | MEMC / frame rate conversion frame store |
| mtk_mali_gop_uncached | 41.0 | GPU and on-screen display (OSD) framebuffers |
| mtk_xc_main_uncached | 25.3 | Scaler main window |
| mtk_iommu | 23.7 | GPU and app graphic buffers |
| mtk_xc_frc_pq_uncached | 9.2 | FRC picture quality |
| others (system, vdec_fb, nullmem) | 21.4 | Small buffers |

About 180 MiB of these (system heaps) sit inside MemTotal and match the meminfo "Iommu" line; about
190 MiB (carveout, FRC, scaler) very likely come from the vendor reserved regions. They exist
because a film is open in the player; the same buffers exist on stock.

## 3. Who uses the most (PSS, MB, with 24 h procstats min-avg-max)

| Process | PSS now | 24 h range | Kind | Comment |
|---|---:|---|---|---|
| system_server | 166 | n/a | Android core | Normal for Android 14 TV (Java 31, code 64). |
| com.google.android.gms | 149 | 54-118-155 | Google Play services | Largest optional consumer. |
| com.google.android.youtube.tv | 148 | n/a | cached app | Reclaimed automatically when needed. |
| com.xgimiru.player | 114 | 78-112-155 | foreground app | The paused film. |
| com.android.systemui | 69 | 45-60-68 | Android core | Normal. |
| org.z9x.home | 64 | 53-76-117 | Lumen launcher | Native heap 23 MB, graphics 7.7 MB (posters/thumbnails). |
| org.z9x.projector | 42 | 24-38-55 | Lumen, persistent | Native heap 16 MB, 144 views kept with 0 activities, 39 app contexts. |
| com.android.inputmethod.leanback | 39 | n/a | keyboard | Kept as current IME, normal. |
| org.z9x.tvinput | 33 | 9-24-33 | Lumen, persistent | At the top of its range, inside it. |
| io.github.romanvht.byedpi | 31 | n/a | user app (DPI bypass) | Owner's choice. |
| surfaceflinger + mtkhwcomposer | 55 | n/a | display | Normal. |
| com.android.bluetooth | 29 | n/a | Android core | Normal. |
| gmpf_main | 24 | n/a | XGIMI vendor daemon | Stock projector daemon, needed. |
| aiaq_audio (2 procs) + aivs_audio | 26 | n/a | XGIMI vendor audio/AI-PQ | Stock, needed for sound/picture. |
| com.google.android.tv.remote.service | 17 | n/a | Google | Phone remote, optional. |
| org.z9x.updater | 10 | 7-8-9 | Lumen, cached | Fine. |
| org.z9x.airplay | 9 | n/a | Lumen, foreground service | Fine. |

Totals by group: native daemons 397 MB, persistent apps 332, persistent services 49, foreground
114, visible 180 (mostly Google), perceptible 49, home 64, cached 200.

All Lumen apps together (org.z9x.*): about 159 MB PSS, of which 116 MB is always resident
(projector, tvinput, airplay) and 64 MB is the launcher.

## 4. Health checks after 16 h

| Check | Value | Verdict |
|---|---|---|
| oom_kill (/proc/vmstat) | 0 | No kernel OOM. |
| allocstall_*, pgsteal_direct | 0 | No app ever had to wait for memory reclaim. |
| PSI memory avg10/60/300 | 0.00 | No pressure now. |
| procstats memory state, 12 h 20 min | Normal 12 h 11 min, Moderate 4 min 53 s, Low 20 s, Critical 3 min 27 s | Short peaks (installs at 16:14, app starts). |
| am_kill in the event log | 68, all "empty #6/#7" | Background empty processes trimmed by the cached cap (by design). No lmkd kills of visible apps. |
| Lumen apps growth | projector 42 (avg 38, max 55), home 64 (avg 76, max 117), tvinput 33 (max 33), updater 10 | No leak. Values move up and down with use. |
| Earlier owner snapshot (1.0.1e, 4K test) | MemFree 316, MemAvailable 483, SwapFree 317 MB | 1.0.7 now: MemAvailable 874, SwapFree 374. Better than 1.0.1. |

Memory tuning currently active (from Lumen files):
- `vm.swappiness 100` (overlay/v1/z9x_speed.rc): prefer zram for idle app memory. This is why swap
  is half used; it is intended and cheap on this hardware compressor (mzc_hybrid).
- lmkd: `ro.lmk.swap_free_low_percentage 10`, `thrashing_limit 30`, `psi_complete_stall_ms 700`,
  minfree levels 72/90/108/126/216/315 MB.
- Cached process cap 10 (`config_customizedMaxCachedProcesses` in Z9xFrameworkKeysOverlay):
  dumpsys shows CUR_MAX_CACHED_PROCESSES=10, CUR_MAX_EMPTY_PROCESSES=5. Note: the comments in
  z9x_speed.rc and tools/lumen_v1.sh still say 16; the value that ships is 10.
- App freezer on (procstats shows 344 MB of frozen cached apps over the day).

Unrelated finding: wpa_supplicant writes `mtk_cfg_get_txpower ... STA doesn't support this
function` to the kernel log every 3 s (the normal Android Wi-Fi signal poll calls a function the
MediaTek driver does not implement). It costs no memory, but it pushes the early boot lines
(including the vendor "Reserved memory" table) out of dmesg within minutes, so they could not be
quoted here.

## 5. Recommendations, ranked by MB that could be saved

None of these is required: the system is healthy. They are options for the owner.

| # | Change | Saves (MB) | Type | Risk / note |
|---|---|---:|---|---|
| 1 | Use the no-Google image, or disable "Google Play services" related extras if Google is not needed | 150-310 | setting / image choice | Breaks Play Store, Google cast to the phone remote, and some apps. Not advised for the owner (YouTube, Play). |
| 2 | Launcher (org.z9x.home): drop poster/thumbnail bitmap cache in onTrimMemory and when another app goes to the foreground; cap the in-memory image cache | 20-50 (peak 117 to about 60) | Z9X files (apps, launcher) | Low. Posters reload from disk cache when returning home. |
| 3 | org.z9x.projector: release inflated panel views when a panel is closed instead of keeping 144 views, and check what creates 39 app contexts in a service with no activity (likely a context per panel or per binder call) | 10-20 | Z9X files (apps/Z9xProjector) | Low. Panels open a few ms slower the first time. Worth a heap dump first to confirm. |
| 4 | Turn off Google TV remote service (com.google.android.tv.remote.service) if the phone remote app is not used | 17 | setting (Apps, disable) | Phone remote stops working. |
| 5 | org.z9x.airplay: start the AirPlay receiver only when AirPlay is switched on, instead of a permanent foreground service | about 9 | Z9X files | Medium: discovery from iPhone must still work; must be tested. |
| 6 | org.z9x.tvinput: it is persistent and at the top of its range (33 MB). Check whether it needs to be persistent at all or can start with the HDMI/TV input | 10-30 | Z9X files | Medium: HDMI auto-switch and CEC must keep working. Investigate before changing. |
| 7 | Do not change: swappiness 100, lmkd values, cached cap 10, vendor carveouts. watermark experiment E2 stays off | 0 | none | Changing these would make free RAM look bigger but make app switching slower. |
| 8 | Fix the stale "16 cached processes" comments in overlay/v1/z9x_speed.rc and tools/lumen_v1.sh to say 10 | 0 | Z9X files (comments only) | None. |

The owner's 176 MB "free" will stay small whatever is done: any RAM that becomes free is used as
file cache again within minutes, which is what makes apps start fast. The correct numbers to show
the owner are "available" (about 0.9 GB) and "used by apps" (about 1.1 GB).

## Raw capture

Saved on the Mac in the session scratchpad (not in the repo): meminfo, dumpsys meminfo (all and per
Lumen app), procstats 24 h, activity processes and LRU, zram mm_stat, getprop lmk/dalvik, zoneinfo,
dma-buf bufinfo, reserved-memory node list.
