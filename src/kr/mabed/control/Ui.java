package kr.mabed.control;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** 화면 부품을 만드는 도구모음 */
public class Ui {
    public final Activity a;
    public final boolean dark;
    public final int bg, card, line, fg, muted, accent, danger, ok;
    /** 화면 크기에 맞춘 배율 (작은 폰 0.92 ~ 큰 폰 1.18) */
    public final float sc;
    public final int screenH, screenW;

    public Ui(Activity a) {
        this.a = a;
        Configuration cf = a.getResources().getConfiguration();
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        screenW = dm.widthPixels; screenH = dm.heightPixels;
        int swDp = cf.smallestScreenWidthDp > 0 ? cf.smallestScreenWidthDp : 392;
        float f = swDp / 392f;
        if (f < 0.90f) f = 0.90f;
        if (f > 1.18f) f = 1.18f;
        sc = f;
        int m = cf.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = m == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg=0xFF15120F; card=0xFF221E1A; line=0xFF332D27; fg=0xFFF3EDE5;
            muted=0xFF9A8F83; accent=0xFFD98461; danger=0xFFE05A4E; ok=0xFF6FBF73;
        } else {
            bg=0xFFFBF8F4; card=0xFFFFFFFF; line=0xFFE7E0D7; fg=0xFF1C1713;
            muted=0xFF8A8078; accent=0xFFB4522E; danger=0xFFC0392B; ok=0xFF2E7D32;
        }
    }

    /** 화면 배율까지 적용한 dp */
    public int dp(float v) {
        return (int)(v * sc * a.getResources().getDisplayMetrics().density + 0.5f);
    }
    /** 배율만 적용하지 않은 순수 dp (최소 터치 크기 등) */
    public int rawDp(float v) {
        return (int)(v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
    /** 글자 크기 배율 */
    public float sp(float v) { return v * sc; }

    public LinearLayout col() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    public LinearLayout row(int gapBottom) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(gapBottom);
        l.setLayoutParams(p);
        return l;
    }

    public LinearLayout.LayoutParams w(float weight, int side) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weight);
        p.setMargins(dp(side), 0, dp(side), 0);
        return p;
    }

    public GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    public View ripple(View v, int fill, int stroke, float radius) {
        GradientDrawable g = box(fill, stroke, radius);
        v.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), g, g));
        return v;
    }

    public TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    public TextView head(String s) {
        TextView t = text(s, 13, muted, true);
        t.setPadding(dp(4), dp(20), 0, dp(9));
        t.setLetterSpacing(0.04f);
        return t;
    }

    public TextView note(String s) {
        TextView t = text(s, 12.5f, muted, false);
        t.setPadding(dp(4), dp(2), dp(4), dp(10));
        return t;
    }

    public View card(View inner, int padding) {
        LinearLayout c = col();
        c.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        c.setBackground(box(card, line, 16));
        c.addView(inner);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        c.setLayoutParams(p);
        return c;
    }

    /** 큰 버튼 */
    public Button btn(String label, int fill, int textColor, int stroke, float size,
                      int padV, final Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        int pv = dp(padV);
        b.setPadding(dp(8), pv, dp(8), pv);
        b.setMinimumHeight(rawDp(48));
        ripple(b, fill, stroke, 14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        b.setLayoutParams(p);
        if (action != null) b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    public Button soft(String label, Runnable r) { return btn(label, card, fg, line, 15, 16, r); }
    public Button small(String label, Runnable r) { return btn(label, card, fg, line, 13, 11, r); }


    public GradientDrawable oval(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** 위에서 아래로 연한 그라데이션 배경 */
    public GradientDrawable grad(int top, int bottom, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ top, bottom });
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), line);
        return g;
    }

    /** 작은 알약 모양 표시 */
    public TextView pill(String s, int textColor, int fill, int stroke) {
        TextView t = text(s, 11.5f, textColor, true);
        t.setPadding(dp(11), dp(5), dp(11), dp(6));
        t.setBackground(box(fill, stroke, 20));
        return t;
    }

    /** 동그란 아이콘 버튼 (−, +) */
    public Button circleBtn(String label, final Runnable r) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(20));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinHeight(0);
        b.setMinimumWidth(0); b.setMinimumHeight(0);
        GradientDrawable g = oval(bg, line);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), g, g));
        if (r != null) b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        return b;
    }

    /** 아이콘만 있는 둥근 버튼 (뒤로 ←, 설정 톱니) — 크기는 부르는 쪽에서 rawDp(44) 정도로 */
    public ImageView iconBtn(int glyph, int color, String desc, final Runnable r) {
        ImageView v = new ImageView(a);
        v.setImageDrawable(new Glyph(glyph, color, dp(22)));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(desc);   // 화면 읽어주기(TalkBack)용 이름
        v.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), null, oval(0xFFFFFFFF, 0)));
        v.setClickable(true);
        if (r != null) v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { r.run(); }
        });
        return v;
    }

    /** 메뉴 사이 가는 선 */
    public View hair() {
        View v = new View(a);
        v.setBackgroundColor(line);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, rawDp(0.8f)));
        p.leftMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    public EditText input(String hint, String value, boolean numeric) {
        EditText e = new EditText(a);
        e.setHint(hint); e.setText(value);
        e.setTextColor(fg); e.setHintTextColor(muted);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14));
        e.setSingleLine(true);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setBackground(box(card, line, 12));
        e.setPadding(dp(13), dp(13), dp(13), dp(13));
        e.setMinimumHeight(rawDp(48));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        e.setLayoutParams(p);
        return e;
    }
}
