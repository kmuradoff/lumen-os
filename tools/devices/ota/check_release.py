#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_release.py: the publish audit of a device of tools/devices/ota, run after EVERY publish (read only;
the design notes).

    check_release.py --device CODENAME [--root DIR]

Uses the GitHub CLI ($GH, else gh on PATH; logged in) on the repo of devices/<codename>/ota.conf. Exit 1 on any
of these:
  - releases/latest is not a Z9X v<x.y.z> release (a device's pre-release published as Latest would make every
    Z9X report "up to date"), or it is a pre-release or a draft;
  - releases/latest's update-public-nogms.json and .sig are missing or do not verify with
    installer/certs/ota.x509.pem, or that manifest is not the Z9X's (device z9x, channel public-nogms);
  - a release of the device (the manifest tag, or <package_tag> of any version) that is not a pre-release, or is
    a draft, or carries an asset named update-stable*, update-public* (the Z9X manifests);
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

# the Z9X release pointer this audit guards (tools/ota/make_manifest.py public-nogms, installer/certs/ota.x509.pem)
Z9X_TAG = re.compile(r"v\d+\.\d+\.\d+")
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
            errs.append("releases/latest is %r (prerelease %s, draft %s): it must stay the Z9X v<x.y.z> release"
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
        ptag = re.compile(re.escape(c["package_tag"]).replace(re.escape("{version}"), r"(.+)"))
        mine = {t: r for t, r in rels.items() if t == c["manifest_tag"] or ptag.fullmatch(t)}
        for t, r in sorted(mine.items()):
            if not r.get("prerelease") or r.get("draft"):
                errs.append("%s: prerelease %s, draft %s (a release of %s is always a published pre-release)"
                            % (t, r.get("prerelease"), r.get("draft"), dev["name"]))
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
