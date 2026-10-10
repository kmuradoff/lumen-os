package org.z9x.home.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import org.z9x.home.App;
import org.z9x.home.R;

/**
 * Status of an Install from USB session (not exported: only our own PendingIntent, which PackageInstaller
 * fills). PENDING_USER_ACTION: the system installer's confirm screen; SUCCESS: a toast and a fresh Apps
 * page; anything else: a toast with the installer's message.
 */
public class UsbInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xHomeUsb";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!UsbInstallActivity.ACTION_RESULT.equals(i.getAction())) return;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String label = i.getStringExtra(UsbInstallActivity.EXTRA_LABEL);
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (label == null) label = pkg == null ? "" : pkg;
        Log.i(TAG, "session status " + st + " " + pkg);
        switch (st) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm == null) break;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (Throwable t) {
                    Log.w(TAG, "confirm screen: " + t);
                    Toast.makeText(c, c.getString(R.string.usb_failed, label, String.valueOf(t.getMessage())),
                            Toast.LENGTH_LONG).show();
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(c, c.getString(R.string.usb_done, label), Toast.LENGTH_SHORT).show();
                try {
                    App.get().repo().refresh("usb", 300);
                } catch (Throwable t) {
                    Log.w(TAG, "refresh: " + t);
                }
                UsbInstallActivity.onResult(pkg);
                break;
            default: {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Log.i(TAG, "cancelled by the user: " + pkg);
                } else {
                    Toast.makeText(c, c.getString(R.string.usb_failed, label, msg == null || msg.isEmpty() ? "#" + st : msg),
                            Toast.LENGTH_LONG).show();
                }
                UsbInstallActivity.onResult(pkg);
            }
        }
    }
}
