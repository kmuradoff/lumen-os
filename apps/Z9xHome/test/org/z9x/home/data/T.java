package org.z9x.home.data;

/** Minimal assertion helper so the tests run on the Mac JDK without JUnit (no network, no Gradle). */
final class T {
    static int failed, passed;

    static void ok(boolean c, String what) {
        if (c) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static void eq(Object a, Object b, String what) {
        ok(a == null ? b == null : a.equals(b), what + " (got " + a + ", want " + b + ")");
    }
}
