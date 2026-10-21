package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A movie's or a show's page: what it is, where it streams in the region, and what to do with it. */
public class DetailActivity extends Activity {
    private static final String KIND = "kind", ID = "id";

    static void open(Context c, String kind, int id) {
        Intent i = new Intent(c, DetailActivity.class).putExtra(KIND, kind).putExtra(ID, id);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private String kind;
    private int id;
    private Tmdb.Title title;
    private LinearLayout content;
    private TextView listButton, seenButton;
    private boolean inList;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        kind = getIntent().getStringExtra(KIND);
        id = getIntent().getIntExtra(ID, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        ImageView backdrop = new ImageView(this);
        backdrop.setId(View.generateViewId());
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF20B0D12, 0xB30B0D12, 0x330B0D12}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(Math.round(96 * k), Math.round(90 * k), 0, Math.round(80 * k));
        TextView loading = TvUi.text(this, k, 30, Ui.regular, TvUi.DIM);
        loading.setText(Ui.tr("Carregando…", "Loading…"));
        content.addView(loading);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get("/" + kind + "/" + id + "?append_to_response=watch/providers,credits,recommendations,videos");
                boolean saved = Tmdb.inWatchlist(this, kind, id);
                main.post(() -> {
                    if (isFinishing()) return;
                    inList = saved;
                    Images.load(backdrop, o.optString("backdrop_path").isEmpty() ? null : Tmdb.BACKDROP + o.optString("backdrop_path"));
                    show(o);
                });
            } catch (Exception e) {
                main.post(() -> loading.setText(Ui.tr("Não deu para abrir este título: ", "Couldn't open this title: ") + e.getMessage()));
            }
        }).start();
    }

    private void show(JSONObject o) {
        content.removeAllViews();
        boolean movie = kind.equals("movie");
        String name = o.optString(movie ? "title" : "name");
        String date = o.optString(movie ? "release_date" : "first_air_date");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        title = new Tmdb.Title(kind, id, name, o.optString("poster_path"), o.optString("backdrop_path"), year, o.optDouble("vote_average"));

        TextView h = TvUi.text(this, k, 72, Ui.displaySemibold, TvUi.INK);
        h.setText(name);
        h.setMaxWidth(Math.round(1300 * k));
        content.addView(h);

        List<String> meta = new ArrayList<>();
        meta.add(movie ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Show"));
        if (!year.isEmpty()) meta.add(year);
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length() && i < 3; i++) meta.add(genres.optJSONObject(i).optString("name"));
        int runtime = o.optInt("runtime");
        if (movie && runtime > 0) meta.add(runtime >= 60 ? (runtime / 60) + " h " + (runtime % 60) + " min" : runtime + " min");
        int seasons = o.optInt("number_of_seasons");
        if (!movie && seasons > 0) meta.add(seasons == 1 ? Ui.tr("1 temporada", "1 season") : seasons + Ui.tr(" temporadas", " seasons"));
        int episodes = o.optInt("number_of_episodes");
        if (!movie && episodes > 0) meta.add(episodes + Ui.tr(" episódios", " episodes"));
        double vote = o.optDouble("vote_average");
        if (vote > 0) meta.add(String.format(Ui.PT, "★ %.1f", vote));
        TextView m = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
        m.setText(TextUtils.join("  ·  ", meta));
        m.setPadding(0, Math.round(18 * k), 0, 0);
        content.addView(m);

        String tagline = o.optString("tagline");
        if (!tagline.isEmpty()) {
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.INK);
            t.setText(tagline);
            t.setPadding(0, Math.round(26 * k), 0, 0);
            content.addView(t);
        }
        String overview = o.optString("overview");
        if (!overview.isEmpty()) {
            TextView ov = TvUi.text(this, k, 27, Ui.regular, TvUi.DIM);
            ov.setText(overview);
            ov.setMaxWidth(Math.round(1150 * k));
            ov.setMaxLines(6);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setLineSpacing(8 * k, 1);
            ov.setPadding(0, Math.round(18 * k), 0, 0);
            content.addView(ov);
        }

        JSONObject region = o.optJSONObject("watch/providers") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results").optJSONObject(Tmdb.REGION);
        String[] watchApp = firstApp(region);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setClipChildren(false);
        actions.setPadding(0, Math.round(40 * k), 0, Math.round(8 * k));
        if (watchApp != null) {
            actions.addView(spaced(TvUi.button(this, k, "▶  " + Ui.tr("Assistir em ", "Watch on ") + watchApp[1], v -> watch(watchApp[0]))));
        }
        listButton = TvUi.button(this, k, "", v -> toggleList());
        actions.addView(spaced(listButton));
        seenButton = TvUi.button(this, k, "", v -> toggleSeen());
        actions.addView(spaced(seenButton));
        if (Tmdb.signedIn(this)) actions.addView(spaced(TvUi.button(this, k, "★  " + Ui.tr("Avaliar", "Rate"), v -> rate())));
        String trailer = trailer(o);
        if (trailer != null) actions.addView(spaced(TvUi.button(this, k, Ui.tr("Trailer", "Trailer"), v -> playTrailer(trailer))));
        content.addView(actions);
        labels();

        content.addView(header(Ui.tr("Onde assistir", "Where to watch")));
        View where = whereToWatch(region);
        content.addView(where);

        JSONArray cast = o.optJSONObject("credits") == null ? null : o.optJSONObject("credits").optJSONArray("cast");
        if (cast != null && cast.length() > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < cast.length() && i < 8; i++) names.add(cast.optJSONObject(i).optString("name"));
            TextView c = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
            c.setText(Ui.tr("Elenco: ", "Cast: ") + TextUtils.join(", ", names));
            c.setMaxWidth(Math.round(1500 * k));
            c.setPadding(0, Math.round(34 * k), 0, 0);
            content.addView(c);
        }

        JSONObject recs = o.optJSONObject("recommendations");
        List<Tmdb.Title> similar = recs == null ? new ArrayList<>() : Tmdb.titles(recs, kind);
        if (!similar.isEmpty()) {
            content.addView(TvUi.row(this, k, Ui.tr("Você também pode gostar", "You may also like"), similar,
                    t -> DetailActivity.open(this, t.kind, t.id)));
        }
        View first = actions.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private View spaced(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(22 * k);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView header(String text) {
        TextView t = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        t.setText(text);
        t.setPadding(0, Math.round(44 * k), 0, Math.round(14 * k));
        return t;
    }

    private void labels() {
        listButton.setText(inList ? "✓  " + Ui.tr("Na minha lista", "In my list") : "+  " + Ui.tr("Minha lista", "My list"));
        boolean seen = Tmdb.isSeen(this, kind, id);
        seenButton.setText(seen ? "✓  " + Ui.tr("Assistido", "Seen") : Ui.tr("Já assisti", "I've seen it"));
    }

    /** The first subscription or free service with an app on the TV: {package, service name}. */
    private String[] firstApp(JSONObject region) {
        if (region == null) return null;
        for (String type : new String[] {"flatrate", "free", "ads"}) {
            JSONArray list = region.optJSONArray(type);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String app = Providers.app(this, p.optInt("provider_id"), p.optString("provider_name"));
                if (app != null) return new String[] {app, p.optString("provider_name")};
            }
        }
        return null;
    }

    private View whereToWatch(JSONObject region) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFocusable(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.setPadding(Math.round(8 * k), Math.round(10 * k), Math.round(60 * k), Math.round(10 * k));
        String[][] types = {{"flatrate", Ui.tr("Assinatura", "Subscription")}, {"free", Ui.tr("Grátis", "Free")},
                {"ads", Ui.tr("Grátis com anúncios", "Free with ads")}, {"rent", Ui.tr("Aluguel", "Rent")}, {"buy", Ui.tr("Compra", "Buy")}};
        Set<String> added = new HashSet<>();
        for (String[] type : types) {
            JSONArray list = region == null ? null : region.optJSONArray(type[0]);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                int pid = p.optInt("provider_id");
                if (!added.add(pid + type[0])) continue;
                line.addView(chip(pid, p.optString("provider_name"), p.optString("logo_path"), type[1]));
            }
        }
        if (line.getChildCount() == 0) {
            TextView none = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
            none.setText(Ui.tr("Não está em nenhum serviço de streaming no Brasil agora.", "Not on any streaming service in the region right now."));
            return none;
        }
        scroll.addView(line);
        return scroll;
    }

    private View chip(int pid, String name, String logo, String type) {
        String app = Providers.app(this, pid, name);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(TvUi.round(0x26FFFFFF, 18 * k));
        chip.setPadding(Math.round(14 * k), Math.round(14 * k), Math.round(28 * k), Math.round(14 * k));
        ImageView img = new ImageView(this);
        img.setClipToOutline(true);
        img.setBackground(TvUi.round(0x00000000, 12 * k));
        Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
        int size = Math.round(64 * k);
        chip.addView(img, new LinearLayout.LayoutParams(size, size));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(18 * k), 0, 0, 0);
        TextView n = TvUi.text(this, k, 25, Ui.medium, TvUi.INK);
        n.setText(name);
        TextView s = TvUi.text(this, k, 21, Ui.regular, TvUi.DIM);
        s.setText(type + "  ·  " + (app != null ? Ui.tr("abre ", "opens ") + Providers.label(this, app) : Ui.tr("sem app na TV", "no app on the TV")));
        s.setPadding(0, Math.round(6 * k), 0, 0);
        texts.addView(n);
        texts.addView(s);
        chip.addView(texts);
        TvUi.focusable(chip, k, 18 * k);
        chip.setOnClickListener(v -> {
            if (app != null) watch(app);
            else Toast.makeText(this, Ui.tr("Nenhum app para " + name + ". Escolha um em Ajustes › Serviços.",
                    "No app for " + name + ". Pick one in Settings › Services."), Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(20 * k);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void watch(String pkg) {
        if (title != null) Tmdb.setSeen(this, title, true);
        RemoteKeys.run(this, pkg);
    }

    private void toggleList() {
        if (title == null) return;
        boolean want = !inList;
        new Thread(() -> {
            try {
                Tmdb.setWatchlist(this, title, want);
                main.post(() -> { inList = want; labels(); });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, Ui.tr("Não deu para salvar: ", "Couldn't save: ") + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void toggleSeen() {
        if (title == null) return;
        Tmdb.setSeen(this, title, !Tmdb.isSeen(this, kind, id));
        labels();
    }

    private void rate() {
        String[] items = new String[10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Sua nota para ", "Your rating for ") + title.name)
                .setItems(items, (d, which) -> new Thread(() -> {
                    try {
                        Tmdb.rate(this, title, 10 - which);
                        Tmdb.setSeen(this, title, true);
                        main.post(() -> {
                            labels();
                            Toast.makeText(this, Ui.tr("Nota salva na sua conta TMDB.", "Rating saved to your TMDB account."), Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start())
                .show();
    }

    private static String trailer(JSONObject o) {
        JSONArray videos = o.optJSONObject("videos") == null ? null : o.optJSONObject("videos").optJSONArray("results");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if ("YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) return v.optString("key");
        }
        return null;
    }

    private void playTrailer(String key) {
        Intent youtube = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .setPackage("com.google.android.youtube.tv");
        try {
            startActivity(youtube);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key)));
            } catch (Exception ignored) {
                Toast.makeText(this, Ui.tr("O YouTube não está instalado.", "YouTube isn't installed."), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
