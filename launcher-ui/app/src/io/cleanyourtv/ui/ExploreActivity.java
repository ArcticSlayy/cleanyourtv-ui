package io.cleanyourtv.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
 * Explore: rows of movies and shows to browse (trending, by service, new on streaming, by genre), the person's list,
 * and picks from what they saw and saved. OK on a poster opens its page, with where to watch it.
 * With EXTRA_SECTION = "watchlist" (the remote's bookmark button) it shows the list alone.
 */
public class ExploreActivity extends Activity {
    static final String EXTRA_SECTION = "section", EXTRA_TITLE = "title", EXTRA_PATH = "path", EXTRA_KIND = "kind";

    /** A list of titles from one TMDB path (a search, a keyword), three pages, one row per page. */
    static void openList(android.content.Context c, String title, String path, String kind) {
        c.startActivity(new android.content.Intent(c, ExploreActivity.class)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PATH, path).putExtra(EXTRA_KIND, kind));
    }

    private String listPath, listKind;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private LinearLayout rows;
    private TextView status;
    private float k;
    private boolean watchlistOnly;
    private final Map<Integer, View> placed = new HashMap<>();
    private int generation;

    private static final class Section {
        final String title;
        final Callable<List<Tmdb.Title>> load;
        Section(String title, Callable<List<Tmdb.Title>> load) {
            this.title = title;
            this.load = load;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        watchlistOnly = "watchlist".equals(getIntent().getStringExtra(EXTRA_SECTION));
        listPath = getIntent().getStringExtra(EXTRA_PATH);
        listKind = getIntent().getStringExtra(EXTRA_KIND);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(Math.round(96 * k), Math.round(64 * k), 0, Math.round(80 * k));
        rows.setClipToPadding(false);
        TextView title = TvUi.text(this, k, 64, Ui.displaySemibold, TvUi.INK);
        title.setText(listPath != null ? getIntent().getStringExtra(EXTRA_TITLE)
                : watchlistOnly ? Ui.tr("Minha lista", "My list") : Ui.tr("Explorar", "Explore"));
        if (listPath == null && !watchlistOnly) {
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView search = TvUi.button(this, k, "⌕  " + Ui.tr("Buscar", "Search"), v -> askSearch());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(96 * k);
            top.addView(search, lp);
            rows.addView(top);
        } else {
            rows.addView(title);
        }
        TextView sub = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        sub.setText(listPath != null ? Ui.tr("Dados do TMDB", "Data from TMDB") : watchlistOnly
                ? (Tmdb.signedIn(this) ? Ui.tr("Da sua conta TMDB", "From your TMDB account") + " · " + Tmdb.accountName(this)
                        : Ui.tr("Salva nesta TV. Conecte sua conta TMDB em Ajustes para sincronizar.", "Kept on this TV. Connect your TMDB account in Settings to sync."))
                : Ui.tr("O que está em alta, nos seus serviços e do seu jeito · dados do TMDB",
                        "What's trending, on your services and for you · data from TMDB"));
        sub.setPadding(0, Math.round(12 * k), 0, Math.round(8 * k));
        rows.addView(sub);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPadding(0, Math.round(40 * k), 0, 0);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        scroll.addView(rows);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            View row = now;
            while (row != null && row.getParent() != rows) row = row.getParent() instanceof View ? (View) row.getParent() : null;
            if (row != null) scroll.smoothScrollTo(0, Math.max(0, row.getTop() - Math.round(40 * k)));
        });
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private boolean loaded;

    @Override protected void onResume() {
        super.onResume();
        load(loaded);   // back from a title page: only the list and the picks may have changed
        loaded = true;
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void load(boolean personalOnly) {
        if (!Tmdb.hasKey()) {
            status.setText(Ui.tr("Sem chave do TMDB: coloque a sua em launcher-ui/config.properties e instale de novo.",
                    "No TMDB key: add yours to launcher-ui/config.properties and install again."));
            return;
        }
        int gen = ++generation;
        io.execute(() -> {
            List<Section> all = sections();   // asks TMDB for the services: not on the main thread
            main.post(() -> { if (gen == generation && !isFinishing()) start(gen, all, personalOnly); });
        });
    }

    private void start(int gen, List<Section> sections, boolean personalOnly) {
        int[] left = {sections.size()};
        int[] shown = {0};
        if (personalOnly && listPath != null) return;
        if (personalOnly && !watchlistOnly) {
            sections = sections.subList(0, 5);
            left[0] = sections.size();
        }
        for (int i = 0; i < sections.size(); i++) {
            final int slot = i;
            final Section s = sections.get(i);
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
                    place(slot, s.title, got);
                    if (!got.isEmpty()) shown[0]++;
                    if (personalOnly) return;
                    if (--left[0] == 0) {
                        status.setVisibility(shown[0] == 0 ? View.VISIBLE : View.GONE);
                        if (shown[0] == 0) status.setText(watchlistOnly
                                ? Ui.tr("Sua lista está vazia. Abra um título e escolha \"Minha lista\".", "Your list is empty. Open a title and pick \"My list\".")
                                : Ui.tr("Não deu para buscar os títulos agora. Confira a internet da TV.", "Couldn't fetch titles now. Check the TV's internet."));
                    } else if (shown[0] > 0) {
                        status.setVisibility(View.GONE);
                    }
                });
            });
        }
    }

    /** Puts a row in its slot (rows arrive in any order), keeping the focused poster where it is. */
    private final Map<Integer, String> placedKeys = new HashMap<>();

    private void place(int slot, String title, List<Tmdb.Title> titles) {
        StringBuilder keys = new StringBuilder(title);
        for (Tmdb.Title t : titles) keys.append(',').append(t.key());
        if (placed.containsKey(slot) && keys.toString().equals(placedKeys.get(slot))) return;   // unchanged: keep the focus where it is
        placedKeys.put(slot, keys.toString());
        View old = placed.remove(slot);
        if (old != null) rows.removeView(old);
        if (titles.isEmpty()) return;
        View row = TvUi.row(this, k, title, titles, t -> DetailActivity.open(this, t.kind, t.id));
        placed.put(slot, row);
        int index = rows.indexOfChild(status) + 1;
        for (Map.Entry<Integer, View> e : placed.entrySet()) {
            if (e.getKey() < slot && e.getValue().getParent() == rows) index = Math.max(index, rows.indexOfChild(e.getValue()) + 1);
        }
        rows.addView(row, Math.min(index, rows.getChildCount()));
        View focus = getCurrentFocus();
        if (focus == null || focus == rows.getParent() || !focus.isAttachedToWindow()) row.requestFocus();
    }

    private void askSearch() {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setHint(Ui.tr("Filme, série ou pessoa", "Movie, show or person"));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Buscar", "Search"))
                .setView(field)
                .setPositiveButton(Ui.tr("Buscar", "Search"), (d, w) -> search(field.getText().toString()))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.dismiss();
            search(field.getText().toString());
            return true;
        });
        dialog.show();
    }

    private void search(String query) {
        String q = query.trim();
        if (q.isEmpty()) return;
        try {
            openList(this, Ui.tr("Busca: ", "Search: ") + q, "/search/multi?include_adult=false&query=" + java.net.URLEncoder.encode(q, "UTF-8"), "movie");
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
    }

    private List<Section> sections() {
        List<Section> out = new ArrayList<>();
        if (listPath != null) {
            for (int page = 1; page <= 3; page++) {
                final int p = page;
                out.add(new Section(page == 1 ? Ui.tr("Resultados", "Results") : "", () -> Tmdb.titles(
                        Tmdb.get(listPath + (listPath.contains("?") ? "&" : "?") + "page=" + p), listKind == null ? "movie" : listKind)));
            }
            return out;
        }
        if (watchlistOnly) {
            out.add(new Section(Ui.tr("Séries", "Shows"), () -> only(Tmdb.watchlist(this), "tv")));
            out.add(new Section(Ui.tr("Filmes", "Movies"), () -> only(Tmdb.watchlist(this), "movie")));
            out.add(new Section(Ui.tr("Já assisti", "Seen"), () -> Tmdb.seen(this)));
            return out;
        }
        out.add(new Section(Ui.tr("Minha lista", "My list"), () -> Tmdb.watchlist(this)));
        out.add(new Section(Ui.tr("Recomendado para você", "Recommended for you"), this::forYou));
        List<Tmdb.Title> seen = Tmdb.seen(this);
        Tmdb.Title last = seen.isEmpty() ? null : seen.get(0);
        out.add(new Section(last == null ? "" : Ui.tr("Porque você viu ", "Because you watched ") + last.name, () -> last == null
                ? new ArrayList<>() : fresh(Tmdb.titles(Tmdb.get("/" + last.kind + "/" + last.id + "/recommendations"), last.kind))));
        out.add(new Section(Ui.tr("Favoritos", "Favorites"), () -> Tmdb.accountList(this, "favorite")));
        out.add(new Section(Ui.tr("Suas notas", "Your ratings"), () -> Tmdb.accountList(this, "rated")));
        out.add(new Section(Ui.tr("Em alta hoje", "Trending today"), () -> Tmdb.titles(Tmdb.get("/trending/all/day"), "movie")));
        out.add(new Section(Ui.tr("Séries em alta na semana", "Trending shows this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/trending/tv/week"), "tv"))));
        out.add(new Section(Ui.tr("Filmes em alta na semana", "Trending movies this week"), () -> mine("movie", Tmdb.titles(Tmdb.get("/trending/movie/week"), "movie"))));
        try {
            int n = 0;
            for (Providers.Provider p : Providers.usable(this)) {
                if (Providers.app(this, p.id, p.name) == null || n++ >= 6) continue;
                out.add(new Section(Ui.tr("Em alta na ", "Trending on ") + p.name, () -> mix(
                        discover("tv", "&with_watch_providers=" + p.id + "&sort_by=popularity.desc"),
                        discover("movie", "&with_watch_providers=" + p.id + "&sort_by=popularity.desc"))));
            }
        } catch (Exception e) {
            AppLog.w("explore services: " + e.getMessage());
        }
        out.add(new Section(Ui.tr("Episódios novos hoje", "New episodes today"), () -> mine("tv", Tmdb.titles(Tmdb.get("/tv/airing_today"), "tv"))));
        out.add(new Section(Ui.tr("Nos cinemas", "In theaters"), () -> Tmdb.titles(Tmdb.get("/movie/now_playing?region=" + Tmdb.REGION), "movie")));
        out.add(new Section(Ui.tr("Em breve nos cinemas", "Coming to theaters"), () -> Tmdb.titles(Tmdb.get("/movie/upcoming?region=" + Tmdb.REGION), "movie")));
        out.add(new Section(Ui.tr("Mais bem avaliados", "Top rated"), () -> mix(
                Tmdb.titles(Tmdb.get("/tv/top_rated"), "tv"), Tmdb.titles(Tmdb.get("/movie/top_rated"), "movie"))));
        out.add(new Section(Ui.tr("Pessoas em alta", "Trending people"), () -> Tmdb.titles(Tmdb.get("/trending/person/week"), "person")));
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        out.add(new Section(Ui.tr("Estreias recentes no streaming", "New on streaming"), () -> discover("movie",
                "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=40" + services())));
        String[][] genres = {
            {"Comédia", "Comedy", "35", "35"}, {"Ação e aventura", "Action & adventure", "28", "10759"},
            {"Ficção científica e fantasia", "Sci-fi & fantasy", "878", "10765"}, {"Drama", "Drama", "18", "18"},
            {"Animação", "Animation", "16", "16"}, {"Crime e suspense", "Crime & thriller", "80", "80"},
            {"Terror", "Horror", "27", null}, {"Documentários", "Documentaries", "99", "99"},
        };
        for (String[] g : genres) {
            out.add(new Section(Ui.tr(g[0], g[1]), () -> mix(
                    g[3] == null ? new ArrayList<>() : discover("tv", "&with_genres=" + g[3] + "&sort_by=popularity.desc&vote_count.gte=100" + services()),
                    discover("movie", "&with_genres=" + g[2] + "&sort_by=popularity.desc&vote_count.gte=200" + services()))));
        }
        return out;
    }

    /** Only the services with an app (or all, with "show all"), as discover parameters. */
    private String services() throws Exception {
        if (Prefs.showAllProviders()) return "&watch_region=" + Tmdb.REGION;
        StringBuilder ids = new StringBuilder();
        for (Providers.Provider p : Providers.usable(this)) ids.append(ids.length() == 0 ? "" : "|").append(p.id);
        return ids.length() == 0 ? "" : "&watch_region=" + Tmdb.REGION + "&with_watch_providers=" + ids
                + "&with_watch_monetization_types=flatrate";
    }

    private List<Tmdb.Title> discover(String kind, String params) throws Exception {
        String region = params.contains("watch_region") ? "" : "&watch_region=" + Tmdb.REGION;
        return Tmdb.titles(Tmdb.get("/discover/" + kind + "?include_adult=false" + region + params), kind);
    }

    /** Trending, kept to the person's services unless "show all" is on. */
    private List<Tmdb.Title> mine(String kind, List<Tmdb.Title> trending) throws Exception {
        if (Prefs.showAllProviders()) return trending;
        Set<Integer> ok = new HashSet<>();
        String services = services();
        if (services.isEmpty()) return trending;
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
        return out.size() > 24 ? out.subList(0, 24) : out;
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
