package org.lumen.projector.hal;

/** Host stub for test/features: only the exception HbFeatures.require throws (the real class is the transport). */
public class AidlCaller {
    public static final class NotOnHbException extends android.os.RemoteException {
        NotOnHbException(String what) { super(what + ": not on the Z9X HB, nothing sent"); }
    }
}
