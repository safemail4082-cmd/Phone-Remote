package com.bhaskar.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Round navigation pad: four arrows around a centre OK button. Holding an arrow repeats it. */
public class DPadView extends View {

    interface Listener { void onKey(int code); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private final RectF box = new RectF();
    private final Handler h = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int pressed = -1;   // 0 up, 1 right, 2 down, 3 left, 4 ok

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (pressed >= 0 && pressed < 4) {
                fire(pressed);
                h.postDelayed(this, 110);
            }
        }
    };

    public DPadView(Context c) {
        super(c);
        setContentDescription("Navigation pad");
    }

    void setListener(Listener l) { listener = l; }

    private void fire(int zone) {
        if (listener == null) return;
        int code = zone == 0 ? Keys.UP : zone == 1 ? Keys.RIGHT : zone == 2 ? Keys.DOWN : zone == 3 ? Keys.LEFT : Keys.CENTER;
        listener.onKey(code);
    }

    private int zone(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(getWidth(), getHeight()) / 2f;
        float dx = x - cx, dy = y - cy;
        double r = Math.sqrt(dx * dx + dy * dy);
        if (r > R) return -1;
        if (r < R * 0.36) return 4;
        double a = Math.toDegrees(Math.atan2(dy, dx));
        if (a >= -135 && a < -45) return 0;
        if (a >= -45 && a < 45) return 1;
        if (a >= 45 && a < 135) return 2;
        return 3;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), hh = getHeight();
        float cx = w / 2f, cy = hh / 2f;
        float R = Math.min(w, hh) / 2f - 2;
        float dens = getResources().getDisplayMetrics().density;

        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, R, new int[]{0xFF2B2F49, 0xFF1A1D2E}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dens);
        p.setColor(0x22FFFFFF);
        c.drawCircle(cx, cy, R, p);

        if (pressed >= 0 && pressed < 4) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x556D5BFF);
            box.set(cx - R, cy - R, cx + R, cy + R);
            float start = pressed == 0 ? 225 : pressed == 1 ? -45 : pressed == 2 ? 45 : 135;
            c.drawArc(box, start, 90, true, p);
        }

        // thin diagonal separators
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dens);
        p.setColor(0x14FFFFFF);
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(45 + 90 * k);
            c.drawLine(cx + (float) Math.cos(a) * R * 0.36f, cy + (float) Math.sin(a) * R * 0.36f,
                    cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, p);
        }

        // centre OK button
        float r0 = R * 0.34f;
        p.setStyle(Paint.Style.FILL);
        int a1 = pressed == 4 ? 0xFF5B4BEA : 0xFF8B7DFF, a2 = pressed == 4 ? 0xFF3B32B8 : 0xFF4F46E5;
        p.setShader(new LinearGradient(cx - r0, cy - r0, cx + r0, cy + r0, a1, a2, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r0, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(R * 0.17f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("OK", cx, cy + R * 0.06f, p);

        // arrows
        float s = R * 0.085f;
        tri.reset();
        tri.moveTo(0, -s);
        tri.lineTo(-s * 1.15f, s * 0.75f);
        tri.lineTo(s * 1.15f, s * 0.75f);
        tri.close();
        for (int k = 0; k < 4; k++) {
            double ang = Math.toRadians(-90 + 90 * k);
            float ax = cx + (float) Math.cos(ang) * R * 0.68f, ay = cy + (float) Math.sin(ang) * R * 0.68f;
            c.save();
            c.translate(ax, ay);
            c.rotate(90 * k);
            p.setStyle(Paint.Style.FILL);
            p.setColor(pressed == k ? 0xFFFFFFFF : 0xCCE5E7F2);
            c.drawPath(tri, p);
            c.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zone(e.getX(), e.getY());
                if (z < 0) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                pressed = z;
                invalidate();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                fire(z);
                if (z < 4) h.postDelayed(repeater, 380);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                h.removeCallbacks(repeater);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
