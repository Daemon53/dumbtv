package tv.dumbtv.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Options › Quotes: a line under the clock that changes every hour, four hours or day.
 *
 * The list is quotes.txt in the app's folder (or its media folder on Fire OS 8), else the one
 * bundled in res/raw. One quote per line, optionally followed by " — " and who said it.
 */
final class Quotes {
    static final String FILE = "quotes.txt";
    private static final String BY = " — ";

    private Quotes() {}

    /** Each entry is {quote, author}; author may be empty. Reads a file: off the UI thread. */
    static List<String[]> load(Context c, File own) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = own != null ? new FileInputStream(own) : c.getResources().openRawResource(R.raw.quotes);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int by = line.lastIndexOf(BY);
                if (by > 0) out.add(new String[] {line.substring(0, by).trim(), line.substring(by + BY.length()).trim()});
                else out.add(new String[] {line, ""});
            }
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** Which period {@code now} falls in for {@code mode}, counted in local time; -1 when off. */
    static long slot(String mode, long now) {
        long local = now + TimeZone.getDefault().getOffset(now);
        switch (mode) {
            case "hourly": return local / 3_600_000L;
            case "4h": return local / 14_400_000L;
            case "daily": return local / 86_400_000L;
            default: return -1;
        }
    }

    /** The quote for {@code slot}: a fixed shuffle, so neighbouring periods rarely repeat one. */
    static String[] pick(List<String[]> all, long slot) {
        int n = all.size();
        if (n == 0 || slot < 0) return null;
        // Stepping by a stride coprime to n visits every quote once before any comes back.
        int stride = n > 2 ? n / 2 + 1 : 1;
        while (gcd(stride, n) != 1) stride++;
        return all.get((int) ((slot % n) * stride % n));
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
