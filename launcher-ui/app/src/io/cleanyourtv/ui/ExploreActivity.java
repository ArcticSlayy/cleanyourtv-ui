package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore, laid out like the Apple TV app: a banner of what's big and picked for the person, rows of six posters
 * with "See more" (a vertical grid), and a menu on the left (Left at the edge opens it, Right or Back closes it):
 * Search, Home, My list, Today's top 10, Movies, Shows, Categories, what the person pinned (categories, searches),
 * one page per streaming service with an app, and Settings. The background is the focused title's picture, softly
 * blurred. With EXTRA_SECTION = "watchlist" (the remote's bookmark button) it opens on My list.
 */
public class ExploreActivity extends Activity {
    static final String EXTRA_SECTION = "section";
    private static final String HOME = "home", SEARCH = "search", LIST = "watchlist", TOP = "top", MOVIES = "movie",
            SHOWS = "tv", CATS = "categories", SETTINGS = "settings", SERVICE = "service:", GENRE = "genre:", QUERY = "q:";
    private static final long HERO_MS = 9000;
    private static final int HERO_H = 720, HERO_BLEED = 144;

    /** Categories, like Netflix's: key, Portuguese, English, discover parameters for movies and for shows (null: none). */
    private static final String[][] GENRES = {
        {"acao", "Ação", "Action", "&with_genres=28", "&with_genres=10759"},
        {"aventura", "Aventura", "Adventure", "&with_genres=12", "&with_genres=10759"},
        {"anime", "Anime", "Anime", "&with_genres=16&with_keywords=210024", "&with_genres=16&with_keywords=210024"},
        {"animacao", "Animação", "Animation", "&with_genres=16", "&with_genres=16"},
        {"comedia", "Comédia", "Comedy", "&with_genres=35", "&with_genres=35"},
        {"crime", "Crime", "Crime", "&with_genres=80", "&with_genres=80"},
        {"documentarios", "Documentários", "Documentaries", "&with_genres=99", "&with_genres=99"},
        {"drama", "Drama", "Drama", "&with_genres=18", "&with_genres=18"},
        {"familia", "Família", "Family", "&with_genres=10751", "&with_genres=10751"},
        {"fantasia", "Fantasia", "Fantasy", "&with_genres=14", "&with_genres=10765"},
        {"ficcao", "Ficção científica", "Sci-fi", "&with_genres=878", "&with_genres=10765"},
        {"guerra", "Guerra", "War", "&with_genres=10752", "&with_genres=10768"},
        {"historia", "História", "History", "&with_genres=36", null},
        {"infantil", "Infantil", "Kids", null, "&with_genres=10762"},
        {"misterio", "Mistério", "Mystery", "&with_genres=9648", "&with_genres=9648"},
        {"musica", "Música", "Music", "&with_genres=10402", null},
        {"faroeste", "Faroeste", "Western", "&with_genres=37", "&with_genres=37"},
        {"reality", "Reality", "Reality", null, "&with_genres=10764"},
        {"romance", "Romance", "Romance", "&with_genres=10749", null},
        {"suspense", "Suspense", "Thriller", "&with_genres=53", null},
        {"terror", "Terror", "Horror", "&with_genres=27", null},
        {"novela", "Novelas", "Soaps", null, "&with_genres=10766"},
        {"coreanos", "Doramas", "K-dramas", null, "&with_origin_country=KR&with_genres=18"},
        {"nacionais", "Nacionais", "Brazilian", "&with_origin_country=BR", "&with_origin_country=BR"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private float k;
    private TvUi.Scroll scroll;
    private LinearLayout page, rows, menu, menuPins, menuServices, menuFooter;
    private TextView status, servicesHeader, pinsHeader;
    private View shade;
    private String current = HOME;
    private boolean fromBookmark, menuOpen, resumedOnce;
    private View beforeMenu;
    private int generation;
    private final Map<String, TextView> menuItems = new LinkedHashMap<>();
    private final Map<Integer, View> placed = new HashMap<>();
    private final Map<Integer, String> placedKeys = new HashMap<>();
    private List<Section> sections = new ArrayList<>();
    private List<Service> services;

    /** A streaming service with an app: TMDB lists some more than once ("with ads"), so one entry per app. */
    private static final class Service {
        final String key, name, ids, logo;
        Service(String key, String name, String ids, String logo) {
            this.key = key;
            this.name = name;
            this.ids = ids;
            this.logo = logo;
        }
    }

    private static final class Section {
        final String title;
        final Callable<List<Tmdb.Title>> load;
        String morePath, moreKind;
        Runnable more;
        boolean personal, grid, ranked;

        Section(String title, Callable<List<Tmdb.Title>> load) {
            this.title = title;
            this.load = load;
        }

        Section more(String path, String kind) {
            morePath = path;
            moreKind = kind;
            return this;
        }

        Section more(Runnable r) {
            more = r;
            return this;
        }

        Section personal() {
            personal = true;
            return this;
        }

        Section ranked() {
            ranked = grid = true;
            return this;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Images.init(this);
        k = TvUi.k(this);
        fromBookmark = "watchlist".equals(getIntent().getStringExtra(EXTRA_SECTION));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        root.addView(backdrops(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll = new TvUi.Scroll(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setPadding(0, 0, 0, Math.round(80 * k));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(burger(), burgerParams());
        shade = new View(this);
        shade.setBackgroundColor(0xB3000000);
        shade.setAlpha(0);
        shade.setVisibility(View.GONE);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildMenu(), new FrameLayout.LayoutParams(Math.round(560 * k), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> followFocus(now));
        if (!Tmdb.hasKey()) {
            header(Ui.tr("Explorar", "Explore"), Ui.tr("Sem chave do TMDB: coloque a sua em launcher-ui/config.properties e instale de novo.",
                    "No TMDB key: add yours to launcher-ui/config.properties and install again."));
            return;
        }
        io.execute(() -> {
            List<Service> list = services();
            main.post(() -> { if (!isFinishing()) showServices(list); });
        });
        showPage(fromBookmark ? LIST : HOME);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hero != null && !menuOpen) restartHero();
        if (resumedOnce && Tmdb.hasKey()) {   // back from a title page: the list, the picks and what was seen may have changed
            refreshPersonal();
            TvUi.refreshSeen(page);
        }
        resumedOnce = true;
    }

    @Override protected void onPause() {
        main.removeCallbacks(heroTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // MARK: - Background: the focused title's picture

    private ImageView backA, backB;
    private boolean backFront;
    private String backUrl, backWant;
    private final Runnable backSoon = this::loadBackdrop;

    private View backdrops() {
        FrameLayout box = new FrameLayout(this);
        backA = new ImageView(this);
        backB = new ImageView(this);
        for (ImageView v : new ImageView[] {backA, backB}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xE60B0D12, 0xC70B0D12, 0xA30B0D12}));
        box.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private void followFocus(View now) {
        if (menuOpen) return;
        Tmdb.Title t = now != null && now == hero && !heroList.isEmpty() ? (Tmdb.Title) heroList.get(heroAt)[0] : TvUi.titleOf(now);
        if (t == null || t.backdrop.isEmpty() || "null".equals(t.backdrop)) return;
        backWant = "https://image.tmdb.org/t/p/w300" + t.backdrop;
        main.removeCallbacks(backSoon);
        main.postDelayed(backSoon, 350);
    }

    /** Small and stretched: a soft blur, lighter than the home screen's, that costs nothing to draw. */
    private void loadBackdrop() {
        String url = backWant;
        if (url == null || url.equals(backUrl)) return;
        backUrl = url;
        io.execute(() -> {
            Bitmap b = Images.fetch(url);
            if (b == null) return;
            Bitmap soft = blur(Bitmap.createScaledBitmap(b, 160, Math.max(1, Math.round(160f * b.getHeight() / b.getWidth())), true), 3);
            main.post(() -> {
                if (!url.equals(backUrl) || isFinishing()) return;
                ImageView in = backFront ? backA : backB, out = backFront ? backB : backA;
                backFront = !backFront;
                in.setImageBitmap(soft);
                in.animate().alpha(1).setDuration(600).start();
                out.animate().alpha(0).setDuration(600).start();
            });
        });
    }

    /** Three box blurs on a small copy: close to a Gaussian, smooth when stretched over the screen. */
    private static Bitmap blur(Bitmap small, int radius) {
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean across) {
        int lines = across ? h : w, len = across ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int j = Math.max(0, i - r); j <= Math.min(len - 1, i + r); j++) {
                    int c = in[across ? line * w + j : j * w + line];
                    rs += (c >> 16) & 0xFF;
                    gs += (c >> 8) & 0xFF;
                    bs += c & 0xFF;
                    n++;
                }
                out[across ? line * w + i : i * w + line] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    // MARK: - Menu

    /** Three short bars at the top left: the menu is there, to the left. */
    private View burger() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            bar.setBackground(TvUi.round(0xB3FFFFFF, 2 * k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(34 * k), Math.round(4 * k));
            lp.topMargin = i == 0 ? 0 : Math.round(7 * k);
            b.addView(bar, lp);
        }
        return b;
    }

    private FrameLayout.LayoutParams burgerParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(52 * k);
        lp.topMargin = Math.round(66 * k);
        return lp;
    }

    private View buildMenu() {
        TvUi.Scroll box = new TvUi.Scroll(this);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFC12151C, 0xF512151C}));
        box.setElevation(24 * k);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(Math.round(40 * k), Math.round(60 * k), Math.round(40 * k), Math.round(60 * k));
        menu.addView(menuLabel(Ui.tr("EXPLORAR", "EXPLORE"), 0));
        menuItem(menu, SEARCH, Ui.tr("Buscar", "Search"), null);
        menuItem(menu, HOME, Ui.tr("Início", "Home"), null);
        menuItem(menu, LIST, Ui.tr("Minha lista", "My list"), null);
        menuItem(menu, TOP, Ui.tr("Top 10 do dia", "Today's top 10"), null);
        menuItem(menu, MOVIES, Ui.tr("Filmes", "Movies"), null);
        menuItem(menu, SHOWS, Ui.tr("Séries", "Shows"), null);
        menuItem(menu, CATS, Ui.tr("Categorias", "Categories"), null);
        pinsHeader = menuLabel(Ui.tr("FIXADOS", "PINNED"), 30);
        menu.addView(pinsHeader);
        menuPins = new LinearLayout(this);
        menuPins.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuPins);
        servicesHeader = menuLabel(Ui.tr("SEUS SERVIÇOS", "YOUR SERVICES"), 30);
        servicesHeader.setVisibility(View.GONE);
        menu.addView(servicesHeader);
        menuServices = new LinearLayout(this);
        menuServices.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuServices);
        menuFooter = new LinearLayout(this);
        menuFooter.setOrientation(LinearLayout.VERTICAL);
        menuFooter.setPadding(0, Math.round(24 * k), 0, 0);
        menuItem(menuFooter, SETTINGS, Ui.tr("Ajustes", "Settings"), null);
        menu.addView(menuFooter);
        showPins();
        box.addView(menu);
        box.setVisibility(View.GONE);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return box;
    }

    private TextView menuLabel(String text, int top) {
        TextView t = TvUi.text(this, k, 22, Ui.semibold, TvUi.FAINT);
        t.setText(text);
        t.setLetterSpacing(0.12f);
        t.setPadding(Math.round(26 * k), Math.round(top * k), 0, Math.round(14 * k));
        return t;
    }

    private void menuItem(LinearLayout into, String id, String label, String logo) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(Math.round(26 * k), Math.round(16 * k), Math.round(26 * k), Math.round(16 * k));
        if (logo != null) {
            ImageView img = new ImageView(this);
            img.setClipToOutline(true);
            img.setBackground(TvUi.round(TvUi.CARD, 10 * k));
            Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(46 * k), Math.round(46 * k));
            lp.rightMargin = Math.round(20 * k);
            item.addView(img, lp);
        }
        TextView t = TvUi.text(this, k, 30, Ui.medium, TvUi.DIM);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(t);
        item.setFocusable(true);
        item.setTag(t);
        item.setOnFocusChangeListener((v, has) -> styleItem(id, item));
        item.setOnClickListener(v -> {
            closeMenu(false);
            showPage(id);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * k);
        into.addView(item, lp);
        menuItems.put(id, t);
        styleItem(id, item);
    }

    /** Focused: a white pill; the page showing: bright text on a soft pill; the others: dimmed. */
    private void styleItem(String id, View item) {
        TextView t = (TextView) item.getTag();
        boolean focused = item.hasFocus(), here = id.equals(current);
        item.setBackground(focused ? TvUi.round(TvUi.INK, 18 * k) : here ? TvUi.round(0x1FFFFFFF, 18 * k) : null);
        t.setTextColor(focused ? 0xFF14161A : here ? TvUi.INK : TvUi.DIM);
        t.setTypeface(here || focused ? Ui.semibold : Ui.medium);
    }

    private void restyleMenu() {
        for (Map.Entry<String, TextView> e : menuItems.entrySet()) styleItem(e.getKey(), (View) e.getValue().getParent());
    }

    private void showServices(List<Service> list) {
        for (Service s : list) menuItem(menuServices, SERVICE + s.key, s.name, s.logo);
        servicesHeader.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showPins() {
        for (int i = 0; i < menuPins.getChildCount(); i++) menuItems.values().remove(((View) menuPins.getChildAt(i)).getTag());
        menuPins.removeAllViews();
        List<String[]> pins = pins();
        for (String[] p : pins) menuItem(menuPins, p[0], p[1], null);
        pinsHeader.setVisibility(pins.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openMenu() {
        if (menuOpen) return;
        menuOpen = true;
        beforeMenu = getCurrentFocus();
        ViewGroup box = (ViewGroup) menu.getParent();
        box.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        box.setVisibility(View.VISIBLE);
        box.setTranslationX(-560 * k);
        box.animate().translationX(0).setDuration(220).start();
        shade.setVisibility(View.VISIBLE);
        shade.animate().alpha(1).setDuration(220).start();
        restyleMenu();
        TextView here = menuItems.get(current);
        ((View) (here != null ? here : menuItems.get(HOME)).getParent()).requestFocus();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // only the menu takes the focus now
        main.removeCallbacks(heroTick);
    }

    /** `restore`: back to what was focused (Right, Back); otherwise the new page takes the focus. */
    private void closeMenu(boolean restore) {
        if (!menuOpen) return;
        menuOpen = false;
        ViewGroup box = (ViewGroup) menu.getParent();
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.animate().translationX(-560 * k).setDuration(200)
                .withEndAction(() -> { if (!menuOpen) box.setVisibility(View.GONE); }).start();
        shade.animate().alpha(0).setDuration(200).withEndAction(() -> { if (!menuOpen) shade.setVisibility(View.GONE); }).start();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restore && beforeMenu != null && beforeMenu.isAttachedToWindow()) beforeMenu.requestFocus();
        else focusPage();
        if (hero != null) restartHero();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (menuOpen) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!down) closeMenu(true);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Past the menu's first or last item the focus stays in the menu (it used to fall to the page behind).
                View f = getCurrentFocus();
                View next = f == null ? null : f.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                if (next == null || !inside(next, menu)) return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && down && Tmdb.hasKey()) {
            View f = getCurrentFocus();
            boolean heroBack = f != null && f == hero && heroAt > 0;   // the banner goes back first, then the menu
            if (!heroBack && (f == null || f.focusSearch(View.FOCUS_LEFT) == null)) {
                openMenu();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK && !fromBookmark && !HOME.equals(current) && Tmdb.hasKey()) {
            if (!down && !e.isCanceled()) showPage(HOME);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean inside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    // MARK: - Pins and recent searches (kept on the TV)

    private SharedPreferences prefs() {
        return getSharedPreferences("explore", MODE_PRIVATE);
    }

    /** {page id, label} the person pinned to the menu. */
    private List<String[]> pins() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("pins", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(new String[] {a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1)});
        } catch (Exception ignored) {
        }
        return out;
    }

    private boolean pinned(String id) {
        for (String[] p : pins()) if (p[0].equals(id)) return true;
        return false;
    }

    private void togglePin(String id, String label) {
        List<String[]> list = pins();
        boolean removed = list.removeIf(p -> p[0].equals(id));
        if (!removed) list.add(new String[] {id, label});
        JSONArray a = new JSONArray();
        for (String[] p : list) a.put(new JSONArray().put(p[0]).put(p[1]));
        prefs().edit().putString("pins", a.toString()).apply();
        showPins();
        Toast.makeText(this, removed ? Ui.tr("Tirado do menu: ", "Unpinned: ") + label : Ui.tr("Fixado no menu: ", "Pinned: ") + label,
                Toast.LENGTH_SHORT).show();
    }

    private List<String> recent() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void remember(String q) {
        if (q == null || q.trim().length() < 2) return;
        List<String> list = recent();
        list.removeIf(x -> x.equalsIgnoreCase(q.trim()));
        list.add(0, q.trim());
        JSONArray a = new JSONArray();
        for (int i = 0; i < list.size() && i < 10; i++) a.put(list.get(i));
        prefs().edit().putString("recent", a.toString()).apply();
    }

    // MARK: - Pages

    private void showPage(String id) {
        current = id;
        int gen = ++generation;
        placed.clear();
        placedKeys.clear();
        hero = null;
        results = null;
        main.removeCallbacks(heroTick);
        main.removeCallbacks(searchSoon);
        page.removeAllViews();
        scroll.top();
        restyleMenu();
        boolean withHero = id.equals(HOME) || id.equals(MOVIES) || id.equals(SHOWS) || id.equals(TOP) || id.startsWith(SERVICE) || id.startsWith(GENRE);
        if (withHero) page.addView(buildHero());
        else if (!id.startsWith(QUERY)) header(pageTitle(id), pageSubtitle(id));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(Math.round(120 * k), 0, 0, 0);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (withHero) rowsLp.topMargin = -Math.round(HERO_BLEED * k);   // the first row sits over the banner's picture
        page.addView(rows, rowsLp);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPadding(0, Math.round(30 * k), 0, 0);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        if (id.equals(SEARCH) || id.startsWith(QUERY)) {
            if (id.startsWith(QUERY)) header(Ui.tr("Buscar", "Search"), pageSubtitle(SEARCH));
            buildSearch(gen, id.startsWith(QUERY) ? id.substring(QUERY.length()) : "");
            return;
        }
        if (id.equals(LIST)) {
            buildList();
            return;
        }
        if (id.equals(CATS)) {
            buildCategories();
            return;
        }
        if (id.equals(SETTINGS)) {
            buildSettings();
            return;
        }
        io.execute(() -> {
            List<Section> list = sections(id);
            List<Object[]> banner = withHero ? heroItems(id) : null;
            main.post(() -> {
                if (gen != generation || isFinishing()) return;
                sections = list;
                if (banner != null) setHero(banner);
                load(gen, list, false);
            });
        });
    }

    private String pageTitle(String id) {
        switch (id) {
            case SEARCH: return Ui.tr("Buscar", "Search");
            case LIST: return Ui.tr("Minha lista", "My list");
            case TOP: return Ui.tr("Top 10 do dia", "Today's top 10");
            case CATS: return Ui.tr("Categorias", "Categories");
            case SETTINGS: return Ui.tr("Ajustes", "Settings");
            default: return Ui.tr("Explorar", "Explore");
        }
    }

    private String pageSubtitle(String id) {
        if (id.equals(LIST)) return Tmdb.signedIn(this) ? Ui.tr("Da sua conta TMDB", "From your TMDB account") + " · " + Tmdb.accountName(this)
                : Ui.tr("Salva nesta TV. Conecte sua conta TMDB em Ajustes para sincronizar.", "Kept on this TV. Connect your TMDB account in Settings to sync.");
        if (id.equals(CATS)) return Ui.tr("OK abre · segure OK para fixar no menu", "OK opens · hold OK to pin it to the menu");
        if (id.equals(SETTINGS)) return Ui.tr("Conta, serviços e o que aparece no menu", "Account, services and what the menu shows");
        return Ui.tr("Filmes, séries e pessoas · dados do TMDB", "Movies, shows and people · data from TMDB");
    }

    private void header(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(120 * k), Math.round(56 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 60, Ui.displaySemibold, TvUi.INK);
        h.setText(title);
        box.addView(h);
        TextView s = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        s.setText(sub);
        s.setPadding(0, Math.round(12 * k), 0, 0);
        box.addView(s);
        page.addView(box, 0);
    }

    private void load(int gen, List<Section> list, boolean personalOnly) {
        int[] left = {0};
        for (Section s : list) if (!personalOnly || s.personal) left[0]++;
        for (int i = 0; i < list.size(); i++) {
            final int slot = i;
            final Section s = list.get(i);
            if (personalOnly && !s.personal) continue;
            io.execute(() -> {
                List<Tmdb.Title> titles;
                try {
                    titles = s.load.call();
                } catch (Exception e) {
                    AppLog.w("explore " + s.title + ": " + e.getMessage());
                    titles = new ArrayList<>();
                }
                final List<Tmdb.Title> got = titles;
                main.post(() -> {
                    if (gen != generation || isFinishing()) return;
                    place(slot, s, got);
                    if (personalOnly) return;
                    left[0]--;
                    status.setVisibility(placed.isEmpty() ? View.VISIBLE : View.GONE);
                    if (placed.isEmpty() && left[0] == 0) {
                        status.setText(Ui.tr("Não deu para buscar os títulos agora. Confira a internet da TV.", "Couldn't fetch titles now. Check the TV's internet."));
                    }
                });
            });
        }
    }

    private void refreshPersonal() {
        if (current.equals(LIST)) {
            showTab(tab, true);
            return;
        }
        if (current.equals(SEARCH) || current.equals(TOP) || current.equals(CATS) || current.equals(SETTINGS)) return;
        load(generation, sections, true);
    }

    /** Puts a section in its slot (they arrive in any order); an unchanged one stays, with the focus where it is. */
    private void place(int slot, Section s, List<Tmdb.Title> titles) {
        StringBuilder keys = new StringBuilder(s.title);
        for (Tmdb.Title t : titles) keys.append(',').append(t.key());
        if (keys.toString().equals(placedKeys.get(slot))) return;
        placedKeys.put(slot, keys.toString());
        View old = placed.remove(slot);
        boolean hadFocus = old != null && old.hasFocus();
        if (old != null) rows.removeView(old);
        if (titles.isEmpty()) {
            if (hadFocus) focusPage();
            return;
        }
        View view = s.grid ? gridSection(s, titles) : TvUi.row(this, k, s.title, titles, this::open, moreOf(s));
        placed.put(slot, view);
        int index = rows.indexOfChild(status) + 1;
        for (Map.Entry<Integer, View> e : placed.entrySet()) {
            if (e.getKey() < slot && e.getValue().getParent() == rows) index = Math.max(index, rows.indexOfChild(e.getValue()) + 1);
        }
        rows.addView(view, Math.min(index, rows.getChildCount()));
        View focus = getCurrentFocus();
        if (hadFocus || (!menuOpen && (focus == null || !inside(focus, page)))) focusPage();
    }

    private Runnable moreOf(Section s) {
        if (s.more != null) return s.more;
        if (s.morePath != null) return () -> GridActivity.open(this, s.title, s.morePath, s.moreKind);
        return null;
    }

    private void open(Tmdb.Title t) {
        if ((current.equals(SEARCH) || current.startsWith(QUERY)) && !query.isEmpty()) remember(query);
        DetailActivity.open(this, t.kind, t.id);
    }

    /** A section shown whole: a title and a grid (the top 10, numbered). */
    private View gridSection(Section s, List<Tmdb.Title> titles) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setPadding(0, Math.round(26 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(s.title);
        box.addView(h);
        TvUi.Grid grid = new TvUi.Grid(this, k, s.ranked ? 5 : 7, this::open);
        if (s.ranked) {
            for (int i = 0; i < titles.size(); i++) {
                View cell = TvUi.poster(this, k, titles.get(i), this::open);
                rank(cell, i + 1);
                grid.add(cell);
            }
        } else {
            grid.add(titles);
        }
        box.addView(grid);
        return box;
    }

    /** A big number over the poster's corner, like the streaming apps' top 10s. */
    private void rank(View cell, int n) {
        FrameLayout card = (FrameLayout) ((ViewGroup) cell).getChildAt(0);
        TextView num = TvUi.text(this, k, 120, Ui.displaySemibold, TvUi.INK);
        num.setText(String.valueOf(n));
        num.setShadowLayer(18 * k, 0, 4 * k, 0xE6000000);
        num.setPadding(Math.round(12 * k), 0, 0, Math.round(4 * k));
        card.addView(num, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
    }

    /** The page's first thing: the banner, or the first focusable thing under the title. */
    private void focusPage() {
        if (menuOpen) return;
        if (hero != null && !heroList.isEmpty()) {
            hero.requestFocus();
            return;
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v != status && v.requestFocus()) return;
        }
    }

    // MARK: - My list: tabs over a grid

    private static final String[][] TABS = {{"list", "Minha lista", "My list"}, {"favorite", "Favoritos", "Favorites"},
            {"seen", "Já assisti", "Seen"}, {"rated", "Suas notas", "Your ratings"}};
    private int tab;
    private LinearLayout tabBar, tabBody;
    private String tabKeys;
    private final Runnable tabSoon = () -> showTab(tab, false);

    private void buildList() {
        status.setVisibility(View.GONE);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setClipChildren(false);
        tabBar.setPadding(0, Math.round(20 * k), 0, Math.round(10 * k));
        for (int i = 0; i < TABS.length; i++) {
            final int which = i;
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
            t.setText(Ui.tr(TABS[i][1], TABS[i][2]));
            t.setPadding(Math.round(32 * k), Math.round(16 * k), Math.round(32 * k), Math.round(16 * k));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                styleTabs();
                if (has && which != tab) {   // like a TV app's tabs: the focus picks, after a short pause
                    tab = which;
                    main.removeCallbacks(tabSoon);
                    main.postDelayed(tabSoon, 260);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(14 * k);
            tabBar.addView(t, lp);
        }
        rows.addView(tabBar);
        tabBody = new LinearLayout(this);
        tabBody.setOrientation(LinearLayout.VERTICAL);
        tabBody.setClipChildren(false);
        rows.addView(tabBody);
        tab = 0;
        tabKeys = null;
        styleTabs();
        showTab(0, false);
        tabBar.getChildAt(0).requestFocus();
    }

    private void styleTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean focused = t.hasFocus(), here = i == tab;
            t.setBackground(focused ? TvUi.round(TvUi.INK, 40 * k) : here ? TvUi.round(0x33FFFFFF, 40 * k) : null);
            t.setTextColor(focused ? 0xFF14161A : here ? TvUi.INK : TvUi.DIM);
            t.setTypeface(here || focused ? Ui.semibold : Ui.medium);
        }
    }

    /** The tab's titles as a grid built as you scroll; `quiet`: only if they changed (back from a title page). */
    private void showTab(int which, boolean quiet) {
        int gen = generation;
        String kind = TABS[which][0];
        if (!quiet) {
            tabKeys = null;
            tabBody.removeAllViews();
            tabBody.addView(note(Ui.tr("Carregando…", "Loading…")));
        }
        io.execute(() -> {
            List<Tmdb.Title> titles = new ArrayList<>();
            String problem = null;
            try {
                switch (kind) {
                    case "list": titles = Tmdb.watchlist(this); break;
                    case "seen": titles = Tmdb.seen(this); break;
                    default:
                        if (Tmdb.signedIn(this)) titles = Tmdb.accountList(this, kind);
                        else problem = Ui.tr("Conecte sua conta TMDB em Ajustes para ver favoritos e notas.",
                                "Connect your TMDB account in Settings to see favorites and ratings.");
                }
            } catch (Exception e) {
                problem = Ui.tr("Não deu para carregar: ", "Couldn't load: ") + e.getMessage();
            }
            List<Tmdb.Title> got = titles;
            String why = problem;
            main.post(() -> {
                if (gen != generation || which != tab || isFinishing()) return;
                StringBuilder keys = new StringBuilder(kind);
                for (Tmdb.Title t : got) keys.append(',').append(t.key());
                if (quiet && keys.toString().equals(tabKeys)) return;
                tabKeys = keys.toString();
                boolean hadFocus = tabBody.hasFocus();
                tabBody.removeAllViews();
                if (why != null || got.isEmpty()) {
                    tabBody.addView(note(why != null ? why : Ui.tr("Nada aqui ainda. Abra um título para marcar.", "Nothing here yet. Open a title to add it.")));
                } else {
                    TextView count = note(got.size() == 1 ? Ui.tr("1 título", "1 title") : got.size() + Ui.tr(" títulos", " titles"));
                    tabBody.addView(count);
                    TvUi.Grid grid = new TvUi.Grid(this, k, 7, this::open);
                    grid.add(got);
                    tabBody.addView(grid);
                }
                if (hadFocus) tabBar.getChildAt(tab).requestFocus();
            });
        });
    }

    private TextView note(String text) {
        TextView t = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        t.setText(text);
        t.setPadding(0, Math.round(24 * k), 0, 0);
        return t;
    }

    // MARK: - Categories and settings

    private void buildCategories() {
        status.setVisibility(View.GONE);
        TvUi.Flow chips = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        chips.setPadding(Math.round(6 * k), Math.round(30 * k), Math.round(120 * k), 0);
        for (String[] g : GENRES) {
            String id = GENRE + g[0], label = Ui.tr(g[1], g[2]);
            TextView b = TvUi.button(this, k, (pinned(id) ? "★  " : "") + label, v -> showPage(id));
            b.setOnLongClickListener(v -> {
                togglePin(id, label);
                ((TextView) v).setText((pinned(id) ? "★  " : "") + label);
                return true;
            });
            chips.addView(b);
        }
        rows.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chips.getChildAt(0).requestFocus();
    }

    private void buildSettings() {
        status.setVisibility(View.GONE);
        TvUi.Flow buttons = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        buttons.setPadding(Math.round(6 * k), Math.round(30 * k), Math.round(120 * k), 0);
        String account = Tmdb.signedIn(this) ? Ui.tr("Conta TMDB: ", "TMDB account: ") + Tmdb.accountName(this) : Ui.tr("Conectar conta TMDB", "Connect TMDB account");
        buttons.addView(TvUi.button(this, k, account, v -> startActivity(new Intent(this, TmdbLoginActivity.class))));
        buttons.addView(TvUi.button(this, k, Ui.tr("Serviços e apps", "Services and apps"), v -> startActivity(new Intent(this, ServicesActivity.class))));
        rows.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(Ui.tr("Fixados no menu", "Pinned to the menu"));
        h.setPadding(0, Math.round(50 * k), 0, Math.round(16 * k));
        rows.addView(h);
        List<String[]> pins = pins();
        if (pins.isEmpty()) {
            rows.addView(note(Ui.tr("Nada fixado. Em Categorias ou nas buscas recentes, segure OK para fixar.",
                    "Nothing pinned. In Categories or recent searches, hold OK to pin.")));
        }
        for (String[] p : pins) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Math.round(28 * k), Math.round(20 * k), Math.round(28 * k), Math.round(20 * k));
            TextView name = TvUi.text(this, k, 28, Ui.medium, TvUi.INK);
            name.setText(p[1]);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView hint = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
            hint.setText(Ui.tr("OK tira do menu", "OK unpins"));
            row.addView(hint);
            TvUi.softFocus(row, k, 18 * k);
            row.setOnClickListener(v -> {
                togglePin(p[0], p[1]);
                showPage(SETTINGS);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1100 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(12 * k);
            rows.addView(row, lp);
        }
        buttons.getChildAt(0).requestFocus();
    }

    private static String[] genre(String id) {
        String key = id.startsWith(GENRE) ? id.substring(GENRE.length()) : id;
        for (String[] g : GENRES) if (g[0].equals(key)) return g;
        return null;
    }

    // MARK: - Banner

    private FrameLayout hero;
    private ImageView heroImage, heroLogo;
    private TextView heroTag, heroTitle, heroMeta, heroOverview, heroButton;
    private LinearLayout heroDots;
    private final List<Object[]> heroList = new ArrayList<>();   // {title, tag}
    private int heroAt;
    private final Runnable heroTick = () -> {
        if (hero == null || menuOpen || heroList.size() < 2) return;
        stepHero(1);
    };

    private void restartHero() {
        main.removeCallbacks(heroTick);
        main.postDelayed(heroTick, HERO_MS);
    }

    private void stepHero(int by) {
        heroAt = (heroAt + by + heroList.size()) % heroList.size();
        showHero();
        restartHero();
    }

    private View buildHero() {
        heroList.clear();
        hero = new FrameLayout(this);
        int bleed = Math.round((HERO_H + HERO_BLEED) * k);   // the picture runs on behind the first row
        FadeOut picture = new FadeOut(this, 0.42f);
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(heroImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF00B0D12, 0x990B0D12, 0x000B0D12}));
        picture.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        hero.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        heroTag = TvUi.text(this, k, 24, Ui.semibold, TvUi.ACCENT);
        heroTag.setLetterSpacing(0.08f);
        text.addView(heroTag);
        heroLogo = new ImageView(this);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setMaxWidth(Math.round(620 * k));
        heroLogo.setMaxHeight(Math.round(190 * k));
        heroLogo.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoLp.topMargin = Math.round(18 * k);
        text.addView(heroLogo, logoLp);
        heroTitle = TvUi.text(this, k, 76, Ui.displaySemibold, TvUi.INK);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, Math.round(14 * k), 0, 0);
        text.addView(heroTitle);
        heroMeta = TvUi.text(this, k, 26, Ui.medium, TvUi.DIM);
        heroMeta.setPadding(0, Math.round(18 * k), 0, 0);
        text.addView(heroMeta);
        heroOverview = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        heroOverview.setMaxLines(3);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(6 * k, 1);
        heroOverview.setPadding(0, Math.round(14 * k), 0, 0);
        text.addView(heroOverview);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Math.round(28 * k), 0, 0);
        heroButton = TvUi.text(this, k, 27, Ui.medium, TvUi.INK);
        heroButton.setText(Ui.tr("Ver detalhes", "Details"));
        heroButton.setPadding(Math.round(34 * k), Math.round(16 * k), Math.round(34 * k), Math.round(16 * k));
        heroButton.setBackground(TvUi.round(0x33FFFFFF, 40 * k));
        bar.addView(heroButton);
        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        heroDots.setPadding(Math.round(30 * k), 0, 0, 0);
        bar.addView(heroDots);
        text.addView(bar);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(900 * k), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tl.leftMargin = Math.round(120 * k);
        tl.bottomMargin = Math.round((40 + HERO_BLEED) * k);
        hero.addView(text, tl);
        hero.setFocusable(true);
        hero.setFocusableInTouchMode(true);
        hero.setOnFocusChangeListener((v, has) -> {
            heroButton.setBackground(TvUi.round(has ? TvUi.INK : 0x33FFFFFF, 40 * k));
            heroButton.setTextColor(has ? 0xFF14161A : TvUi.INK);
        });
        hero.setOnClickListener(v -> {
            if (!heroList.isEmpty()) open((Tmdb.Title) heroList.get(heroAt)[0]);
        });
        hero.setOnKeyListener((v, code, e) -> {
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT, back = code == KeyEvent.KEYCODE_DPAD_LEFT && heroAt > 0;
            if ((!right && !back) || heroList.size() < 2) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) stepHero(right ? 1 : -1);
            return true;
        });
        hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        return hero;
    }

    private void setHero(List<Object[]> list) {
        if (list.isEmpty()) {   // nothing with a backdrop: a plain title instead
            page.removeView(hero);
            hero = null;
            ((LinearLayout.LayoutParams) rows.getLayoutParams()).topMargin = 0;
            rows.requestLayout();
            header(Ui.tr("Explorar", "Explore"), pageSubtitle(current));
            return;
        }
        heroList.clear();
        heroList.addAll(list);
        heroAt = 0;
        heroDots.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(12 * k), Math.round(12 * k));
            lp.rightMargin = Math.round(10 * k);
            heroDots.addView(dot, lp);
        }
        showHero();
        if (!menuOpen) hero.requestFocus();
        restartHero();
    }

    private void showHero() {
        Tmdb.Title t = (Tmdb.Title) heroList.get(heroAt)[0];
        heroTag.setText(((String) heroList.get(heroAt)[1]).toUpperCase(Locale.ROOT));
        heroImage.setAlpha(0.35f);
        Images.load(heroImage, Tmdb.BACKDROP + t.backdrop);
        heroImage.animate().alpha(1).setDuration(500).start();
        heroTitle.setText(t.name);
        heroTitle.setVisibility(View.VISIBLE);
        heroLogo.setVisibility(View.GONE);
        List<String> meta = new ArrayList<>();
        meta.add(t.kind.equals("movie") ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Show"));
        if (!t.year.isEmpty()) meta.add(t.year);
        if (t.vote > 0) meta.add(String.format(Ui.PT, "★ %.1f", t.vote));
        if (Tmdb.isSeen(this, t.kind, t.id)) meta.add("✓ " + Ui.tr("Assistido", "Seen"));
        heroMeta.setText(TextUtils.join("  ·  ", meta));
        heroOverview.setText(t.overview);
        for (int i = 0; i < heroDots.getChildCount(); i++) {
            heroDots.getChildAt(i).setBackground(TvUi.round(i == heroAt ? TvUi.INK : 0x59FFFFFF, 6 * k));
        }
        if (hero.hasFocus()) followFocus(hero);
        int at = heroAt;
        FrameLayout shown = hero;
        io.execute(() -> {
            String logo = logo(t);
            if (logo == null) return;
            main.post(() -> {
                if (hero != shown || heroAt != at) return;
                Images.load(heroLogo, logo);
                heroLogo.setVisibility(View.VISIBLE);
                heroTitle.setVisibility(View.GONE);
            });
        });
    }

    /** The title's logo (a transparent PNG) in the app's language, else English, else without text; null if none. */
    private static String logo(Tmdb.Title t) {
        try {
            String mine = Tmdb.lang().substring(0, 2);
            JSONArray logos = Tmdb.get("/" + t.kind + "/" + t.id + "/images?include_image_language=" + mine + ",en,null")
                    .optJSONArray("logos");
            String best = null;
            int bestRank = 9;
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.optJSONObject(i);
                String path = l.optString("file_path");
                if (!path.endsWith(".png")) continue;
                String lang = l.optString("iso_639_1");
                int rank = lang.equals(mine) ? 0 : lang.equals("en") ? 1 : 2;
                if (rank < bestRank) {
                    bestRank = rank;
                    best = path;
                }
            }
            return best == null ? null : "https://image.tmdb.org/t/p/w500" + best;
        } catch (Exception e) {
            return null;
        }
    }

    /** The banner: picks for the person and what's big today (home), or the page's own top titles. */
    private List<Object[]> heroItems(String id) {
        List<Object[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            if (id.equals(HOME)) {
                String picked = Ui.tr("Recomendado para você", "Picked for you");
                List<Tmdb.Title> mine = forYou();
                for (int i = 0; i < mine.size() && out.size() < 2; i++) addHero(out, seen, mine.get(i), picked);
                for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/trending/all/day"), "movie")) {
                    if (out.size() >= 7) break;
                    addHero(out, seen, t, Ui.tr("Em alta hoje", "Trending today"));
                }
            } else if (id.equals(TOP)) {
                List<Tmdb.Title> tv = top(Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv"));
                List<Tmdb.Title> movies = top(Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie"));
                for (int i = 0; i < 4; i++) {
                    if (i < tv.size()) addHero(out, seen, tv.get(i), Ui.tr("Nº " + (i + 1) + " em séries hoje", "No. " + (i + 1) + " in shows today"));
                    if (i < movies.size()) addHero(out, seen, movies.get(i), Ui.tr("Nº " + (i + 1) + " em filmes hoje", "No. " + (i + 1) + " in movies today"));
                }
            } else if (id.equals(MOVIES) || id.equals(SHOWS)) {
                boolean movie = id.equals(MOVIES);
                for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/trending/" + id + "/day"), id)) {
                    if (out.size() >= 6) break;
                    addHero(out, seen, t, movie ? Ui.tr("Filme em alta", "Trending movie") : Ui.tr("Série em alta", "Trending show"));
                }
            } else if (id.startsWith(GENRE)) {
                String[] g = genre(id);
                if (g != null) {
                    String tag = Ui.tr("Em alta em ", "Trending in ") + Ui.tr(g[1], g[2]);
                    for (Tmdb.Title t : mix(g[4] == null ? new ArrayList<>() : discover("tv", g[4] + "&sort_by=popularity.desc&vote_count.gte=50"),
                            g[3] == null ? new ArrayList<>() : discover("movie", g[3] + "&sort_by=popularity.desc&vote_count.gte=100"))) {
                        if (out.size() >= 6) break;
                        addHero(out, seen, t, tag);
                    }
                }
            } else {
                Service s = service(id);
                if (s != null) {
                    for (Tmdb.Title t : mix(discover("tv", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"),
                            discover("movie", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"))) {
                        if (out.size() >= 6) break;
                        addHero(out, seen, t, Ui.tr("Em alta na ", "Trending on ") + s.name);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("explore banner: " + e.getMessage());
        }
        return out;
    }

    private static void addHero(List<Object[]> out, Set<String> seen, Tmdb.Title t, String tag) {
        if (t.backdrop.isEmpty() || "null".equals(t.backdrop) || !seen.add(t.key())) return;
        out.add(new Object[] {t, tag});
    }

    /** Its lower part fades to transparent (a mask, not a dark band), so the banner melts into the background. */
    private static final class FadeOut extends FrameLayout {
        private final float from;
        private final android.graphics.Paint mask = new android.graphics.Paint();
        private int maskH;

        FadeOut(Context c, float from) {
            super(c);
            this.from = from;
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            setWillNotDraw(false);
        }

        @Override protected void dispatchDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight();
            if (h != maskH) {
                maskH = h;
                mask.setShader(new android.graphics.LinearGradient(0, h * from, 0, h, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            }
            int layer = c.saveLayer(0, 0, w, h, null);
            super.dispatchDraw(c);
            c.drawRect(0, h * from, w, h, mask);
            c.restoreToCount(layer);
        }
    }

    // MARK: - Search

    private TvUi.Grid results;
    private TextView resultsTitle, pinSearch;
    private String query = "";
    private final Runnable searchSoon = () -> search(generation);

    private void buildSearch(int gen, String initial) {
        status.setVisibility(View.GONE);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * k);
        field.setTextColor(TvUi.INK);
        field.setHintTextColor(TvUi.FAINT);
        field.setTypeface(Ui.medium);
        field.setHint(Ui.tr("Filme, série ou pessoa", "Movie, show or person"));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(Math.round(36 * k), Math.round(22 * k), Math.round(36 * k), Math.round(22 * k));
        field.setBackground(TvUi.round(0x1FFFFFFF, 40 * k));
        field.setOnFocusChangeListener((v, has) -> v.setBackground(TvUi.round(has ? 0x47FFFFFF : 0x1FFFFFFF, 40 * k)));
        top.addView(field, new LinearLayout.LayoutParams(Math.round(1100 * k), ViewGroup.LayoutParams.WRAP_CONTENT));
        pinSearch = TvUi.button(this, k, "", v -> {
            if (query.isEmpty()) return;
            togglePin(QUERY + query, "“" + query + "”");
            pinLabel();
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = Math.round(24 * k);
        top.addView(pinSearch, pl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(16 * k);
        rows.addView(top, lp);
        List<String> recent = recent();
        if (!recent.isEmpty()) {
            TvUi.Flow chips = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
            chips.setPadding(Math.round(6 * k), Math.round(26 * k), Math.round(120 * k), 0);
            for (String q : recent) {
                TextView b = TvUi.button(this, k, "↺  " + q, v -> field.setText(q));
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k);
                b.setOnLongClickListener(v -> {
                    togglePin(QUERY + q, "“" + q + "”");
                    return true;
                });
                chips.addView(b);
            }
            rows.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        resultsTitle = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        resultsTitle.setPadding(0, Math.round(40 * k), 0, 0);
        rows.addView(resultsTitle);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                pinLabel();
                main.removeCallbacks(searchSoon);
                main.postDelayed(searchSoon, 600);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
            main.removeCallbacks(searchSoon);
            remember(query);
            search(generation);
            return true;
        });
        query = initial;
        if (!initial.isEmpty()) field.setText(initial);
        pinLabel();
        search(gen);
        field.requestFocus();
    }

    private void pinLabel() {
        if (pinSearch == null) return;
        pinSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        pinSearch.setText(pinned(QUERY + query) ? "★  " + Ui.tr("Fixada no menu", "Pinned") : "☆  " + Ui.tr("Fixar no menu", "Pin to menu"));
    }

    /** Empty: what's trending this week; otherwise TMDB's multi search (movies, shows, people), three pages. */
    private void search(int gen) {
        String q = query;
        resultsTitle.setText(q.isEmpty() ? Ui.tr("Em alta na semana", "Trending this week") : Ui.tr("Buscando…", "Searching…"));
        io.execute(() -> {
            List<Tmdb.Title> found = new ArrayList<>();
            try {
                if (q.isEmpty()) {
                    found.addAll(Tmdb.titles(Tmdb.get("/trending/all/week"), "movie"));
                } else {
                    String path = "/search/multi?include_adult=false&query=" + URLEncoder.encode(q, "UTF-8");
                    for (int p = 1; p <= 3; p++) found.addAll(Tmdb.titles(Tmdb.get(path + "&page=" + p), "movie"));
                }
            } catch (Exception e) {
                AppLog.w("explore search: " + e.getMessage());
            }
            main.post(() -> {
                if (gen != generation || !q.equals(query) || isFinishing()) return;
                if (results != null) rows.removeView(results);
                results = new TvUi.Grid(this, k, 7, this::open);
                results.add(found);
                rows.addView(results);
                resultsTitle.setText(q.isEmpty() ? Ui.tr("Em alta na semana", "Trending this week")
                        : found.isEmpty() ? Ui.tr("Nada encontrado para “", "Nothing found for “") + q + "”"
                        : Ui.tr("Resultados para “", "Results for “") + q + "”");
            });
        });
    }

    // MARK: - What each page shows

    private List<Section> sections(String id) {
        List<Section> out = new ArrayList<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (id.startsWith(GENRE)) {
            String[] g = genre(id);
            if (g != null) genrePage(out, g, today);
            return out;
        }
        switch (id) {
            case TOP:
                out.add(new Section(Ui.tr("Top 10 séries hoje", "Top 10 shows today"), () -> top(Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv"))).ranked());
                out.add(new Section(Ui.tr("Top 10 filmes hoje", "Top 10 movies today"), () -> top(Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie"))).ranked());
                return out;
            case MOVIES: {
                out.add(new Section(Ui.tr("Em alta na semana", "Trending this week"), () -> mine("movie", Tmdb.titles(Tmdb.get("/trending/movie/week"), "movie"))));
                String fresh = "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=40" + servicesParam();
                out.add(new Section(Ui.tr("Estreias recentes no streaming", "New on streaming"), () -> discover("movie", fresh))
                        .more(discoverPath("movie", fresh), "movie"));
                out.add(new Section(Ui.tr("Nos cinemas", "In theaters"), () -> Tmdb.titles(Tmdb.get("/movie/now_playing?region=" + Tmdb.REGION), "movie"))
                        .more("/movie/now_playing?region=" + Tmdb.REGION, "movie"));
                out.add(new Section(Ui.tr("Em breve nos cinemas", "Coming to theaters"), () -> Tmdb.titles(Tmdb.get("/movie/upcoming?region=" + Tmdb.REGION), "movie"))
                        .more("/movie/upcoming?region=" + Tmdb.REGION, "movie"));
                out.add(new Section(Ui.tr("Mais bem avaliados", "Top rated"), () -> Tmdb.titles(Tmdb.get("/movie/top_rated"), "movie")).more("/movie/top_rated", "movie"));
                genres(out, "movie");
                return out;
            }
            case SHOWS:
                out.add(new Section(Ui.tr("Em alta na semana", "Trending this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/trending/tv/week"), "tv"))));
                out.add(new Section(Ui.tr("Episódios novos hoje", "New episodes today"), () -> mine("tv", Tmdb.titles(Tmdb.get("/tv/airing_today"), "tv")))
                        .more("/tv/airing_today", "tv"));
                out.add(new Section(Ui.tr("No ar esta semana", "On the air this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/tv/on_the_air"), "tv")))
                        .more("/tv/on_the_air", "tv"));
                out.add(new Section(Ui.tr("Mais bem avaliadas", "Top rated"), () -> Tmdb.titles(Tmdb.get("/tv/top_rated"), "tv")).more("/tv/top_rated", "tv"));
                genres(out, "tv");
                return out;
            case HOME: {
                out.add(new Section(Ui.tr("Minha lista", "My list"), () -> Tmdb.watchlist(this)).personal().more(() -> showPage(LIST)));
                out.add(new Section(Ui.tr("Recomendado para você", "Recommended for you"), this::forYou).personal());
                List<Tmdb.Title> seen = Tmdb.seen(this);
                Tmdb.Title last = seen.isEmpty() ? null : seen.get(0);
                if (last != null) {
                    out.add(new Section(Ui.tr("Porque você viu ", "Because you watched ") + last.name,
                            () -> fresh(Tmdb.titles(Tmdb.get("/" + last.kind + "/" + last.id + "/recommendations"), last.kind)))
                            .personal().more("/" + last.kind + "/" + last.id + "/recommendations", last.kind));
                }
                out.add(new Section(Ui.tr("Em alta hoje", "Trending today"), () -> Tmdb.titles(Tmdb.get("/trending/all/day"), "movie"))
                        .more("/trending/all/day", "movie"));
                out.add(new Section(Ui.tr("Séries em alta na semana", "Trending shows this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/trending/tv/week"), "tv")))
                        .more(() -> showPage(SHOWS)));
                out.add(new Section(Ui.tr("Filmes em alta na semana", "Trending movies this week"), () -> mine("movie", Tmdb.titles(Tmdb.get("/trending/movie/week"), "movie")))
                        .more(() -> showPage(MOVIES)));
                int n = 0;
                for (Service s : services()) {
                    if (n++ >= 6) break;
                    out.add(new Section(Ui.tr("Em alta na ", "Trending on ") + s.name, () -> mix(
                            discover("tv", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"),
                            discover("movie", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc")))
                            .more(() -> showPage(SERVICE + s.key)));
                }
                out.add(new Section(Ui.tr("Favoritos", "Favorites"), () -> Tmdb.accountList(this, "favorite")).personal());
                out.add(new Section(Ui.tr("Suas notas", "Your ratings"), () -> Tmdb.accountList(this, "rated")).personal());
                out.add(new Section(Ui.tr("Pessoas em alta", "Trending people"), () -> Tmdb.titles(Tmdb.get("/trending/person/week"), "person"))
                        .more("/trending/person/week", "person"));
                return out;
            }
            default: {
                Service s = service(id);
                if (s == null) return out;
                String p = "&with_watch_providers=" + s.ids;
                out.add(new Section(Ui.tr("Séries em alta", "Trending shows"), () -> discover("tv", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("tv", p + "&sort_by=popularity.desc"), "tv"));
                out.add(new Section(Ui.tr("Filmes em alta", "Trending movies"), () -> discover("movie", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("movie", p + "&sort_by=popularity.desc"), "movie"));
                String fresh = p + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=20";
                out.add(new Section(Ui.tr("Filmes recém-chegados", "New movies"), () -> discover("movie", fresh)).more(discoverPath("movie", fresh), "movie"));
                String newShows = p + "&sort_by=first_air_date.desc&first_air_date.lte=" + today + "&vote_count.gte=10";
                out.add(new Section(Ui.tr("Séries novas", "New shows"), () -> discover("tv", newShows)).more(discoverPath("tv", newShows), "tv"));
                String best = p + "&sort_by=vote_average.desc&vote_count.gte=500";
                out.add(new Section(Ui.tr("Séries mais bem avaliadas", "Top rated shows"), () -> discover("tv", best)).more(discoverPath("tv", best), "tv"));
                out.add(new Section(Ui.tr("Filmes mais bem avaliados", "Top rated movies"), () -> discover("movie", best)).more(discoverPath("movie", best), "movie"));
                for (String[] g : GENRES) {
                    if (g[3] == null || g[4] == null || !(g[0].equals("comedia") || g[0].equals("acao") || g[0].equals("animacao") || g[0].equals("documentarios"))) continue;
                    out.add(new Section(Ui.tr(g[1], g[2]), () -> mix(discover("tv", p + g[4] + "&sort_by=popularity.desc"),
                            discover("movie", p + g[3] + "&sort_by=popularity.desc"))).more(() -> showPage(GENRE + g[0])));
                }
                return out;
            }
        }
    }

    /** A category's page: trending on the person's services, its shows and movies, the best rated, the newest, by service. */
    private void genrePage(List<Section> out, String[] g, String today) {
        String movie = g[3], tv = g[4], svc = servicesParam();
        out.add(new Section(Ui.tr("Em alta nos seus serviços", "Trending on your services"), () -> mix(
                tv == null ? new ArrayList<>() : discover("tv", tv + "&sort_by=popularity.desc" + svc),
                movie == null ? new ArrayList<>() : discover("movie", movie + "&sort_by=popularity.desc" + svc))));
        if (tv != null) {
            out.add(new Section(Ui.tr("Séries", "Shows"), () -> discover("tv", tv + "&sort_by=popularity.desc&vote_count.gte=50"))
                    .more(discoverPath("tv", tv + "&sort_by=popularity.desc&vote_count.gte=50"), "tv"));
        }
        if (movie != null) {
            out.add(new Section(Ui.tr("Filmes", "Movies"), () -> discover("movie", movie + "&sort_by=popularity.desc&vote_count.gte=100"))
                    .more(discoverPath("movie", movie + "&sort_by=popularity.desc&vote_count.gte=100"), "movie"));
            String fresh = movie + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=20";
            out.add(new Section(Ui.tr("Lançamentos", "New releases"), () -> discover("movie", fresh)).more(discoverPath("movie", fresh), "movie"));
        }
        out.add(new Section(Ui.tr("Mais bem avaliados", "Top rated"), () -> mix(
                tv == null ? new ArrayList<>() : discover("tv", tv + "&sort_by=vote_average.desc&vote_count.gte=300"),
                movie == null ? new ArrayList<>() : discover("movie", movie + "&sort_by=vote_average.desc&vote_count.gte=500"))));
        int n = 0;
        for (Service s : services()) {
            if (n++ >= 4) break;
            String p = "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc";
            out.add(new Section(Ui.tr("Na ", "On ") + s.name, () -> mix(
                    tv == null ? new ArrayList<>() : discover("tv", tv + p),
                    movie == null ? new ArrayList<>() : discover("movie", movie + p))));
        }
    }

    private void genres(List<Section> out, String kind) {
        boolean movie = kind.equals("movie");
        String services = servicesParam();
        for (String[] g : GENRES) {
            String params = movie ? g[3] : g[4];
            if (params == null) continue;
            String p = params + "&sort_by=popularity.desc&vote_count.gte=" + (movie ? 200 : 100) + services;
            out.add(new Section(Ui.tr(g[1], g[2]), () -> discover(kind, p)).more(discoverPath(kind, p), kind));
        }
    }

    private static List<Tmdb.Title> top(List<Tmdb.Title> list) {
        return list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list;
    }

    // MARK: - Services and TMDB helpers

    /** The services with an app (or all, with "show all"), one per app, most watched first. Asked once. */
    private synchronized List<Service> services() {
        if (services != null) return services;
        List<Service> out = new ArrayList<>();
        try {
            Map<String, Service> byApp = new LinkedHashMap<>();
            Map<String, StringBuilder> ids = new HashMap<>();
            for (Providers.Provider p : Providers.usable(this)) {
                String app = Providers.app(this, p.id, p.name);
                String key = app != null ? app : "p" + p.id;
                if (!byApp.containsKey(key)) {
                    byApp.put(key, new Service(key, p.name, "", p.logo));
                    ids.put(key, new StringBuilder());
                }
                StringBuilder b = ids.get(key);
                b.append(b.length() == 0 ? "" : "|").append(p.id);
            }
            for (Service s : byApp.values()) out.add(new Service(s.key, s.name, ids.get(s.key).toString(), s.logo));
        } catch (Exception e) {
            AppLog.w("explore services: " + e.getMessage());
        }
        if (out.size() > 12) out = new ArrayList<>(out.subList(0, 12));
        services = out;
        return out;
    }

    private Service service(String pageId) {
        String key = pageId.startsWith(SERVICE) ? pageId.substring(SERVICE.length()) : pageId;
        for (Service s : services()) if (s.key.equals(key)) return s;
        return null;
    }

    /** Only the person's services, as discover parameters ("" when there are none; region only with "show all"). */
    private String servicesParam() {
        if (Prefs.showAllProviders()) return "&watch_region=" + Tmdb.REGION;
        StringBuilder ids = new StringBuilder();
        for (Service s : services()) ids.append(ids.length() == 0 ? "" : "|").append(s.ids);
        return ids.length() == 0 ? "" : "&watch_region=" + Tmdb.REGION + "&with_watch_providers=" + ids
                + "&with_watch_monetization_types=flatrate";
    }

    private static String discoverPath(String kind, String params) {
        String region = params.contains("watch_region") ? "" : "&watch_region=" + Tmdb.REGION;
        return "/discover/" + kind + "?include_adult=false" + region + params;
    }

    private List<Tmdb.Title> discover(String kind, String params) throws Exception {
        return Tmdb.titles(Tmdb.get(discoverPath(kind, params)), kind);
    }

    /** Trending, kept to the person's services unless "show all" is on. */
    private List<Tmdb.Title> mine(String kind, List<Tmdb.Title> trending) throws Exception {
        if (Prefs.showAllProviders()) return trending;
        String services = servicesParam();
        if (services.isEmpty()) return trending;
        Set<Integer> ok = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/discover/" + kind + "?sort_by=popularity.desc&page=" + page + services), kind)) ok.add(t.id);
        }
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : trending) if (ok.contains(t.id)) out.add(t);
        return out.size() >= 5 ? out : trending;
    }

    /** From what was seen and saved: each one's recommendations, the ones that come up most often first. */
    private List<Tmdb.Title> forYou() throws Exception {
        List<Tmdb.Title> seeds = new ArrayList<>(Tmdb.seen(this));
        try { seeds.addAll(Tmdb.watchlist(this)); } catch (Exception ignored) {}
        Set<String> skip = new HashSet<>();
        for (Tmdb.Title t : seeds) skip.add(t.key());
        Map<String, Tmdb.Title> byKey = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        int used = 0;
        for (Tmdb.Title s : seeds) {
            if (used++ >= 8) break;
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/" + s.kind + "/" + s.id + "/recommendations"), s.kind)) {
                if (skip.contains(t.key())) continue;
                byKey.putIfAbsent(t.key(), t);
                count.merge(t.key(), 1, Integer::sum);
            }
        }
        List<Tmdb.Title> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int byCount = Integer.compare(count.get(b.key()), count.get(a.key()));
            return byCount != 0 ? byCount : Double.compare(b.vote, a.vote);
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    private List<Tmdb.Title> fresh(List<Tmdb.Title> list) {
        Set<String> seen = new HashSet<>();
        for (Tmdb.Title t : Tmdb.seen(this)) seen.add(t.key());
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : list) if (!seen.contains(t.key())) out.add(t);
        return out;
    }

    private static List<Tmdb.Title> mix(List<Tmdb.Title> a, List<Tmdb.Title> b) {
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }
}
