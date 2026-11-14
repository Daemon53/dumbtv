package tv.dumbtv.weather;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where-to-watch search for dumbtv home: TMDB (themoviedb.org) finds the titles, and its watch
 * providers list, which comes from JustWatch, says which US services stream them.
 *
 * TMDB needs a key, which the owner gets free from their own TMDB account and enters once on the
 * TV's search screen; it is kept in this app's private storage. Each search sends TMDB only the
 * words typed and the key.
 */
final class Titles {
    private static final String API = "https://api.themoviedb.org/3";
    private static final int MAX_RESULTS = 8;

    private Titles() {}

    static boolean hasKey(SharedPreferences prefs) {
        return !prefs.getString("tmdb.key", "").isEmpty();
    }

    static void setKey(SharedPreferences prefs, String key) {
        prefs.edit().putString("tmdb.key", key == null ? "" : key.trim()).apply();
    }

    /**
     * Up to eight movies and series for {@code query}, with the services that stream each one
     * (subscription, free or with ads) and those that rent or sell it. Null without a key; a
     * Bundle with "error" when TMDB refuses or cannot be reached.
     */
    static Bundle search(SharedPreferences prefs, String query) {
        String key = prefs.getString("tmdb.key", "");
        if (key.isEmpty()) return null;
        Bundle out = new Bundle();
        ArrayList<String> titles = new ArrayList<>(), years = new ArrayList<>(), kinds = new ArrayList<>(),
                stream = new ArrayList<>(), rent = new ArrayList<>();
        try {
            JSONArray results = get(key, API + "/search/multi?include_adult=false&language=en-US&page=1&query="
                    + Uri.encode(query)).optJSONArray("results");
            for (int i = 0; results != null && i < results.length() && titles.size() < MAX_RESULTS; i++) {
                JSONObject r = results.getJSONObject(i);
                String kind = r.optString("media_type");
                if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
                String title = r.optString("movie".equals(kind) ? "title" : "name", "");
                String date = r.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
                if (title.isEmpty()) continue;
                Set<String> on = new LinkedHashSet<>(), paid = new LinkedHashSet<>();
                JSONObject us = get(key, API + "/" + kind + "/" + r.getLong("id") + "/watch/providers")
                        .optJSONObject("results");
                us = us == null ? null : us.optJSONObject("US");
                if (us != null) {
                    names(us.optJSONArray("flatrate"), on);
                    names(us.optJSONArray("free"), on);
                    names(us.optJSONArray("ads"), on);
                    names(us.optJSONArray("rent"), paid);
                    names(us.optJSONArray("buy"), paid);
                    paid.removeAll(on);
                }
                titles.add(title);
                years.add(date.length() >= 4 ? date.substring(0, 4) : "");
                kinds.add(kind);
                stream.add(join(on));
                rent.add(join(paid));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            out.putString("error", e.getMessage() != null && e.getMessage().contains("401")
                    ? "TMDB did not accept the key." : "Could not reach TMDB.");
        }
        out.putStringArray("title", titles.toArray(new String[0]));
        out.putStringArray("year", years.toArray(new String[0]));
        out.putStringArray("kind", kinds.toArray(new String[0]));
        out.putStringArray("stream", stream.toArray(new String[0]));
        out.putStringArray("rent", rent.toArray(new String[0]));
        return out;
    }

    /** A v4 read access token goes in the Authorization header; a v3 key as api_key. */
    private static JSONObject get(String key, String url) throws IOException, JSONException {
        boolean token = key.contains(".");
        String address = token ? url : url + (url.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(key);
        return Provider.get(address, "application/json", token ? "Bearer " + key : null);
    }

    private static void names(JSONArray list, Set<String> into) {
        for (int i = 0; list != null && i < list.length(); i++) {
            String name = list.optJSONObject(i) == null ? "" : list.optJSONObject(i).optString("provider_name", "");
            if (!name.isEmpty()) into.add(name.replace("|", "/"));
        }
    }

    private static String join(Set<String> names) {
        StringBuilder s = new StringBuilder();
        for (String n : names) s.append(s.length() == 0 ? "" : "|").append(n);
        return s.toString();
    }
}
