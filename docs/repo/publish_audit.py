#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""publish_audit.py: classify every file under gsi/ for the public Lumen OS repository.

READ-ONLY: walks the tree, reads files, writes only the manifest given as argv[2].
    python3 docs/repo/publish_audit.py <gsi dir> docs/repo/PUBLISH_MANIFEST.tsv

Actions:
  PUBLISH        copy as is to repo_path
  PUBLISH_SCRUB  copy to repo_path after removing personal identifiers (tools/ota/check_publish.py
                 must pass on the result)
  REGENERATE     not committed; the repo carries the script/recipe that recreates it
  FETCH          not committed; a pinned git submodule / download
  EXCLUDE        never published (proprietary, personal, images, build output, keys, obsolete)
Content flags (any file): ELF, MTK_BLOB, PRIVATE_KEY, PERSONAL (from check_publish.py), BIG (> 5 MB).
A PUBLISH file with a content flag is downgraded to PUBLISH_SCRUB or EXCLUDE.
Seeded from the v7 design audit (research v7 ota/publish_audit.py), extended for Lumen OS 1.0.
"""
import os
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(HERE)), "tools", "ota"))
import check_publish  # noqa: E402

COLLAPSE = [  # (prefix, action, reason)
    ("build/", "EXCLUDE", "images, signed tars, getvar dumps (serial), member lists"),
    ("backup/", "EXCLUDE", "personal backup of the owner's apps, sdcard folders and settings"),
    ("logs/", "EXCLUDE", "device logs of the owner's projector"),
    ("research/", "EXCLUDE", "XGIMI focus/ToF captures and constants from stock (research only)"),
    ("apps/third_party/boringssl/", "FETCH", "git submodule google/boringssl @ 0.20260929.0 (Apache-2.0)"),
    ("apps/third_party/_downloads/", "EXCLUDE", "download cache"),
    ("apps/third_party/UxPlay/", "FETCH", "git submodule FDH2/UxPlay @ 3dbf7cee (GPL-3.0); our patches in apps/Z9xAirPlay/jni/patches"),
    ("apps/third_party/libplist/", "FETCH", "git submodule libimobiledevice/libplist @ fe3dc34 (LGPL-2.1)"),
    ("apps/third_party/alac/", "FETCH", "git submodule macosforge/alac @ c38887c (Apache-2.0)"),
    ("apps/third_party/android-airplay-server/", "FETCH", "git submodule jqssun/android-airplay-server (GPL-3.0), pinned"),
    ("apps/sdk/system-modules-android-34-jdk21/", "REGENERATE", "apps/sdk/gen_sdk.sh (jlink of Android's java.base)"),
    ("apps/out/", "EXCLUDE", "build output (APKs)"),
    ("overlay/apps_v1/", "EXCLUDE", "build output APKs (release assets only)"),
    ("overlay/apps_v64/", "EXCLUDE", "build output APKs"),
    ("overlay/apps_v65/", "EXCLUDE", "build output APKs"),
    ("overlay/audio/", "EXCLUDE", "copies/derivatives of the vendor audio policy XMLs (generated at boot instead)"),
    ("overlay/v6/", "EXCLUDE", "superseded overlay generation"),
    ("overlay/v61/", "EXCLUDE", "superseded overlay generation"),
    ("overlay/v62/", "EXCLUDE", "superseded overlay generation (+ MTK c2store blobs)"),
    ("overlay/v63/", "EXCLUDE", "superseded overlay generation (+ MTK c2store blobs)"),
    ("overlay/v64/", "EXCLUDE", "superseded overlay generation (+ MTK c2store blobs)"),
    ("overlay/v64-fix/", "EXCLUDE", "superseded"),
    ("overlay/v65/", "EXCLUDE", "superseded by overlay/v1 (history starts at Lumen OS 1.0)"),
    ("overlay/v1/c2store/", "EXCLUDE", "MediaTek proprietary codec2 libraries: NEVER in git or public images (installer 'blobs' step)"),
    ("overlay/v1/c2vndk/", "REGENERATE", "our libcodec2_vndk build (AOSP source + lineage/patches); a release asset, not git"),
    ("apps/Z9xBtPair/", "EXCLUDE", "obsolete helper (folded into Z9xProjector remote auto-pair)"),
]
RULES = [  # (regex on rel path, action, repo_path template, reason)
    (r"(^|.*/)__pycache__/.*|.*\.pyc$", "EXCLUDE", "", "python cache"),
    (r"^aosp_arm64-img-.*\.zip$", "EXCLUDE", "", "Google GSI download"),
    (r"^system\.img$", "EXCLUDE", "", "image"),
    (r"^vbmeta\.img$", "EXCLUDE", "", "Google build artifact; never shipped (vbmeta is the user's manual step)"),
    (r"^apps_installed\.txt$", "EXCLUDE", "", "personal app list"),
    (r"^lineage/gapps/MindTheGapps-.*\.zip$", "EXCLUDE", "", "Google proprietary apps; gapps/README.md gives URL + sha256"),
    (r"^lineage/gapps/.*$", "EXCLUDE", "", "not needed"),
    (r"^lineage/.*\.log$", "EXCLUDE", "", "build logs of the owner's laptop"),
    (r"^lineage/patches/(.*)$", "PUBLISH", "lineage/patches/\\1", "our patch to Apache-2.0 AOSP source"),
    (r"^lineage/(Dockerfile|z9x_build_loop\.sh|z9x_status\.sh|z9x_thermal\.sh|z9x_watch\.sh)$", "PUBLISH_SCRUB", "lineage/\\1", "build environment (BUILDER env var, no host names)"),
    # Lumen OS 1.0 image inputs
    (r"^overlay/v1/keylayout/(.*\.kl)$", "PUBLISH", "system/usr/keylayout/\\1", "key layout tables (Apache-2.0 headed, modified by us)"),
    (r"^overlay/v1/idc/(.*\.idc)$", "PUBLISH", "system/usr/idc/\\1", "own input-device configs"),
    (r"^overlay/v1/bootanimation\.zip$", "REGENERATE", "", "tools/lumen/gen_bootanim.py renders it (release asset)"),
    (r"^overlay/v1/LineageCustomizer\.apk$", "REGENERATE", "",
     "tools/lumen/gen_customizer.py makes it from the base image's LineageCustomizer (Play Movies removed)"),
    (r"^overlay/v1/(.*\.(rc|sh|xml|txt|md))$", "PUBLISH", "system/etc/\\1", "own init scripts / configs / props"),
    (r"^overlay/keylayout/.*$", "EXCLUDE", "", "superseded / original XGIMI copies"),
    (r"^overlay/mtv_core_hardware(\.orig)?\.xml$", "EXCLUDE", "", "vendor permission XML derivative (obsolete)"),
    (r"^overlay/audio_policy_configuration\.xml$", "EXCLUDE", "", "XGIMI-derived audio policy"),
    (r"^overlay/debug/(.*)$", "PUBLISH", "tools/dev/debuglog/\\1", "own USB-stick boot logger (dev builds only)"),
    (r"^overlay/(xgimi_compat|xgimi_compat_extra|hide_xgimi_vendor_apps)\.rc$", "PUBLISH", "system/etc/legacy/\\1.rc", "own init"),
    (r"^overlay/(unmute_system_sounds\.sh|z9x_build_prop\.txt)$", "PUBLISH", "system/etc/legacy/\\1", "own"),
    # keys and certificates
    (r"^apps/sdk/platform\.(pk8|x509\.pem)$", "PUBLISH", "apps/sdk/testkeys/platform.\\1", "PUBLIC AOSP test key, dev builds only (README says so)"),
    (r"^apps/sdk/framework-full-headers\.jar$", "REGENERATE", "", "header jar of our framework build (apps/sdk/gen_sdk.sh)"),
    (r"^tools/sign/release_certs/(.*\.pem|.*\.avbpubkey|(?:.*/)?FINGERPRINTS\.txt)$", "PUBLISH", "keys/public/\\1", "Lumen OS PUBLIC release certificates and APEX public keys (no private key)"),
    (r"^tools/sign/testcerts/(.*\.pem)$", "PUBLISH", "tools/sign/testcerts/\\1", "public AOSP test certificates (keymap.json inputs)"),
    (r"^tools/sign/(.*)$", "PUBLISH", "tools/sign/\\1", "signing tools (keys never in the tree)"),
    (r"^tools/ota/(.*)$", "PUBLISH", "tools/ota/\\1", "OTA tools + image OTA plumbing"),
    (r"^installer/(.*)$", "PUBLISH", "installer/\\1", "PC installer (macOS/Linux/Windows)"),
    (r"^docs/repo/(LICENSE|NOTICE|gitignore|pre-push)$", "PUBLISH", "\\1", "repo root files (gitignore -> .gitignore, pre-push -> .githooks/pre-push)"),
    (r"^docs/(.*)$", "PUBLISH", "docs/\\1", "guides and design docs"),
    # apps
    (r"^apps/(Z9x[A-Za-z]+)/build/.*$", "EXCLUDE", "", "build intermediates"),
    (r"^apps/(Z9x[A-Za-z]+)/[^/]*\.apk$", "EXCLUDE", "", "build output"),
    (r"^apps/Z9xAirPlay/lib/.*\.so$", "EXCLUDE", "", "our native build output (rebuilt by jni/build_native.sh)"),
    (r"^apps/(Z9x[A-Za-z]+)/(.*)$", "PUBLISH", "apps/\\1/\\2", "own app source"),
    (r"^apps/common/(.*)$", "PUBLISH", "apps/common/\\1", "shared sources copied at build time"),
    (r"^apps/(i18n/.*)$", "PUBLISH", "apps/\\1", "own i18n"),
    (r"^apps/(build_apk\.sh|check_global_keys\.py|check_hal_callers\.py)$", "PUBLISH", "apps/\\1", "own build tooling"),
    (r"^apps/(V6[0-9]_[A-Z_]+\.md)$", "PUBLISH_SCRUB", "docs/design/\\1", "design history"),
    # image tools
    (r"^tools/__pycache__/.*$", "EXCLUDE", "", "python cache"),
    (r"^tools/patch_tar\.py\.v5$", "EXCLUDE", "", "old copy"),
    (r"^tools/z9x_v6[0-5]?\.sh$", "EXCLUDE", "", "superseded image scripts (Lumen history starts at 1.0)"),
    (r"^tools/lumen_v1\.sh$", "PUBLISH_SCRUB", "tools/build_system.sh", "image composer (scrub host names)"),
    (r"^tools/lumen/(.*)$", "PUBLISH", "tools/lumen/\\1", "image tooling (boot animation, checks, dexpreopt)"),
    (r"^tools/(patch_tar\.py|ext4_to_tar\.py|compose_tv\.py|assemble_tv\.sh|z9x_patches\.sh)$", "PUBLISH", "tools/\\1", "own image tooling"),
    (r"^tools/(flash_tv\.sh|flash_gsi\.sh|flash_gsi_system\.sh)$", "PUBLISH_SCRUB", "tools/dev/\\1", "dev flash helpers (serial -> argument)"),
    (r"^tools/(backup_apps\.sh|restore_stock\.sh)$", "EXCLUDE", "", "personal (the installer has a generic backup step)"),
]


def main():
    root, out = sys.argv[1], sys.argv[2]
    rows, collapsed = [], {}
    for dp, dns, fns in os.walk(root):
        dns.sort()
        for fn in sorted(fns):
            if fn == ".DS_Store":
                continue
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, root)
            hit = next((c for c in COLLAPSE if rel.startswith(c[0])), None)
            mb = re.match(r"^(apps/[^/]+/build/)", rel)
            if mb and not hit:
                hit = (mb.group(1), "EXCLUDE", "build intermediates")
                if hit not in COLLAPSE:
                    COLLAPSE.append(hit)
            if hit:
                n, sz = collapsed.get(hit[0], (0, 0))
                collapsed[hit[0]] = (n + 1, sz + os.path.getsize(p))
                continue
            action, repo, reason = "REVIEW", "", "no rule matched"
            for rx, a, tmpl, why in RULES:
                m = re.match(rx, rel)
                if m:
                    action, reason = a, why
                    repo = m.expand(tmpl) if tmpl else ""
                    break
            flags = []
            if action.startswith("PUBLISH"):
                blocked = check_publish.check(root, [rel])
                for _, why in blocked:
                    for w in why:
                        if w.startswith("ELF"):
                            flags.append("ELF")
                        elif "MediaTek" in w:
                            flags.append("MTK_BLOB")
                        elif "PRIVATE KEY" in w or w == "key file":
                            flags.append("PRIVATE_KEY")
                        elif w.startswith("personal data") or w == "e-mail address":
                            flags.append("PERSONAL")
                        elif w.startswith("larger"):
                            flags.append("BIG")
                        else:
                            flags.append("BINARY")
                if repo.startswith("apps/sdk/testkeys/"):
                    flags = [f for f in flags if f != "PRIVATE_KEY"]   # the PUBLIC AOSP test key (DEV ONLY)
                flags = sorted(set(flags))
                if {"ELF", "MTK_BLOB", "PRIVATE_KEY", "BINARY"} & set(flags):
                    action, reason = "EXCLUDE", reason + "; blocked by check_publish (" + ",".join(flags) + ")"
                elif "PERSONAL" in flags and action == "PUBLISH":
                    action, reason = "PUBLISH_SCRUB", reason + "; personal identifier found"
            rows.append((rel, action, repo, os.path.getsize(p), ",".join(flags), reason))
    with open(out, "w") as o:
        o.write("# path (relative to gsi/)\taction\trepo_path\tbytes\tflags\treason\n")
        for key, (n, sz) in sorted(collapsed.items()):
            c = next(c for c in COLLAPSE if c[0] == key)
            o.write(f"{key}** ({n} files)\t{c[1]}\t\t{sz}\t\t{c[2]}\n")
        for r in rows:
            o.write("\t".join(str(x) for x in r) + "\n")
    cnt = Counter(r[1] for r in rows)
    print("files:", len(rows), dict(cnt), "collapsed dirs:", len(collapsed))
    rv = [r[0] for r in rows if r[1] == "REVIEW"]
    print("REVIEW:", rv[:60])
    print("flagged:", [(r[0], r[4]) for r in rows if r[4]][:60])
    return 0


if __name__ == "__main__":
    sys.exit(main())
