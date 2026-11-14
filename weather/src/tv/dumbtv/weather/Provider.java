package tv.dumbtv.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Today's forecast and any active weather alerts, for dumbtv home, which has no internet
 * permission of its own.
 *
 * dumbtv home calls {@code call("weather", null, {lat, lon, units})}. The answer is what was last
 * saved for that place, or null before the first forecast arrives. Anything older than its limit
 * (forecast 30 min, alerts 5 min) is fetched again in the background, so the next call gets it.
 * Two hosts are contacted, both free and without an account: api.open-meteo.com for the forecast
 * and api.weather.gov (the US National Weather Service) for alerts. Each is sent only the place.
 * The where-to-watch search ({@link Titles}) adds api.themoviedb.org, with the owner's own key.
 */
public final class Provider extends ContentProvider {
    private static final String CALLER = "tv.dumbtv.home";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private static final String ALERTS = "https://api.weather.gov/alerts/active";
    // api.weather.gov refuses requests without a User-Agent that names the application.
    private static final String AGENT = "dumbtv-weather/0.2 (github.com/Daemon53/dumbtv)";
    private static final long FORECAST_MS = 30 * 60 * 1000L;
    private static final long ALERTS_MS = 5 * 60 * 1000L;
    private static final long RETRY_MS = 2 * 60 * 1000L;
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_ALERTS = 4;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile long lastTry;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!CALLER.equals(getCallingPackage())) return null;
        // Where-to-watch search (Titles). Binder threads may block on the network.
        if ("hasKey".equals(method)) {
            Bundle b = new Bundle();
            b.putBoolean("hasKey", Titles.hasKey(prefs()));
            return b;
        }
        if ("setKey".equals(method)) {
            Titles.setKey(prefs(), arg);
            return null;
        }
        if ("search".equals(method)) return arg == null || arg.trim().isEmpty() ? null : Titles.search(prefs(), arg.trim());
        if (!"weather".equals(method) || extras == null) return null;
        double lat = extras.getDouble("lat", Double.NaN), lon = extras.getDouble("lon", Double.NaN);
        if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) return null;
        String units = "celsius".equals(extras.getString("units")) ? "celsius" : "fahrenheit";
        String place = String.format(Locale.ROOT, "%.4f,%.4f,%s", lat, lon, units);

        SharedPreferences saved = prefs();
        boolean same = place.equals(saved.getString("place", null));
        long now = System.currentTimeMillis();
        // A forecast saved before the 10-day page existed has no days yet: fetch it again.
        boolean forecast = !same || now - saved.getLong("at", 0) > FORECAST_MS
                || saved.getString("days.time", "").isEmpty();
        boolean alerts = !same || now - saved.getLong("alertsAt", 0) > ALERTS_MS;
        // A new place is fetched at once; failures for the same place wait RETRY_MS before trying again.
        if ((forecast || alerts) && !busy && (!same || now - lastTry > RETRY_MS)) {
            busy = true;
            lastTry = now;
            net.execute(() -> {
                try {
                    if (forecast) fetchForecast(lat, lon, units, place);
                    if (alerts) fetchAlerts(lat, lon);
                } catch (IOException | JSONException | RuntimeException e) {
                    Log.w("dumbtvweather", "update failed: " + e);
                } finally {
                    busy = false;
                }
            });
        }
        if (!same) return null;

        Bundle out = new Bundle();
        out.putLong("at", saved.getLong("at", 0));
        out.putInt("code", saved.getInt("code", -1));
        for (String k : new String[] {"now", "hi", "lo", "noon", "evening"}) {
            out.putDouble(k, Double.longBitsToDouble(saved.getLong(k, Double.doubleToLongBits(Double.NaN))));
        }
        // Alerts that have run out since they were saved are left out.
        ArrayList<String> events = new ArrayList<>(), severities = new ArrayList<>(), headlines = new ArrayList<>();
        ArrayList<Long> ends = new ArrayList<>();
        int n = saved.getInt("alerts", 0);
        for (int i = 0; i < n; i++) {
            long end = saved.getLong("alert" + i + ".ends", 0);
            if (end != 0 && end < now) continue;
            events.add(saved.getString("alert" + i + ".event", ""));
            severities.add(saved.getString("alert" + i + ".severity", ""));
            headlines.add(saved.getString("alert" + i + ".headline", ""));
            ends.add(end);
        }
        out.putStringArray("alertEvent", events.toArray(new String[0]));
        out.putStringArray("alertSeverity", severities.toArray(new String[0]));
        out.putStringArray("alertHeadline", headlines.toArray(new String[0]));
        long[] endArray = new long[ends.size()];
        for (int i = 0; i < endArray.length; i++) endArray[i] = ends.get(i);
        out.putLongArray("alertEnds", endArray);

        String[] days = split(saved.getString("days.time", ""));
        String[] hi = split(saved.getString("days.hi", "")), lo = split(saved.getString("days.lo", ""));
        String[] code = split(saved.getString("days.code", "")), rain = split(saved.getString("days.rain", ""));
        int d = Math.min(days.length, Math.min(Math.min(hi.length, lo.length), Math.min(code.length, rain.length)));
        double[] dayHi = new double[d], dayLo = new double[d];
        int[] dayCode = new int[d], dayRain = new int[d];
        String[] dayTime = new String[d];
        for (int i = 0; i < d; i++) {
            dayTime[i] = days[i];
            dayHi[i] = number(hi[i]);
            dayLo[i] = number(lo[i]);
            dayCode[i] = (int) number(code[i]);
            dayRain[i] = (int) number(rain[i]);
        }
        out.putStringArray("dayTime", dayTime);
        out.putDoubleArray("dayHi", dayHi);
        out.putDoubleArray("dayLo", dayLo);
        out.putIntArray("dayCode", dayCode);
        out.putIntArray("dayRain", dayRain);
        return out;
    }

    private static String[] split(String s) {
        return s.isEmpty() ? new String[0] : s.split(",");
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void fetchForecast(double lat, double lon, String units, String place) throws IOException, JSONException {
        JSONObject json = get(String.format(Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code"
                        + "&hourly=temperature_2m&daily=temperature_2m_max,temperature_2m_min,weather_code"
                        + ",precipitation_probability_max&temperature_unit=%s&timezone=auto&forecast_days=10",
                FORECAST, lat, lon, units), "application/json");
        JSONObject current = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        JSONObject hourly = json.getJSONObject("hourly");
        String day = daily.getJSONArray("time").getString(0);
        // The 10 days for the forecast page, one comma-separated list per field.
        JSONArray times = daily.getJSONArray("time"), highs = daily.getJSONArray("temperature_2m_max"),
                lows = daily.getJSONArray("temperature_2m_min");
        JSONArray codes = daily.optJSONArray("weather_code"), rain = daily.optJSONArray("precipitation_probability_max");
        StringBuilder dt = new StringBuilder(), dh = new StringBuilder(), dl = new StringBuilder(),
                dc = new StringBuilder(), dr = new StringBuilder();
        for (int i = 0; i < times.length(); i++) {
            String sep = i == 0 ? "" : ",";
            dt.append(sep).append(times.optString(i));
            dh.append(sep).append(highs.optDouble(i));
            dl.append(sep).append(lows.optDouble(i));
            dc.append(sep).append(codes == null ? -1 : codes.optInt(i, -1));
            dr.append(sep).append(rain == null ? -1 : rain.optInt(i, -1));
        }
        prefs().edit()
                .putString("days.time", dt.toString())
                .putString("days.hi", dh.toString())
                .putString("days.lo", dl.toString())
                .putString("days.code", dc.toString())
                .putString("days.rain", dr.toString())
                .apply();
        prefs().edit()
                .putString("place", place)
                .putLong("at", System.currentTimeMillis())
                .putInt("code", current.optInt("weather_code", -1))
                .putLong("now", bits(current.optDouble("temperature_2m")))
                .putLong("hi", bits(daily.getJSONArray("temperature_2m_max").optDouble(0)))
                .putLong("lo", bits(daily.getJSONArray("temperature_2m_min").optDouble(0)))
                .putLong("noon", bits(hour(hourly, day + "T12:00")))
                .putLong("evening", bits(hour(hourly, day + "T18:00")))
                .apply();
    }

    /** Active NWS alerts for the point, most severe first. US locations only; elsewhere none. */
    private void fetchAlerts(double lat, double lon) throws IOException, JSONException {
        JSONArray features = get(String.format(Locale.ROOT, "%s?point=%.4f,%.4f", ALERTS, lat, lon),
                "application/geo+json").getJSONArray("features");
        String[] order = {"Extreme", "Severe", "Moderate", "Minor", "Unknown"};
        SharedPreferences.Editor e = prefs().edit();
        int n = 0;
        for (String level : order) {
            for (int i = 0; i < features.length() && n < MAX_ALERTS; i++) {
                JSONObject p = features.getJSONObject(i).optJSONObject("properties");
                if (p == null || !level.equals(p.optString("severity", "Unknown"))) continue;
                if ("Cancel".equals(p.optString("messageType"))) continue;
                String end = p.optString("ends", "");
                if (end.isEmpty() || "null".equals(end)) end = p.optString("expires", "");
                e.putString("alert" + n + ".event", p.optString("event", "Weather alert"));
                e.putString("alert" + n + ".severity", level);
                e.putString("alert" + n + ".headline", p.optString("headline", ""));
                e.putLong("alert" + n + ".ends", time(end));
                n++;
            }
        }
        e.putInt("alerts", n).putLong("alertsAt", System.currentTimeMillis()).apply();
    }

    private static JSONObject get(String address, String accept) throws IOException, JSONException {
        return get(address, accept, null);
    }

    static JSONObject get(String address, String accept, String authorization) throws IOException, JSONException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", AGENT);
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + c.getURL().getHost());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /** ISO 8601 with an offset, as the NWS writes it ("2026-11-07T18:00:00-06:00"); 0 if unreadable. */
    private static long time(String iso) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(iso).getTime();
        } catch (ParseException | RuntimeException e) {
            return 0;
        }
    }

    /** The hourly temperature at {@code time} (local, "yyyy-MM-ddTHH:mm"), or NaN. */
    private static double hour(JSONObject hourly, String time) throws JSONException {
        JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m");
        for (int i = 0; i < times.length() && i < temps.length(); i++) {
            if (time.equals(times.optString(i))) return temps.optDouble(i);
        }
        return Double.NaN;
    }

    private static long bits(double v) {
        return Double.doubleToLongBits(v);
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("answer too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("forecast", android.content.Context.MODE_PRIVATE);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
