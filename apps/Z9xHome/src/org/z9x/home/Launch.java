package org.z9x.home;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.z9x.home.data.AppSource;
import org.z9x.home.data.Card;
import org.z9x.home.data.InputSource;
import org.z9x.home.data.IntentGuard;
import org.z9x.home.proj.ProjectorBridge;

/** Opens what a card stands for. Untrusted program intents always go through IntentGuard. */
public final class Launch {
    private Launch() {}

    /** Returns false if the card needs UI of the caller (cast how-to, features, customize). */
    public static boolean open(Context c, Card k) {
        if (k == null) return true;
        try {
            switch (k.kind) {
                case Card.APP: {
                    ComponentName cn = ComponentName.unflattenFromString(k.intent);
                    if (cn == null) return true;
                    Intent i = new Intent(Intent.ACTION_MAIN).setComponent(cn)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    start(c, i, "app " + cn.getPackageName());
                    return true;
                }
                case Card.PROGRAM: {
                    Intent i = IntentGuard.forRow(c, k.intent, k.pkg);
                    if (i == null) i = IntentGuard.appLaunch(c, k.pkg);
                    if (i != null) start(c, i, "program pkg=" + k.pkg);
                    return true;
                }
                case Card.INPUT: {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(k.intent)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (InputSource.TVINPUT_PKG.equals(k.pkg)) i.setPackage(InputSource.TVINPUT_PKG);
                    start(c, i, "input " + k.id);
                    return true;
                }
                case Card.TILE:
                    if ("customize".equals(k.intent)) return false;
                    ProjectorBridge.showPanel(c, k.intent);
                    return true;
                case Card.MORE_APPS: {
                    Intent i = IntentGuard.appLaunch(c, AppSource.PKG_PLAY);
                    if (i != null) start(c, i, "play store");
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.w(App.TAG, "open " + k.id + ": " + t);
            Toast.makeText(c, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    public static void openApp(Context c, String pkg) {
        Intent i = IntentGuard.appLaunch(c, pkg);
        if (i != null) start(c, i, "app " + pkg);
    }

    public static void start(Context c, Intent i, String what) {
        try {
            c.startActivity(i, ActivityOptions.makeBasic().toBundle());
            Log.i(App.TAG, "launch " + what);
        } catch (Throwable t) {
            Log.w(App.TAG, "launch " + what + " failed: " + t);
            Toast.makeText(c, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }
}
