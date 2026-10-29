Lumen OS 1.0.8 bridges (the org.z9x -> org.lumen package rename)

Up to 1.0.7 our apps were called org.z9x.*; from 1.0.8 they are org.lumen.*. Android treats a new package name as a
new app (new uid, empty data folder) and wipes the data of a system package that is no longer in the image. So
1.0.8 is a bridge release:

- the apps themselves are org.lumen.* (one name on every device, no <original-package>: global_keys.xml and the
  other system XML can name only one package per entry);
- under each old name the image carries a bridge APK built from this folder (build_bridges.sh): same package name
  and signing key as the old app, versionCode 900000 (above the old copies 104 on the Z9X and 600000 + N on the
  Play 6), one read-only provider "org.z9x.<app>.bridge" and nothing else. Android keeps the old uid and data;
- each new app copies the old files once, first thing in Application.attachBaseContext
  (apps/common/LumenImport.java, copied into each app by apps/common/sync.sh item migrate): device-protected
  storage at once, credential-protected storage once the user is unlocked, only files it does not have yet,
  "org.z9x." -> "org.lumen." inside shared_prefs (component names, TV input ids, favorites). Copy, never move:
  a rollback to the old slot still finds its data;
- Lumen Home's saved network passwords are encrypted with a keystore key of the old uid: the Home bridge serves
  shared_prefs/net_servers.xml with each password decrypted ("pwc"), and the new Home encrypts it again with its
  own key at once (NetServers.reencryptImported);
- the projector app rewrites the Secure settings that name our components once (screensavers, HDMI input labels
  and hidden inputs, the assistant: LumenImport.migrateSystemSettings).

Who may read a bridge: only org.lumen.<same app>, signed with the bridge's own key (BridgeProvider.enforceCaller).

Bridges per projector:
  Z9X     projector home setup tvinput updater airplay          tools/lumen_v1.sh, tools/lumen/lumen_checks.py APKS
  Play 6  projector home setup tvinput (+ updater with --ota,   tools/play6/build_play6.sh, build_test.sh, compose.sh
          system uid as apps/Play6Updater)

The OTA channel serves 1.0.8 to every pre-1.0.8 device first, so each of them passes through the bridge once. Keep
the bridges in later images as long as a device may still jump from 1.0.7 straight to a newer image (they are
about 17 KB each).
