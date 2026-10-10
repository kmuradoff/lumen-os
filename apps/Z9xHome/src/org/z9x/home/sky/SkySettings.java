package org.z9x.home.sky;

import android.content.Context;
import android.content.SharedPreferences;

/** Live sky choices (SharedPreferences "sky" of org.z9x.home): the scene, "auto" by default. */
public final class SkySettings {
    private SkySettings() {}

    public static final String FILE = "sky";
    private static final String K_SCENE = "scene";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** "auto" or one of {@link SkyView#sceneIds()}. */
    public static String scene(Context c) {
        String s = sp(c).getString(K_SCENE, SkyView.AUTO);
        return s != null && SkyLook.sceneIndex(s) >= 0 ? s : SkyView.AUTO;
    }

    public static void setScene(Context c, String id) {
        String s = id != null && SkyLook.sceneIndex(id) >= 0 ? id : SkyView.AUTO;
        sp(c).edit().putString(K_SCENE, s).apply();
    }
}
