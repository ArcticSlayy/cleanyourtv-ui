package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Views for Explore, the title page and the services list, sized for a 1920-wide TV screen like the home screen. */
final class TvUi {
    private TvUi() {}

    static final int BG = 0xFF0B0D12, INK = 0xFFF5F6F8, DIM = 0x99F5F6F8, FAINT = 0x59F5F6F8, CARD = 0xFF1A1D24,
            ACCENT = 0xFF19B5A6;

    static float k(Activity a) {
        return a.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static TextView text(Context c, float k, float size, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Focused: lifted like the home screen's tiles, bigger with a deep shadow, no outline. */
    static void focusable(View v, float k, float radius) {
        focusable(v, k, radius, null);
    }

    static void focusable(View v, float k, float radius, TextView caption) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        v.setOnFocusChangeListener((view, has) -> {
            float s = has ? 1.1f : 1f;
            view.animate().scaleX(s).scaleY(s).translationZ(has ? 28 * k : 0).setDuration(160).start();
            if (caption != null) caption.setTextColor(has ? INK : DIM);
        });
    }

    /** A pill button: white when focused. */
    static TextView button(Context c, float k, String label, View.OnClickListener click) {
        TextView b = text(c, k, 28, Ui.medium, INK);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Math.round(34 * k), Math.round(18 * k), Math.round(34 * k), Math.round(18 * k));
        b.setBackground(round(0x33FFFFFF, 40 * k));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(round(has ? INK : 0x33FFFFFF, 40 * k));
            b.setTextColor(has ? 0xFF14161A : INK);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? 12 * k : 0).setDuration(140).start();
        });
        b.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        return b;
    }

    interface OnTitle { void open(Tmdb.Title t); }

    /** A row: its title, then posters that scroll sideways. */
    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(a, k, 32, Ui.semibold, INK);
        h.setText(title);
        h.setPadding(0, 0, 0, Math.round(18 * k));
        box.addView(h);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setFocusable(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.setPadding(Math.round(24 * k), Math.round(30 * k), Math.round(80 * k), Math.round(20 * k));
        for (Tmdb.Title t : titles) line.addView(poster(a, k, t, open));
        scroll.addView(line);
        box.addView(scroll);
        box.setClipChildren(false);
        box.setPadding(0, Math.round(26 * k), 0, Math.round(10 * k));
        return box;
    }

    static View poster(Activity a, float k, Tmdb.Title t, OnTitle open) {
        int w = Math.round(210 * k), h = Math.round(315 * k);
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = new FrameLayout(a);
        card.setBackground(round(CARD, 14 * k));
        card.setClipToOutline(true);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(w, h));
        TextView fallback = text(a, k, 24, Ui.medium, DIM);
        fallback.setText(t.name);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(Math.round(14 * k), 0, Math.round(14 * k), 0);
        card.addView(fallback, 0, new FrameLayout.LayoutParams(w, h));
        Images.load(img, t.poster.isEmpty() ? null : Tmdb.POSTER + t.poster);
        card.setContentDescription(t.name + (t.year.isEmpty() ? "" : ", " + t.year));
        card.setOnClickListener(v -> open.open(t));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        TextView name = text(a, k, 22, Ui.regular, DIM);
        focusable(card, k, 14 * k, name);
        name.setText(t.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Math.round(26 * k), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(26 * k);
        cell.setLayoutParams(lp);
        return cell;
    }
}
