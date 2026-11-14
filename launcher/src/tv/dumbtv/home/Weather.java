package tv.dumbtv.home;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * Today's forecast, read from the tv.dumbtv.weather helper app (weather/). This app has no
 * internet permission; the helper is the only part that goes online, and it answers only us.
 *
 * Settings in home.conf: weather.lat and weather.lon (decimal degrees), weather.units
 * (fahrenheit or celsius) and Options › Weather. Without a place the weather stays hidden.
 */
final class Weather {
    private static final Uri HELPER = Uri.parse("content://tv.dumbtv.weather");

    final double now, hi, lo, noon, evening;
    final int code;
    /** Active National Weather Service alerts, most severe first; empty when there are none. */
    final String[] alertEvent, alertSeverity, alertHeadline;
    final long[] alertEnds;
    /** The coming days, today first: "yyyy-MM-dd", high, low, WMO code, chance of rain in % (-1 unknown). */
    final String[] dayTime;
    final double[] dayHi, dayLo;
    final int[] dayCode, dayRain;

    private Weather(Bundle b) {
        String[] t = b.getStringArray("dayTime");
        double[] h = b.getDoubleArray("dayHi"), l = b.getDoubleArray("dayLo");
        int[] c = b.getIntArray("dayCode"), r = b.getIntArray("dayRain");
        boolean days = t != null && h != null && l != null && c != null && r != null
                && h.length == t.length && l.length == t.length && c.length == t.length && r.length == t.length;
        dayTime = days ? t : new String[0];
        dayHi = days ? h : new double[0];
        dayLo = days ? l : new double[0];
        dayCode = days ? c : new int[0];
        dayRain = days ? r : new int[0];
        now = b.getDouble("now", Double.NaN);
        hi = b.getDouble("hi", Double.NaN);
        lo = b.getDouble("lo", Double.NaN);
        noon = b.getDouble("noon", Double.NaN);
        evening = b.getDouble("evening", Double.NaN);
        code = b.getInt("code", -1);
        alertEvent = strings(b, "alertEvent");
        alertSeverity = strings(b, "alertSeverity");
        alertHeadline = strings(b, "alertHeadline");
        long[] ends = b.getLongArray("alertEnds");
        alertEnds = ends != null && ends.length == alertEvent.length ? ends : new long[alertEvent.length];
    }

    private static String[] strings(Bundle b, String key) {
        String[] v = b.getStringArray(key);
        return v != null ? v : new String[0];
    }

    /** True for warnings and Extreme/Severe alerts: shown in red rather than amber. */
    boolean urgent(int i) {
        return alertEvent[i].endsWith("Warning") || "Extreme".equals(alertSeverity[i]) || "Severe".equals(alertSeverity[i]);
    }

    /** "Tornado Warning until 6:15 PM", plus how many more there are. */
    String alertLine(Context c) {
        if (alertEvent.length == 0) return null;
        StringBuilder s = new StringBuilder("⚠  ").append(alertEvent[0]);
        if (alertEnds[0] > 0) {
            s.append(" until ").append(android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(alertEnds[0])));
        }
        if (alertEvent.length > 1) s.append("   +").append(alertEvent.length - 1).append(" more");
        return s.toString();
    }

    /** The question for the helper, or null when weather is off or no place is set. UI thread. */
    static Bundle request(Config cfg) {
        if (!"on".equals(cfg.get(Config.WEATHER))) return null;
        double lat = number(cfg.get("weather.lat", "")), lon = number(cfg.get("weather.lon", ""));
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        Bundle ask = new Bundle();
        ask.putDouble("lat", lat);
        ask.putDouble("lon", lon);
        ask.putString("units", cfg.get("weather.units", "fahrenheit"));
        return ask;
    }

    /** The saved forecast, or null when the helper is missing or has none yet. Off the UI thread. */
    static Weather read(Context c, Bundle request) {
        try {
            Bundle b = c.getContentResolver().call(HELPER, "weather", null, request);
            if (b == null || Double.isNaN(b.getDouble("now", Double.NaN))) return null;
            return new Weather(b);
        } catch (RuntimeException e) {
            // IllegalArgumentException when the helper is not installed; anything else the
            // helper throws must not reach the home screen either.
            return null;
        }
    }

    static String degrees(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "–" : Math.round(v) + "°";
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code == 1 || code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    private static double number(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
