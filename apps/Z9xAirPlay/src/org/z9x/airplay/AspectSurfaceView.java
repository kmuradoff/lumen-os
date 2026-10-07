/*
 * SurfaceView that keeps the picture's aspect ratio inside its parent (letterbox / pillarbox).
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.z9x.airplay;

import android.content.Context;
import android.view.SurfaceView;
import android.view.View;

final class AspectSurfaceView extends SurfaceView {
    private int videoW, videoH;

    AspectSurfaceView(Context c) {
        super(c);
    }

    /** Picture size; 0 = fill the parent. */
    void setVideoSize(int w, int h) {
        if (w == videoW && h == videoH) return;
        videoW = w;
        videoH = h;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = View.MeasureSpec.getSize(wSpec), h = View.MeasureSpec.getSize(hSpec);
        if (videoW > 0 && videoH > 0 && w > 0 && h > 0) {
            if ((long) videoW * h > (long) videoH * w) {
                h = (int) ((long) w * videoH / videoW);
            } else {
                w = (int) ((long) h * videoW / videoH);
            }
        }
        setMeasuredDimension(w, h);
    }
}
