package org.z9x.projector.report;

import java.io.IOException;

/** The user canceled the running report (Back / Cancel); the worker stops and deletes the report files. */
final class CanceledException extends IOException {
    CanceledException() {
        super("canceled");
    }
}
