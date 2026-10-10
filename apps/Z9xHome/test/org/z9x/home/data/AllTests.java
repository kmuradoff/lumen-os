package org.z9x.home.data;

/** Runs the plain-JVM tests: sh tools/run_tests.sh */
public final class AllTests {
    public static void main(String[] a) throws Exception {
        IntentGuardTest.run();
        RankerTest.run();
        SnapshotTest.run();
        HeroArtTest.run();
        HeaderContrastTest.run();
        ResolutionTest.run();
        System.out.println("tests passed=" + T.passed + " failed=" + T.failed);
        System.exit(T.failed == 0 ? 0 : 1);
    }
}
