package kr.mabed.control;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/** 직접 그린 슬라이더 — 둥근 트랙, 큰 손잡이, 목표 위치 눈금 */
public class Slider extends View {

    public interface Listener { void onSlide(int value, boolean finished); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int cTrack, cFill, cThumb, cGhost;
    private final float d;
    private int max = 100, value = 0, target = -1;
    private Listener listener;

    public Slider(Context c, int track, int fill, int thumb, int ghost) {
        super(c);
        cTrack = track; cFill = fill; cThumb = thumb; cGhost = ghost;
        d = c.getResources().getDisplayMetrics().density;
        setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(46 * d)));
    }

    public void setListener(Listener l) { listener = l; }
    public void setMax(int m) { max = Math.max(1, m); invalidate(); }
    public int getMax() { return max; }
    public int getValue() { return value; }
    public void setValue(int v) { int n = clamp(v); if (n != value) { value = n; invalidate(); } }
    public void setTarget(int t) { if (t != target) { target = t; invalidate(); } }

    private int clamp(int v) { return v < 0 ? 0 : (v > max ? max : v); }
    private float thumbR() { return 13 * d; }
    private float left()  { return thumbR() + 2 * d; }
    private float right() { return getWidth() - thumbR() - 2 * d; }
    private float xOf(int v) { return left() + (right() - left()) * ((float) v / max); }

    @Override protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, h = 9 * d;
        float l = left(), r = right();
        if (r <= l) return;

        p.setStyle(Paint.Style.FILL);
        p.setColor(cTrack);
        c.drawRoundRect(new RectF(l - h/2, cy - h/2, r + h/2, cy + h/2), h/2, h/2, p);

        float tx = xOf(value);
        p.setColor(cFill);
        c.drawRoundRect(new RectF(l - h/2, cy - h/2, tx, cy + h/2), h/2, h/2, p);

        if (target >= 0 && target != value) {
            float gx = xOf(clamp(target));
            p.setColor((cGhost & 0x00FFFFFF) | 0x99000000);
            c.drawRoundRect(new RectF(gx - 1.6f*d, cy - h*1.05f, gx + 1.6f*d, cy + h*1.05f), 2*d, 2*d, p);
        }

        p.setColor(0x26000000);
        c.drawCircle(tx, cy + 1.6f*d, thumbR(), p);
        p.setColor(cThumb);
        c.drawCircle(tx, cy, thumbR(), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(cTrack);
        c.drawCircle(tx, cy, thumbR() - 0.6f * d, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(cFill);
        c.drawCircle(tx, cy, thumbR() * 0.40f, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float w = right() - left();
        if (w <= 0) return false;
        int v = clamp(Math.round((e.getX() - left()) / w * max));
        int act = e.getAction();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            if (act == MotionEvent.ACTION_DOWN && getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
            setValue(v);
            if (listener != null) listener.onSlide(v, false);
            return true;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            setValue(v);
            if (listener != null) listener.onSlide(v, true);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
