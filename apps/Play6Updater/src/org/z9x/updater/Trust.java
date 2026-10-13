// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Trust anchors: the certificates in /system/etc/security/otacerts.zip (Lumen "ota" first, then the
 * spare "ota_next" for one key rotation; written by tools/sign/sign_tar.py). The same file
 * update_engine uses for the payload signatures.
 */
public final class Trust {
    private Trust() {}

    public static List<X509Certificate> otaCerts(String path) throws IOException {
        List<X509Certificate> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory()) continue;
                try (InputStream in = z.getInputStream(en)) {
                    out.add((X509Certificate) cf.generateCertificate(in));
                } catch (Exception ex) {
                    // not a certificate: ignore this entry
                }
            }
        } catch (java.security.cert.CertificateException e) {
            throw new IOException(e);
        }
        if (out.isEmpty()) throw new IOException("no certificate in " + path);
        return out;
    }

    /** RSA PKCS#1 v1.5 SHA-256 signature over the exact bytes, by any of the OTA certificates. */
    public static boolean verify(byte[] data, byte[] sig, List<X509Certificate> certs) {
        for (X509Certificate c : certs) {
            try {
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initVerify(c.getPublicKey());
                s.update(data);
                if (s.verify(sig)) return true;
            } catch (Exception ignored) {
                // try the next certificate
            }
        }
        return false;
    }

    public static boolean verifyWithSystemCerts(byte[] data, byte[] sig) {
        try {
            return verify(data, sig, otaCerts(Ota.OTACERTS));
        } catch (IOException e) {
            return false;
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    /** sha256 of a file prefix (or the whole file with len < 0). */
    public static String sha256(File f, long len, Interrupt stop) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[1 << 20];
        long left = len < 0 ? Long.MAX_VALUE : len;
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                md.update(buf, 0, n);
                left -= n;
                if (stop != null && stop.stopped()) throw new IOException("stopped");
            }
        }
        if (len >= 0 && left != 0) throw new IOException("short file " + f);
        return hex(md.digest());
    }

    public interface Interrupt { boolean stopped(); }
}
