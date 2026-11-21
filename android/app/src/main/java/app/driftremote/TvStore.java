package app.driftremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** TVs the user has used, and app shortcuts, kept in SharedPreferences. */
public class TvStore {
    public static final class Tv {
        public String name;
        public String host;
        public boolean paired;
        public long lastUsed;

        Tv(String name, String host) {
            this.name = name;
            this.host = host;
        }
    }

    public static final class Shortcut {
        public final String label;
        public final String link;

        public Shortcut(String label, String link) {
            this.label = label;
            this.link = link;
        }
    }

    private final SharedPreferences prefs;

    TvStore(Context ctx) {
        prefs = ctx.getSharedPreferences("driftremote", Context.MODE_PRIVATE);
    }

    public synchronized List<Tv> tvs() {
        List<Tv> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("tvs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tv t = new Tv(o.optString("name"), o.optString("host"));
                t.paired = o.optBoolean("paired");
                t.lastUsed = o.optLong("lastUsed");
                list.add(t);
            }
        } catch (JSONException ignored) {
        }
        Collections.sort(list, (x, y) -> Long.compare(y.lastUsed, x.lastUsed));
        return list;
    }

    public synchronized Tv find(String host) {
        for (Tv t : tvs()) if (t.host.equals(host)) return t;
        return null;
    }

    /** Adds or updates a TV. A null name keeps the saved one. */
    public synchronized void save(String host, String name, Boolean paired, boolean touch) {
        List<Tv> list = tvs();
        Tv tv = null;
        for (Tv t : list) if (t.host.equals(host)) tv = t;
        if (tv == null) {
            tv = new Tv(name == null ? host : name, host);
            list.add(tv);
        }
        if (name != null && !name.isEmpty()) tv.name = name;
        if (paired != null) tv.paired = paired;
        if (touch) tv.lastUsed = System.currentTimeMillis();
        write(list);
    }

    public synchronized void forget(String host) {
        List<Tv> list = tvs();
        for (Iterator<Tv> it = list.iterator(); it.hasNext(); ) if (it.next().host.equals(host)) it.remove();
        write(list);
    }

    synchronized void forgetAllPairings() {
        List<Tv> list = tvs();
        for (Tv t : list) t.paired = false;
        write(list);
    }

    private void write(List<Tv> list) {
        JSONArray a = new JSONArray();
        try {
            for (Tv t : list) {
                a.put(new JSONObject().put("name", t.name).put("host", t.host)
                        .put("paired", t.paired).put("lastUsed", t.lastUsed));
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("tvs", a.toString()).apply();
    }

    // ---- app shortcuts ----

    private static final String[][] DEFAULT_SHORTCUTS = {
            {"YouTube", "https://www.youtube.com"},
            {"Netflix", "https://www.netflix.com/title"},
            {"Prime Video", "https://app.primevideo.com"},
            {"Disney+", "https://www.disneyplus.com"},
            {"Spotify", "market://launch?id=com.spotify.tv.android"},
            {"Play Store", "market://launch?id=com.android.vending"},
    };

    public synchronized List<Shortcut> shortcuts() {
        List<Shortcut> list = new ArrayList<>();
        String saved = prefs.getString("shortcuts", null);
        if (saved == null) {
            for (String[] s : DEFAULT_SHORTCUTS) list.add(new Shortcut(s[0], s[1]));
            return list;
        }
        try {
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Shortcut(o.optString("label"), o.optString("link")));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public synchronized void setShortcuts(List<Shortcut> list) {
        JSONArray a = new JSONArray();
        try {
            for (Shortcut s : list) a.put(new JSONObject().put("label", s.label).put("link", s.link));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("shortcuts", a.toString()).apply();
    }

    /** Turns what the user typed (a package name or a link) into an app link. */
    public static String toLink(String input) {
        String s = input.trim();
        if (s.contains("://")) return s;
        return "market://launch?id=" + s;
    }
}
