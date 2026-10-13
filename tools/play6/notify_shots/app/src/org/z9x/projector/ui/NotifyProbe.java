package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Harness only (tools/play6/notify_shots): one Notify card drawn off screen through the real Notify.CardView
 * (reflection: the class is private), with its layout facts for the report. Never shipped.
 */
public final class NotifyProbe {
    public View view;
    public int w, h;
    public Layout title, desc;
    public float contentX, textW;

    public static NotifyProbe make(Context c, CharSequence t, CharSequence d, Drawable icon) throws Exception {
        Class<?> k = Class.forName("org.z9x.projector.ui.Notify$CardView");
        Constructor<?> ctor = k.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        NotifyProbe p = new NotifyProbe();
        p.view = (View) ctor.newInstance(c);
        Method set = k.getDeclaredMethod("set", CharSequence.class, CharSequence.class, Drawable.class);
        set.setAccessible(true);
        set.invoke(p.view, t, d, icon);
        p.w = (Integer) get(k, p.view, "cardW");
        p.h = (Integer) get(k, p.view, "cardH");
        p.title = (Layout) get(k, p.view, "titleLayout");
        p.desc = (Layout) get(k, p.view, "descLayout");
        p.contentX = (Float) get(k, p.view, "contentX");
        p.textW = (Float) get(k, p.view, "textW");
        p.view.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY));
        p.view.layout(0, 0, p.w, p.h);
        return p;
    }

    private static Object get(Class<?> k, Object o, String name) throws Exception {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
