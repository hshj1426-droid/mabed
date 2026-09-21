package kr.mabed.control;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** 침대 옆모습 그림 */
public class BedView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int cFrame, cBed, cAccent, cFloor;
    private int head = 0, leg = 0, headTarget = -1, legTarget = -1;
    private boolean mini = false;

    public BedView(Context c, int frame, int bed, int accent, int floor) {
        super(c);
        cFrame = frame; cBed = bed; cAccent = accent; cFloor = floor;
    }

    /** 자세 버튼에 들어가는 작은 그림 */
    public void mini(int h, int l) { mini = true; head = h; leg = l; invalidate(); }

    public void set(int h, int l, int ht, int lt) {
        if (h == head && l == leg && ht == headTarget && lt == legTarget) return;
        head = h; leg = l; headTarget = ht; legTarget = lt;
        invalidate();
    }

    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    private void seg(Canvas c, float x, float y, float len, float deg, boolean left,
                     int color, float w, int a) { seg(c, x, y, len, deg, left, color, w, a, 0f); }

    private void seg(Canvas c, float x, float y, float len, float deg, boolean left,
                     int color, float w, int a, float lift) {
        double r = Math.toRadians(deg);
        float dx = (float) (Math.cos(r) * len) * (left ? -1 : 1);
        float dy = (float) (Math.sin(r) * len);
        if (lift != 0f) {
            float nx = (float) Math.sin(r) * (left ? 1 : -1);
            float ny = (float) -Math.cos(r);
            x += nx * lift; y += ny * lift;
        }
        p.setColor(alpha(color, a));
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setPathEffect(null);
        c.drawLine(x, y, x + dx, y - dy, p);
    }

    private void ghost(Canvas c, float x, float y, float len, float deg, boolean left, float thick) {
        double r = Math.toRadians(deg);
        float dx = (float) (Math.cos(r) * len) * (left ? -1 : 1);
        float dy = (float) (Math.sin(r) * len);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(2f, thick * 0.22f));
        p.setColor(alpha(cAccent, 130));
        p.setPathEffect(new DashPathEffect(new float[]{ thick * 0.5f, thick * 0.45f }, 0));
        c.drawLine(x, y, x + dx, y - dy, p);
        p.setPathEffect(null);
    }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        float baseY = H * (mini ? 0.62f : 0.66f);
        float cx = W * 0.5f;
        float thick = Math.max(mini ? 4.5f : 11f, H * (mini ? 0.145f : 0.090f));
        float room = baseY - thick * 0.70f;
        float midHalf = W * (mini ? 0.085f : 0.105f);
        float headLen = Math.min(W * (mini ? 0.285f : 0.275f), room * 0.95f);
        float legLen  = Math.min(W * (mini ? 0.245f : 0.225f), room * 0.95f);
        float hx = cx - midHalf, lx = cx + midHalf;

        if (!mini) {
            // 바닥 그림자
            p.setStyle(Paint.Style.FILL);
            p.setColor(alpha(cFloor, 38));
            c.drawOval(new RectF(W * 0.16f, H * 0.875f, W * 0.84f, H * 0.955f), p);

            // 바닥선
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(1.5f, H * 0.007f));
            p.setColor(alpha(cFloor, 90));
            c.drawLine(W * 0.08f, H * 0.925f, W * 0.92f, H * 0.925f, p);

            // 프레임
            p.setStrokeWidth(Math.max(4f, thick * 0.30f));
            p.setColor(cFrame);
            float railY = baseY + thick * 0.46f;
            c.drawLine(W * 0.26f, railY, W * 0.26f, H * 0.915f, p);
            c.drawLine(W * 0.74f, railY, W * 0.74f, H * 0.915f, p);
            c.drawLine(W * 0.24f, railY, W * 0.76f, railY, p);

            // 목표 위치(점선)
            if (headTarget >= 0 && headTarget != head) ghost(c, hx, baseY, headLen, headTarget, true, thick);
            if (legTarget >= 0 && legTarget != leg)   ghost(c, lx, baseY, legLen, legTarget, false, thick);
        }

        // 매트리스
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(thick);
        p.setColor(cBed);
        p.setPathEffect(null);
        c.drawLine(hx, baseY, lx, baseY, p);
        seg(c, hx, baseY, headLen, head, true,  cBed, thick, 255);
        seg(c, lx, baseY, legLen,  leg,  false, cBed, thick, 255);

        if (!mini) {
            // 매트리스 윗면 광택 (윗면 쪽으로 살짝 올려 그린다)
            seg(c, hx, baseY, headLen * 0.84f, head, true,  0xFFFFFFFF, thick * 0.13f, 34, thick * 0.26f);
            seg(c, lx, baseY, legLen  * 0.84f, leg,  false, 0xFFFFFFFF, thick * 0.13f, 34, thick * 0.26f);

            // 베개 — 매트리스 윗면에 얹는다
            double r = Math.toRadians(head);
            float dx = (float) -Math.cos(r), dy = (float) -Math.sin(r);   // 머리쪽 방향
            float nx = (float) Math.sin(r),  ny = (float) -Math.cos(r);   // 윗면 방향
            float px = hx + dx * headLen * 0.74f + nx * thick * 0.46f;
            float py = baseY + dy * headLen * 0.74f + ny * thick * 0.46f;
            float ex = dx * headLen * 0.13f, ey = dy * headLen * 0.13f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(thick * 0.42f);
            p.setColor(0xF0F7EEE2);
            c.drawLine(px - ex, py - ey, px + ex, py + ey, p);
        }
    }
}
