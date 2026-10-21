package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * config.json pushed from the computer (launcher-ui/config.sh) into the app's external files folder. Each new file is
 * applied once, as if the person had made those choices on the TV; what they change on the TV afterwards still wins
 * until the next push. remote.json (the remote's buttons, launcher-ui/remote.sh) sits in the same folder.
 */
final class TvConfig {
    private TvConfig() {}

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    static String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Applies config.json if it changed since the last time. True when something was applied. */
    static boolean apply(Context c) {
        String text = read(new File(dir(c), "config.json"));
        SharedPreferences sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String stamp = text == null ? "" : String.valueOf(text.hashCode()) + ":" + text.length();
        if (stamp.equals(sp.getString("configStamp", ""))) return false;
        sp.edit().putString("configStamp", stamp).apply();
        if (text == null) return false;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray shelf = o.optJSONArray("shelf");
            if (shelf != null) Prefs.setShelf(strings(shelf));
            JSONArray hidden = o.optJSONArray("hidden_apps");
            if (hidden != null) Prefs.setHidden(strings(hidden));
            JSONObject city = o.optJSONObject("city");
            if (city != null && city.has("latitude") && city.has("longitude")) {
                Prefs.setCity(city.optString("name"), String.valueOf(city.getDouble("latitude")),
                        String.valueOf(city.getDouble("longitude")));
            }
            if (o.has("theme")) Prefs.setTheme(o.getString("theme"));
            if (o.has("temperature")) Prefs.setTemperature(o.getString("temperature"));
            if (o.has("weather")) Prefs.setWeather(o.getBoolean("weather"));
            JSONObject buttons = o.optJSONObject("buttons");
            if (buttons != null) {
                for (java.util.Iterator<String> it = buttons.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Prefs.setButton(key, buttons.optString(key));
                }
            }
            JSONObject providers = o.optJSONObject("providers");
            if (providers != null) {
                for (java.util.Iterator<String> it = providers.keys(); it.hasNext(); ) {
                    String id = it.next();
                    Prefs.setProvider(id, providers.optString(id));
                }
            }
            if (o.has("show_all_providers")) Prefs.setShowAllProviders(o.getBoolean("show_all_providers"));
            AppLog.w("config.json applied");
            return true;
        } catch (Exception e) {
            AppLog.w("config.json not applied: " + e.getMessage());
            return false;
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
