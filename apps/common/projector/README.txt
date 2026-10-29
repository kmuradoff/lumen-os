apps/common/projector: single source of the code and resources that the projector apps of the Lumen OS
devices share (Z9xProjector and the projector app of any other device).

What is shared: every path listed in FILES (relative to the app dir). `sh apps/common/sync.sh APP_DIR
projector` copies them from here into an app; with --check it only reports a copy that differs. The device
HAL clients (src/org/lumen/projector/hal/) are never shared, each device has its own code table;
hal/HidlCaller.java is the separate `hal` item (apps/common/hal).

Rules
- The shared files are owned by the Z9X app. A shared file changes only as part of an owner-approved
  Z9xProjector change: edit it here, then copy it outward with sync.sh into every projector app. Another
  device's app picks the change up at its next build.
- A need of another device alone never edits a shared file: that device forks the file into its own app at
  that moment, the path leaves FILES, and that device tracks its fork against the Z9X file.
- A Z9X change that breaks another device's app is fixed on that device's side. It never blocks or changes a
  Z9X release.
- Copy mode runs only from here outward, by hand, at a shared-code change. Builds and checks run
  `sync.sh --check` only. Z9X release preflight:
      sh apps/common/sync.sh --check apps/Z9xProjector hal projector
- An edit made to a copy instead of here shows up in that check (the copy is newer than this file): move
  it here, never overwrite it with copy mode.

Languages
- The strings files listed in FILES belong to the `common` target of apps/i18n/gen.py: their translations
  are the @common/<file> sections of apps/i18n/langs (never @projector/<file>), and the `projector` target
  skips them. gen.py --write writes their locale files here; then copy them outward with sync.sh.
- Hand-written locale files (values-ru/strings.xml, strings_power.xml, strings_remote.xml,
  strings_source.xml, strings_pair.xml, and strings_pair.xml in every locale dir) are plain files in FILES:
  edit them here.
