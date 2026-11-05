#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_release.py: the publish audit of a device of tools/devices/ota, run after EVERY publish (read only;
the design notes).

    check_release.py --device CODENAME [--root DIR]

Uses the GitHub CLI ($GH, else gh on PATH; logged in) on the repo of devices/<codename>/ota.conf. Exit 1 on any
of these:
  - releases/latest is not a Z9X release, z9x-<x.y.z> (v<x.y.z> up to 1.0.7) (a device's pre-release published as
    Latest would make every Z9X up to 1.0.7 report "up to date"), or it is a pre-release or a draft;
  - releases/latest's update-public-nogms.json and .sig are missing or do not verify with
    installer/certs/ota.x509.pem, or that manifest is not the Z9X's (device z9x, channel public-nogms);
  - the Z9X update channel (images from 1.0.8 on read it): the tag z9x missing while Latest is a z9x-<x.y.z>
    release; z9x not a pre-release, or a draft; on z9x an asset other than update-public-nogms.json and .sig, a
    manifest that does not verify with installer/certs/ota.x509.pem, is not device z9x channel public-nogms, or
    lists a package or side-car that is not an asset of z9x-<version> with the manifest's size; the manifest
    on Latest not byte-identical to the one on z9x (images up to 1.0.7 and from 1.0.8 must be offered the same);
    a z9x-<x.y.z> release that is a draft;
  - a release of the device (the manifest tag, or <package_tag> of any version) that is a draft, the manifest tag
    when it is not a pre-release (a version release may be a normal release since the maintainer's HB 1.0.8, 2026-11-05,
    but never Latest), or one that carries an asset named update-stable*, update-public* (the Z9X manifests);
  - on the manifest tag: an asset other than update-<channel>.json and .sig of a registry channel, a manifest
    without its .sig, one that fails verify_manifest.check with devices/<codename>/certs/ota.x509.pem (device and
    channel = the tag's device and the file's channel, O1 version code, package URLs https on the version's tag),
    or a package or side-car that is not an asset of that version's release with the manifest's size;
  - on the manifest tag: a manifest (or its .sig) of a channel other than stage_channel while
    devices/<codename>/install/OTA_RESULTS.tsv does not record T5, T6 and T7 as passed;
  - the manifest tag is missing while a version release exists.
Nothing is uploaded, edited or deleted.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402
import verify_manifest as vm  # noqa: E402

# the Z9X release pointer this audit guards (tools/ota/make_manifest.py public-nogms, installer/certs/ota.x509.pem):
# z9x-<version> from 1.0.8 on, v<version> up to 1.0.7
Z9X_TAG = re.compile(r"(v|z9x-)\d+\.\d+\.\d+")
# the Z9X update channel: a fixed pre-release holding only the signed manifest (tools/lumen_v1.sh MANIFEST_BASE)
Z9X_CHANNEL_TAG = "z9x"
Z9X_PKG_TAG = re.compile(r"z9x-(\d+\.\d+\.\d+)")
Z9X_MANIFEST = "update-public-nogms.json"
Z9X_CERT = "installer/certs/ota.x509.pem"
Z9X_NAMES = re.compile(r"update-(stable|public)")


class Gh:
    def __init__(self, repo):
        self.repo = repo
        self.gh = os.environ.get("GH") or shutil.which("gh")
        if not self.gh:
            raise ota_conf.OtaError("gh not found (GitHub CLI)")

    def run(self, *args):
        r = subprocess.run([self.gh, *args], capture_output=True, text=True)
        if r.returncode:
            raise ota_conf.OtaError("gh %s: %s" % (" ".join(args[:2]), (r.stderr or r.stdout).strip()[-300:]))
        return r.stdout

    def latest(self):
        return json.loads(self.run("api", "repos/%s/releases/latest" % self.repo))

    def releases(self):
        out = self.run("api", "--paginate", "repos/%s/releases?per_page=100" % self.repo, "--jq",
                       ".[] | {tag_name, prerelease, draft, assets: [.assets[] | {name, size}]}")
        return [json.loads(x) for x in out.splitlines() if x.strip()]

    def download(self, tag, names, d):
        args = ["release", "download", tag, "--repo", self.repo, "--dir", d, "--clobber"]
        for n in names:
            args += ["--pattern", n]
        self.run(*args)


def audit(dev, gh, errs, notes):
    c = dev["ota"]
    tmp = tempfile.mkdtemp(prefix="lumen-release-")
    try:
        lt = gh.latest()
        tag = lt.get("tag_name", "")
        if not Z9X_TAG.fullmatch(tag) or lt.get("prerelease") or lt.get("draft"):
            errs.append("releases/latest is %r (prerelease %s, draft %s): it must stay the Z9X release (z9x-<x.y.z>; v<x.y.z> up to 1.0.7)"
                        % (tag, lt.get("prerelease"), lt.get("draft")))
        else:
            notes.append("releases/latest = %s (Z9X)" % tag)
            names = [x.get("name") for x in lt.get("assets", [])]
            if Z9X_MANIFEST not in names or Z9X_MANIFEST + ".sig" not in names:
                errs.append("releases/latest %s has no %s and .sig" % (tag, Z9X_MANIFEST))
            else:
                d = os.path.join(tmp, "latest")
                os.makedirs(d)
                gh.download(tag, [Z9X_MANIFEST, Z9X_MANIFEST + ".sig"], d)
                mf = os.path.join(d, Z9X_MANIFEST)
                if not vm.signature_ok(mf, mf + ".sig", os.path.join(dev["root"], Z9X_CERT)):
                    errs.append("releases/latest/%s does not verify with %s" % (Z9X_MANIFEST, Z9X_CERT))
                else:
                    m = json.load(open(mf, encoding="utf-8"))
                    if m.get("device") != "z9x" or m.get("channel") != "public-nogms":
                        errs.append("releases/latest/%s is device %r channel %r, not the Z9X's"
                                    % (Z9X_MANIFEST, m.get("device"), m.get("channel")))
                    else:
                        notes.append("releases/latest/%s verifies with %s (Z9X %s)" % (Z9X_MANIFEST, Z9X_CERT, m.get("version")))
        rels = {r["tag_name"]: r for r in gh.releases()}
        z9x_channel(dev, gh, rels, tag if Z9X_TAG.fullmatch(tag) else "", tmp, errs, notes)
        ptag = re.compile(re.escape(c["package_tag"]).replace(re.escape("{version}"), r"(.+)"))
        mine = {t: r for t, r in rels.items() if t == c["manifest_tag"] or ptag.fullmatch(t)}
        for t, r in sorted(mine.items()):
            # the manifest tag is always a published pre-release; a version release (package_tag) may be a normal
            # release since the maintainer's HB 1.0.8 (2026-11-05), but never a draft, and never Latest (checked above)
            if r.get("draft") or (t == c["manifest_tag"] and not r.get("prerelease")):
                errs.append("%s: prerelease %s, draft %s (%s)" % (t, r.get("prerelease"), r.get("draft"),
                            "the manifest tag of %s is always a published pre-release" % dev["name"]
                            if t == c["manifest_tag"] else "a release of %s is never a draft" % dev["name"]))
            for a in r.get("assets", []):
                if Z9X_NAMES.match(a["name"]):
                    errs.append("%s: asset %s is a Z9X manifest name" % (t, a["name"]))
        notes.append("%d release(s) of %s: %s" % (len(mine), dev["name"], " ".join(sorted(mine)) or "none"))
        mt = mine.get(c["manifest_tag"])
        if mt is None:
            if len(mine):
                errs.append("no %s release, but version releases exist" % c["manifest_tag"])
            return
        allowed = {"update-%s.json%s" % (ch, s) for ch in dev["channels"] for s in ("", ".sig")}
        names = {a["name"]: a for a in mt.get("assets", [])}
        for n in sorted(names):
            if n not in allowed:
                errs.append("%s: asset %s is not update-<channel>.json(.sig) of %s" % (c["manifest_tag"], n,
                                                                                       " ".join(dev["channels"])))
        for ch in sorted({n[len("update-"):].split(".json")[0] for n in names if n in allowed}):
            try:
                ota_conf.channel_open(dev, ch)
            except ota_conf.OtaError as e:
                errs.append("%s: update-%s.json (or its .sig) is on the tag: %s" % (c["manifest_tag"], ch, e))
        for n in sorted(x for x in names if x.endswith(".json") and x in allowed):
            if n + ".sig" not in names:
                errs.append("%s: %s has no .sig" % (c["manifest_tag"], n))
                continue
            d = os.path.join(tmp, "m-" + n)
            os.makedirs(d)
            gh.download(c["manifest_tag"], [n, n + ".sig"], d)
            mf = os.path.join(d, n)
            e2, m = vm.check(dev, mf, mf + ".sig", ota_conf.tree_cert(dev))
            errs += ["%s/%s: %s" % (c["manifest_tag"], n, e) for e in e2]
            if e2:
                continue
            vt = c["package_tag"].replace("{version}", m["version"])
            vr = mine.get(vt)
            if vr is None:
                errs.append("%s/%s: version %s has no release %s" % (c["manifest_tag"], n, m["version"], vt))
                continue
            assets = {a["name"]: a for a in vr.get("assets", [])}
            for p in m["packages"]:
                for url, size in ((p["url"], p["size"]), (p["metadata_url"], p.get("metadata_size"))):
                    an = url.rsplit("/", 1)[1]
                    if an not in assets:
                        errs.append("%s/%s: %s is not an asset of %s" % (c["manifest_tag"], n, an, vt))
                    elif size is not None and assets[an].get("size") != size:
                        errs.append("%s/%s: %s is %s bytes on %s, the manifest says %s"
                                    % (c["manifest_tag"], n, an, assets[an].get("size"), vt, size))
            notes.append("%s/%s: %s %s (%s), %d package(s) on %s" % (c["manifest_tag"], n, m["channel"], m["version"],
                                                                     m["version_code"], len(m["packages"]), vt))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def z9x_channel(dev, gh, rels, latest, tmp, errs, notes):
    """The Z9X update channel z9x: a published pre-release with only update-public-nogms.json and its .sig, whose
    packages are assets of z9x-<version>; the Latest release carries the same manifest bytes."""
    for t, r in sorted(rels.items()):
        if Z9X_PKG_TAG.fullmatch(t) and r.get("draft"):
            errs.append("%s: a draft (a Z9X release is never a draft)" % t)
    zc = rels.get(Z9X_CHANNEL_TAG)
    if zc is None:
        if Z9X_PKG_TAG.fullmatch(latest):
            errs.append("no %s release, but Latest is %s (images from 1.0.8 on read the tag %s)"
                        % (Z9X_CHANNEL_TAG, latest, Z9X_CHANNEL_TAG))
        else:
            notes.append("Z9X channel: not published (no tag %s)" % Z9X_CHANNEL_TAG)
        return
    if not zc.get("prerelease") or zc.get("draft"):
        errs.append("%s: prerelease %s, draft %s (the Z9X channel is always a published pre-release)"
                    % (Z9X_CHANNEL_TAG, zc.get("prerelease"), zc.get("draft")))
    names = {a["name"]: a for a in zc.get("assets", [])}
    pair = (Z9X_MANIFEST, Z9X_MANIFEST + ".sig")
    for n in sorted(names):
        if n not in pair:
            errs.append("%s: asset %s is not %s or its .sig" % (Z9X_CHANNEL_TAG, n, Z9X_MANIFEST))
    have = [n for n in pair if n in names]
    if len(have) != 2:
        errs.append("%s: %s" % (Z9X_CHANNEL_TAG, "%s without %s" % (have[0], [n for n in pair if n not in names][0])
                                if have else "no %s and .sig" % Z9X_MANIFEST))
        return
    d = os.path.join(tmp, "z9x-channel")
    os.makedirs(d)
    gh.download(Z9X_CHANNEL_TAG, list(pair), d)
    mf = os.path.join(d, Z9X_MANIFEST)
    if not vm.signature_ok(mf, mf + ".sig", os.path.join(dev["root"], Z9X_CERT)):
        errs.append("%s/%s does not verify with %s" % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, Z9X_CERT))
        return
    m = json.load(open(mf, encoding="utf-8"))
    if m.get("device") != "z9x" or m.get("channel") != "public-nogms":
        errs.append("%s/%s is device %r channel %r, not the Z9X's (z9x, public-nogms)"
                    % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, m.get("device"), m.get("channel")))
        return
    vt = "z9x-%s" % m.get("version")
    vr = rels.get(vt)
    if vr is None:
        errs.append("%s/%s: version %s has no release %s" % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, m.get("version"), vt))
        return
    assets = {a["name"]: a for a in vr.get("assets", [])}
    want = "https://github.com/%s/releases/download/%s/" % (dev["ota"]["repo"], vt)
    for p in m.get("packages", []):
        for url, size in ((p.get("url", ""), p.get("size")), (p.get("metadata_url", ""), p.get("metadata_size"))):
            an = url.rsplit("/", 1)[-1]
            if not url.startswith(want):
                errs.append("%s/%s: %s is not on %s" % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, url, want))
            elif an not in assets:
                errs.append("%s/%s: %s is not an asset of %s" % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, an, vt))
            elif size is not None and assets[an].get("size") != size:
                errs.append("%s/%s: %s is %s bytes on %s, the manifest says %s"
                            % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, an, assets[an].get("size"), vt, size))
    notes.append("Z9X channel: %s/%s verifies with %s (Z9X %s, %s), %d package(s) on %s"
                 % (Z9X_CHANNEL_TAG, Z9X_MANIFEST, Z9X_CERT, m.get("version"), m.get("version_code"),
                    len(m.get("packages", [])), vt))
    if latest and latest != Z9X_CHANNEL_TAG and Z9X_MANIFEST in {a.get("name") for a in rels.get(latest, {}).get("assets", [])}:
        ld = os.path.join(tmp, "latest")
        lm = os.path.join(ld, Z9X_MANIFEST)
        if not os.path.isfile(lm):
            os.makedirs(ld, exist_ok=True)
            gh.download(latest, [Z9X_MANIFEST], ld)
        if open(lm, "rb").read() != open(mf, "rb").read():
            errs.append("releases/latest %s/%s differs from %s/%s (images up to 1.0.7 and from 1.0.8 would be offered "
                        "different updates)" % (latest, Z9X_MANIFEST, Z9X_CHANNEL_TAG, Z9X_MANIFEST))
        else:
            notes.append("Z9X channel: releases/latest %s carries the same %s" % (latest, Z9X_MANIFEST))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", required=True)
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    a = ap.parse_args()
    errs, notes = [], []
    try:
        dev = ota_conf.device(os.path.abspath(a.root), a.device, publish=True)
        audit(dev, Gh(dev["ota"]["repo"]), errs, notes)
    except (ota_conf.OtaError, ValueError, KeyError, OSError) as e:
        errs.append(str(e))
    for n in notes:
        print("check_release: " + n)
    for e in errs:
        print("check_release: ERROR " + e)
    print("check_release: " + ("FAILED" if errs else "OK"))
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
