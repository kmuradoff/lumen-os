package org.z9x.projector.sys;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Lumen OS 1.0.5: keeps Google's Cast receiver (mediashell, only with the Google add-on) out of the cached
 * apps freezer. Its CastReceiverService is a plain started service, so after a while Android 14 caches and
 * freezes the process: it then stops answering mDNS (no _googlecast._tcp, phones do not see the projector)
 * and port 8009 fills with connections nobody accepts. A binding from this persistent process keeps it
 * uncached. BIND_NOT_FOREGROUND: it stays in the background CPU group, it is only never frozen.
 * Without mediashell (the edition without Google) this does nothing.
 */
public final class CastKeeper {
    private static final String TAG = "Z9xCastKeeper";
    private static final String PKG = "com.google.android.apps.mediashell";
    private static final ComponentName SERVICE = new ComponentName(PKG, PKG + ".CastReceiverService");
    private static final long REBIND_MS = 10_000;

    private static Context sCtx;
    private static boolean sBound;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { Log.i(TAG, "cast receiver held"); }
        @Override public void onServiceDisconnected(ComponentName n) { Log.w(TAG, "cast receiver died, Android restarts it"); }
        @Override public void onBindingDied(ComponentName n) { Log.w(TAG, "binding died"); rebindLater(); }
        @Override public void onNullBinding(ComponentName n) { Log.i(TAG, "cast receiver held (null binding)"); }
    };

    private CastKeeper() { }

    public static void install(Context ctx) {
        sCtx = ctx.getApplicationContext();
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        sCtx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getData() != null && PKG.equals(i.getData().getSchemeSpecificPart())) rebindLater();
            }
        }, f, Context.RECEIVER_EXPORTED);
        bind();
    }

    private static void rebindLater() {
        H.removeCallbacksAndMessages(null);
        H.postDelayed(CastKeeper::bind, REBIND_MS);
    }

    private static void bind() {
        try {
            if (sBound) { sCtx.unbindService(CONN); sBound = false; }
        } catch (Throwable t) { sBound = false; }
        try {
            Intent i = new Intent("com.google.cast.action.BIND").setComponent(SERVICE);
            sBound = sCtx.bindService(i, CONN, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);
            if (!sBound) { try { sCtx.unbindService(CONN); } catch (Throwable ignored) { } }
            Log.i(TAG, sBound ? "binding to the cast receiver" : "no cast receiver on this system");
        } catch (Throwable t) {
            Log.w(TAG, "bind: " + t);
        }
    }
}
