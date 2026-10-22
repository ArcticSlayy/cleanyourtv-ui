package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
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
 * Search, Home, My list, Today's top 10, Movies, Shows and one page per streaming service with an app.
 * With EXTRA_SECTION = "watchlist" (the remote's bookmark button) it opens on My list.
 */
public class ExploreActivity extends Activity {
    static final String EXTRA_SECTION = "section";
    private static final String HOME = "home", SEARCH = "search", LIST = "watchlist", TOP = "top", MOVIES = "movie",
            SHOWS = "tv", SERVICE = "service:";
    private static final long HERO_MS = 9000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private float k;
    private TvUi.Scroll scroll;
    private LinearLayout page, rows, menu, menuServices;
    private TextView status, servicesHeader;
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

        Section grid() {
            grid = true;
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
        if (resumedOnce && Tmdb.hasKey()) refreshPersonal();   // back from a title page: the list and the picks may have changed
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
        TextView brand = TvUi.text(this, k, 24, Ui.semibold, TvUi.FAINT);
        brand.setText(Ui.tr("EXPLORAR", "EXPLORE"));
        brand.setLetterSpacing(0.12f);
        brand.setPadding(Math.round(26 * k), 0, 0, Math.round(26 * k));
        menu.addView(brand);
        menuItem(menu, SEARCH, Ui.tr("Buscar", "Search"), null);
        menuItem(menu, HOME, Ui.tr("Início", "Home"), null);
        menuItem(menu, LIST, Ui.tr("Minha lista", "My list"), null);
        menuItem(menu, TOP, Ui.tr("Top 10 do dia", "Today's top 10"), null);
        menuItem(menu, MOVIES, Ui.tr("Filmes", "Movies"), null);
        menuItem(menu, SHOWS, Ui.tr("Séries", "Shows"), null);
        servicesHeader = TvUi.text(this, k, 22, Ui.semibold, TvUi.FAINT);
        servicesHeader.setText(Ui.tr("SEUS SERVIÇOS", "YOUR SERVICES"));
        servicesHeader.setLetterSpacing(0.12f);
        servicesHeader.setPadding(Math.round(26 * k), Math.round(30 * k), 0, Math.round(14 * k));
        servicesHeader.setVisibility(View.GONE);
        menu.addView(servicesHeader);
        menuServices = new LinearLayout(this);
        menuServices.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuServices);
        box.addView(menu);
        box.setVisibility(View.GONE);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return box;
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
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && down && Tmdb.hasKey()) {
            View f = getCurrentFocus();
            if (f == null || f.focusSearch(View.FOCUS_LEFT) == null) {   // at the left edge: the menu
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
        boolean withHero = id.equals(HOME) || id.equals(MOVIES) || id.equals(SHOWS) || id.startsWith(SERVICE);
        if (withHero) page.addView(buildHero());
        else header(pageTitle(id), pageSubtitle(id));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(Math.round(120 * k), 0, 0, 0);
        page.addView(rows);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPadding(0, Math.round(30 * k), 0, 0);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        if (id.equals(SEARCH)) {
            buildSearch(gen);
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
            default: return Ui.tr("Explorar", "Explore");
        }
    }

    private String pageSubtitle(String id) {
        if (id.equals(LIST)) return Tmdb.signedIn(this) ? Ui.tr("Da sua conta TMDB", "From your TMDB account") + " · " + Tmdb.accountName(this)
                : Ui.tr("Salva nesta TV. Conecte sua conta TMDB em Ajustes para sincronizar.", "Kept on this TV. Connect your TMDB account in Settings to sync.");
        if (id.equals(TOP)) return Ui.tr("O que mais se assiste hoje · dados do TMDB", "What's most watched today · data from TMDB");
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
                    status.setVisibility(placed.isEmpty() && left[0] == 0 ? View.VISIBLE : placed.isEmpty() ? View.VISIBLE : View.GONE);
                    if (placed.isEmpty() && left[0] == 0) status.setText(current.equals(LIST)
                            ? Ui.tr("Sua lista está vazia. Abra um título e escolha \"Minha lista\".", "Your list is empty. Open a title and pick \"My list\".")
                            : Ui.tr("Não deu para buscar os títulos agora. Confira a internet da TV.", "Couldn't fetch titles now. Check the TV's internet."));
                });
            });
        }
    }

    private void refreshPersonal() {
        if (current.equals(SEARCH) || current.equals(TOP)) return;
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
        if (hadFocus || (!menuOpen && (focus == null || !inPage(focus)))) focusPage();
    }

    private Runnable moreOf(Section s) {
        if (s.more != null) return s.more;
        if (s.morePath != null) return () -> GridActivity.open(this, s.title, s.morePath, s.moreKind);
        return null;
    }

    private void open(Tmdb.Title t) {
        DetailActivity.open(this, t.kind, t.id);
    }

    /** A section shown whole: a title and a grid (My list; the top 10, numbered). */
    private View gridSection(Section s, List<Tmdb.Title> titles) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setPadding(0, Math.round(26 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(s.title);
        box.addView(h);
        TvUi.Grid grid = new TvUi.Grid(this, k, s.ranked ? 5 : 7, this::open);
        for (int i = 0; i < titles.size(); i++) {
            View cell = TvUi.poster(this, k, titles.get(i), this::open);
            if (s.ranked) rank(cell, i + 1);
            grid.add(cell);
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

    private boolean inPage(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == page) return true;
        return false;
    }

    /** The page's first thing: the banner, or the first row's first poster. */
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

    // MARK: - Banner

    private FrameLayout hero;
    private ImageView heroImage, heroLogo;
    private TextView heroTag, heroTitle, heroMeta, heroOverview, heroButton;
    private LinearLayout heroDots;
    private final List<Object[]> heroList = new ArrayList<>();   // {title, tag}
    private int heroAt;
    private final Runnable heroTick = () -> {
        if (hero == null || menuOpen || heroList.size() < 2) return;
        heroAt = (heroAt + 1) % heroList.size();
        showHero();
        restartHero();
    };

    private void restartHero() {
        main.removeCallbacks(heroTick);
        main.postDelayed(heroTick, HERO_MS);
    }

    private View buildHero() {
        heroList.clear();
        hero = new FrameLayout(this);
        hero.setBackgroundColor(TvUi.BG);
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(heroImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF00B0D12, 0x990B0D12, 0x000B0D12}));
        hero.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x000B0D12, 0x000B0D12, 0xFF0B0D12}));
        hero.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        heroTitle.setMaxWidth(Math.round(1000 * k));
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
        tl.bottomMargin = Math.round(40 * k);
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
            if (code != KeyEvent.KEYCODE_DPAD_RIGHT || heroList.size() < 2) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                heroAt = (heroAt + 1) % heroList.size();
                showHero();
                restartHero();
            }
            return true;
        });
        hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(720 * k)));
        return hero;
    }

    private void setHero(List<Object[]> list) {
        if (list.isEmpty()) {   // nothing with a backdrop: a plain title instead
            page.removeView(hero);
            hero = null;
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
        heroMeta.setText(TextUtils.join("  ·  ", meta));
        heroOverview.setText(t.overview);
        for (int i = 0; i < heroDots.getChildCount(); i++) {
            heroDots.getChildAt(i).setBackground(TvUi.round(i == heroAt ? TvUi.INK : 0x59FFFFFF, 6 * k));
        }
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
            } else if (id.equals(MOVIES)) {
                for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie")) {
                    if (out.size() >= 6) break;
                    addHero(out, seen, t, Ui.tr("Filme em alta", "Trending movie"));
                }
            } else if (id.equals(SHOWS)) {
                for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv")) {
                    if (out.size() >= 6) break;
                    addHero(out, seen, t, Ui.tr("Série em alta", "Trending show"));
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

    // MARK: - Search

    private TvUi.Grid results;
    private TextView resultsTitle;
    private String query = "";
    private final Runnable searchSoon = () -> search(generation);

    private void buildSearch(int gen) {
        status.setVisibility(View.GONE);
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
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                main.removeCallbacks(searchSoon);
                main.postDelayed(searchSoon, 600);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
            main.removeCallbacks(searchSoon);
            search(generation);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1200 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(16 * k);
        rows.addView(field, lp);
        resultsTitle = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        resultsTitle.setPadding(0, Math.round(40 * k), 0, 0);
        rows.addView(resultsTitle);
        query = "";
        search(gen);
        field.requestFocus();
    }

    /** Empty: what's trending this week; otherwise TMDB's multi search (movies, shows, people), two pages. */
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
                    for (int p = 1; p <= 2; p++) found.addAll(Tmdb.titles(Tmdb.get(path + "&page=" + p), "movie"));
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
        switch (id) {
            case LIST:
                out.add(new Section(Ui.tr("Séries", "Shows"), () -> only(Tmdb.watchlist(this), "tv")).grid().personal());
                out.add(new Section(Ui.tr("Filmes", "Movies"), () -> only(Tmdb.watchlist(this), "movie")).grid().personal());
                out.add(new Section(Ui.tr("Favoritos", "Favorites"), () -> Tmdb.accountList(this, "favorite")).grid().personal());
                out.add(new Section(Ui.tr("Suas notas", "Your ratings"), () -> Tmdb.accountList(this, "rated")).grid().personal());
                out.add(new Section(Ui.tr("Já assisti", "Seen"), () -> Tmdb.seen(this)).grid().personal());
                return out;
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
                String[][] g = {{"Comédia", "Comedy", "35", "35"}, {"Ação e aventura", "Action & adventure", "28", "10759"},
                        {"Animação", "Animation", "16", "16"}, {"Documentários", "Documentaries", "99", "99"}};
                for (String[] genre : g) {
                    out.add(new Section(Ui.tr(genre[0], genre[1]), () -> mix(
                            discover("tv", p + "&with_genres=" + genre[3] + "&sort_by=popularity.desc"),
                            discover("movie", p + "&with_genres=" + genre[2] + "&sort_by=popularity.desc"))));
                }
                return out;
            }
        }
    }

    private void genres(List<Section> out, String kind) {
        String[][] genres = {
            {"Comédia", "Comedy", "35", "35"}, {"Ação e aventura", "Action & adventure", "28", "10759"},
            {"Ficção científica e fantasia", "Sci-fi & fantasy", "878", "10765"}, {"Drama", "Drama", "18", "18"},
            {"Animação", "Animation", "16", "16"}, {"Crime", "Crime", "80", "80"},
            {"Terror", "Horror", "27", null}, {"Mistério", "Mystery", "9648", "9648"}, {"Documentários", "Documentaries", "99", "99"},
            {"Família", "Family", "10751", "10751"}, {"Romance", "Romance", "10749", null}, {"Reality", "Reality", null, "10764"},
        };
        boolean movie = kind.equals("movie");
        String services = servicesParam();
        for (String[] g : genres) {
            String gid = movie ? g[2] : g[3];
            if (gid == null) continue;
            String params = "&with_genres=" + gid + "&sort_by=popularity.desc&vote_count.gte=" + (movie ? 200 : 100) + services;
            out.add(new Section(Ui.tr(g[0], g[1]), () -> discover(kind, params)).more(discoverPath(kind, params), kind));
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

    private static List<Tmdb.Title> only(List<Tmdb.Title> list, String kind) {
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : list) if (t.kind.equals(kind)) out.add(t);
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
