package org.z9x.home.data;

import org.z9x.home.usb.UsbApks;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Install from USB (1.0.1): which files of a stick are offered (root, apks/, lumen-os/, one level deep). */
final class UsbApksTest {
    /** Own run (tools/run_tests.sh): android.jar first, UsbApks links PackageManager classes. */
    public static void main(String[] a) throws Exception {
        run();
        System.out.println("usb tests passed=" + T.passed + " failed=" + T.failed);
        System.exit(T.failed == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        T.ok(UsbApks.candidate("Kodi.apk"), "plain .apk");
        T.ok(UsbApks.candidate("FDROID.APK"), "upper-case .APK");
        T.ok(!UsbApks.candidate("._Kodi.apk"), "macOS resource fork ._ file");
        T.ok(!UsbApks.candidate(".hidden.apk"), "hidden file");
        T.ok(!UsbApks.candidate("bundle.apks"), ".apks bundle (not in 1.0.1)");
        T.ok(!UsbApks.candidate("app.xapk"), ".xapk");
        T.ok(!UsbApks.candidate(".apk"), "bare extension");
        T.ok(!UsbApks.candidate(null), "null");

        File root = Files.createTempDirectory("usbapks").toFile();
        try {
            put(root, "b.apk", 3);
            put(root, "A.APK", 3);
            put(root, "._b.apk", 3);
            put(root, "empty.apk", 0);
            put(root, "notes.txt", 3);
            put(root, "apks/c.apk", 3);
            put(root, "apks/deeper/d.apk", 3);
            put(root, "lumen-os/e.apk", 3);
            put(root, "other/f.apk", 3);
            new File(root, "dir.apk").mkdirs();
            List<String> got = new ArrayList<>();
            String rp = root.getAbsolutePath() + "/";
            for (File f : UsbApks.files(root)) got.add(f.getAbsolutePath().substring(rp.length()));
            T.eq(String.join(",", got), "A.APK,b.apk,apks/c.apk,lumen-os/e.apk", "files of a stick");
        } finally {
            delete(root);
        }
    }

    private static void put(File root, String rel, int size) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[size]);
        }
    }

    private static void delete(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) delete(c);
        f.delete();
    }
}
