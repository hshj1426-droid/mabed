package kr.mabed.control;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** 코드로 직접 그린 아이콘 (그림 파일 없이) */
public class Glyph extends Drawable {

    public static final int STOP = 0, LAMP = 1, SPEAKER = 2, CHEVRON = 3, GEAR = 4, BACK = 5;

    private final int kind, color, size;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Glyph(int kind, int color, int sizePx) {
        this.kind = kind; this.color = color; this.size = sizePx;
        setBounds(0, 0, sizePx, sizePx);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter f) {}

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float x = b.left, y = b.top, w = b.width(), h = b.height();
        float s = Math.min(w, h);
        float cx = x + w / 2f, cy = y + h / 2f;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (kind) {
            case STOP: {
                p.setStyle(Paint.Style.FILL);
                float m = s * 0.24f;
                c.drawRoundRect(new RectF(x + m, y + m, x + w - m, y + h - m), s * 0.12f, s * 0.12f, p);
                break;
            }
            case LAMP: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.17f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.095f);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * i / 4.0;
                    float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
                    c.drawLine(cx + ux * s * 0.28f, cy + uy * s * 0.28f,
                               cx + ux * s * 0.40f, cy + uy * s * 0.40f, p);
                }
                break;
            }
            case SPEAKER: {
                p.setStyle(Paint.Style.FILL);
                Path path = new Path();
                path.moveTo(x + s * 0.12f, cy - s * 0.13f);
                path.lineTo(x + s * 0.26f, cy - s * 0.13f);
                path.lineTo(x + s * 0.46f, cy - s * 0.30f);
                path.lineTo(x + s * 0.46f, cy + s * 0.30f);
                path.lineTo(x + s * 0.26f, cy + s * 0.13f);
                path.lineTo(x + s * 0.12f, cy + s * 0.13f);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.085f);
                c.drawArc(new RectF(x + s * 0.50f, cy - s * 0.20f, x + s * 0.74f, cy + s * 0.20f), -58, 116, false, p);
                c.drawArc(new RectF(x + s * 0.50f, cy - s * 0.36f, x + s * 0.92f, cy + s * 0.36f), -52, 104, false, p);
                break;
            }
            case CHEVRON: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.11f);
                float r = s * 0.19f;
                c.drawLine(cx - r * 0.45f, cy - r, cx + r * 0.55f, cy, p);
                c.drawLine(cx + r * 0.55f, cy, cx - r * 0.45f, cy + r, p);
                break;
            }
            case GEAR: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.10f);
                c.drawCircle(cx, cy, s * 0.17f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                    c.drawLine(cx + dx * s * 0.27f, cy + dy * s * 0.27f,
                               cx + dx * s * 0.38f, cy + dy * s * 0.38f, p);
                }
                break;
            }
            case BACK: {   // 왼쪽 화살표 ←
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.10f);
                float r = s * 0.30f;
                c.drawLine(cx + r, cy, cx - r, cy, p);
                c.drawLine(cx - r, cy, cx - r * 0.25f, cy - r * 0.75f, p);
                c.drawLine(cx - r, cy, cx - r * 0.25f, cy + r * 0.75f, p);
                break;
            }
        }
    }
}
