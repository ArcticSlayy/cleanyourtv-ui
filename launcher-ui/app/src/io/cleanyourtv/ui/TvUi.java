package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Views for Explore, the title page and the services list, sized for a 1920-wide TV screen like the home screen. */
final class TvUi {
    private TvUi() {}

    static final int BG = 0xFF0B0D12, INK = 0xFFF5F6F8, DIM = 0x99F5F6F8, FAINT = 0x59F5F6F8, CARD = 0xFF1A1D24,
            ACCENT = 0xFF19B5A6;
    /** Posters in a row before its "See more" card: a row never scrolls sideways. */
    static final int ROW_MAX = 6;
    static final int POSTER_W = 210, POSTER_H = 315, POSTER_GAP = 26;

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

    /** A list item (an episode, a service): a soft card that lights up when focused, nothing grows past its edges. */
    static void softFocus(View v, float k, float radius) {
        v.setBackground(round(0x17FFFFFF, radius));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnFocusChangeListener((view, has) -> view.setBackground(round(has ? 0x59FFFFFF : 0x17FFFFFF, radius)));
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

    /**
     * A row: its title, then up to ROW_MAX posters and, when there are more, a "See more" card. `more` opens the rest
     * (null: a grid of the titles given).
     */
    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open) {
        return row(a, k, title, titles, open, null);
    }

    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open, Runnable more) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(a, k, 32, Ui.semibold, INK);
        h.setText(title);
        h.setPadding(0, 0, 0, Math.round(18 * k));
        box.addView(h);
        Row line = new Row(a);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(Math.round(24 * k), Math.round(30 * k), 0, Math.round(20 * k));
        for (int i = 0; i < titles.size() && i < ROW_MAX; i++) line.addView(poster(a, k, titles.get(i), open));
        if (titles.size() > ROW_MAX || (more != null && titles.size() >= ROW_MAX)) {
            Runnable all = more != null ? more : () -> GridActivity.show(a, title, titles, open);
            line.addView(moreCard(a, k, all));
        }
        box.addView(line);
        box.setClipChildren(false);
        box.setPadding(0, Math.round(26 * k), 0, Math.round(10 * k));
        return box;
    }

    static View poster(Activity a, float k, Tmdb.Title t, OnTitle open) {
        int w = Math.round(POSTER_W * k), h = Math.round(POSTER_H * k);
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
        lp.rightMargin = Math.round(POSTER_GAP * k);
        cell.setLayoutParams(lp);
        return cell;
    }

    /** The last card of a long row: opens all of it as a grid. */
    static View moreCard(Activity a, float k, Runnable open) {
        int w = Math.round(POSTER_W * k), h = Math.round(POSTER_H * k);
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(round(0x1FFFFFFF, 14 * k));
        TextView arrow = text(a, k, 64, Ui.regular, INK);
        arrow.setText("›");
        arrow.setGravity(Gravity.CENTER);
        TextView label = text(a, k, 26, Ui.medium, INK);
        label.setText(Ui.tr("Ver mais", "See more"));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Math.round(12 * k), 0, 0);
        card.addView(arrow);
        card.addView(label);
        card.setContentDescription(Ui.tr("Ver mais", "See more"));
        card.setOnClickListener(v -> open.run());
        focusable(card, k, 14 * k);
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        cell.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /**
     * A horizontal line of cards that remembers its card: coming from above or below lands on the card focused last
     * (the first one at the start), not on whichever card happens to be nearest.
     */
    static class Row extends LinearLayout {
        private View last;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
        }

        @Override public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            last = child;
        }

        @Override public void addFocusables(ArrayList<View> views, int direction, int mode) {
            View keep = last != null && last.getParent() == this ? last : getChildCount() > 0 ? getChildAt(0) : null;
            if (hasFocus() || keep == null || (direction != FOCUS_UP && direction != FOCUS_DOWN)) {
                super.addFocusables(views, direction, mode);
            } else {
                keep.addFocusables(views, direction, mode);
            }
        }
    }

    /** Posters in a vertical grid, `columns` per line; more can be added later (the next page). */
    static final class Grid extends LinearLayout {
        private final Activity a;
        private final float k;
        private final int columns;
        private final OnTitle open;
        private LinearLayout line;
        int count;

        Grid(Activity a, float k, int columns, OnTitle open) {
            super(a);
            this.a = a;
            this.k = k;
            this.columns = columns;
            this.open = open;
            setOrientation(VERTICAL);
            setClipChildren(false);
        }

        void add(List<Tmdb.Title> titles) {
            for (Tmdb.Title t : titles) add(poster(a, k, t, open));
        }

        void add(View cell) {
            if (line == null || line.getChildCount() >= columns) {
                line = new LinearLayout(a);
                line.setOrientation(HORIZONTAL);
                line.setClipChildren(false);
                line.setPadding(Math.round(24 * k), Math.round(30 * k), 0, Math.round(16 * k));
                addView(line);
            }
            line.addView(cell);
            count++;
        }

        int lineOf(View focused) {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                    if (v == row) return i;
                }
            }
            return -1;
        }
    }

    /**
     * A vertical scroll for the TV: the focused item is brought to the middle of the screen, and only when it would
     * otherwise sit near an edge (moving between things already in view doesn't scroll).
     */
    static final class Scroll extends ScrollView {
        private int aim = -1;

        Scroll(Context c) {
            super(c);
            setFocusable(false);
            setVerticalScrollBarEnabled(false);
            setClipChildren(false);
            setSmoothScrollingEnabled(true);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            int h = getHeight();
            if (h == 0 || getChildCount() == 0) return 0;
            int y = aim >= 0 ? aim : getScrollY();
            int margin = Math.round(h * 0.14f);
            if (r.top >= y + margin && r.bottom <= y + h - margin) return 0;
            if (r.top < margin && y == 0) return 0;     // near the top of the page: the page stays put
            int max = Math.max(0, getChildAt(0).getHeight() - h);
            int target = Math.max(0, Math.min(max, r.centerY() - h / 2));
            if (target != y) {
                aim = target;
                smoothScrollTo(0, target);
            }
            return 0;
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (t == aim) aim = -1;
        }

        void top() {
            aim = -1;
            scrollTo(0, 0);
        }
    }

    /** Children left to right, wrapping to a new line when the width runs out (chips, buttons). */
    static final class Flow extends ViewGroup {
        private final int gapX, gapY;

        Flow(Context c, int gapX, int gapY) {
            super(c);
            this.gapX = gapX;
            this.gapY = gapY;
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                x += c.getMeasuredWidth() + gapX;
                widest = Math.max(widest, x - gapX);
                lineH = Math.max(lineH, c.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                    : widest + getPaddingLeft() + getPaddingRight(), y + lineH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + c.getMeasuredWidth(),
                        getPaddingTop() + y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gapX;
                lineH = Math.max(lineH, c.getMeasuredHeight());
            }
        }
    }
}
