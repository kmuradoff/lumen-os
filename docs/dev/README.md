# Notes for maintainers

Users need only the [install guide](../install/en.md) and the [changelog](../../CHANGELOG.md). The notes
here are for people who build, sign or publish Lumen OS.

- [ota.md](ota.md): how updates work: signing, slots, the boot gate and the boot rescue.
- [release.md](release.md): building, signing and publishing a release.
- [keys.md](keys.md): the key set, certificate fingerprints and key rotation.
- [publishing.md](publishing.md): how this repository is exported from the working tree.
- [uires.md](uires.md): the UI resolution switch. Lumen OS 1.0.1 ships with 1080p only
  (`ro.z9x.uires.allow=0`).
- [changes/](changes/): engineering changelogs of the apps, the system files and the OTA tools.
