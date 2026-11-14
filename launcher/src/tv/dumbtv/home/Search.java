package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where-to-watch search: type a title, see which services stream it, open one of your apps.
 *
 * The lookup runs in the tv.dumbtv.weather helper (Titles there), which holds the internet
 * permission and the owner's TMDB key; this screen only shows what it returns. The first time,
 * it asks for the key, which the owner types in with the remote.
 */
public final class Search extends Activity {
    private static final Uri HELPER = Uri.parse("content://tv.dumbtv.weather");
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** TMDB/JustWatch provider names, lower case, to the Fire TV apps that play them. */
    private static final Map<String, String[]> APPS = new LinkedHashMap<>();

    static {
        APPS.put("netflix", new String[] {"com.netflix.ninja"});
        APPS.put("amazon prime video", new String[] {"com.amazon.firebat", "com.amazon.avod"});
        APPS.put("amazon prime video with ads", new String[] {"com.amazon.firebat", "com.amazon.avod"});
        APPS.put("disney plus", new String[] {"com.disney.disneyplus"});
        APPS.put("max", new String[] {"com.hbo.hbonow"});
        APPS.put("hbo max", new String[] {"com.hbo.hbonow"});
        APPS.put("apple tv plus", new String[] {"com.apple.atve.amazon.appletv"});
        APPS.put("apple tv+", new String[] {"com.apple.atve.amazon.appletv"});
        APPS.put("pluto tv", new String[] {"tv.pluto.android"});
        APPS.put("youtube", new String[] {"com.amazon.firetv.youtube"});
        APPS.put("youtube premium", new String[] {"com.amazon.firetv.youtube"});
        APPS.put("hulu", new String[] {"com.hulu.plus"});
        APPS.put("peacock", new String[] {"com.peacock.peacockfiretv"});
        APPS.put("peacock premium", new String[] {"com.peacock.peacockfiretv"});
        APPS.put("paramount plus", new String[] {"com.cbs.ott"});
        APPS.put("paramount+", new String[] {"com.cbs.ott"});
        APPS.put("tubi tv", new String[] {"com.tubitv.ott"});
        APPS.put("crunchyroll", new String[] {"com.crunchyroll.crunchyroid"});
        APPS.put("starz", new String[] {"com.starz.starzplay.firetv"});
        APPS.put("amc+", new String[] {"com.amcplus.amcfiretv"});
        APPS.put("plex", new String[] {"com.plexapp.android"});
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout page, results;
    private EditText field;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(72), dp(32), dp(72), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0C0C0E);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
        io.execute(() -> {
            Bundle b = call("hasKey", null);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (b == null) helperMissing();
                else if (b.getBoolean("hasKey")) searchPage();
                else keyPage();
            });
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void searchPage() {
        page.removeAllViews();
        title("Search");
        field = input("Movie or show title", false);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, action, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                run(field.getText().toString());
                return true;
            }
            return false;
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView credit = text(13, Ui.FAINT);
        credit.setText("Titles from TMDB; this product uses the TMDB API but is not endorsed or certified by TMDB."
                + "\nWhere to watch: JustWatch, via TMDB.");
        credit.setPadding(0, dp(18), 0, 0);
        page.addView(credit);
        field.requestFocus();
    }

    private void keyPage() {
        page.removeAllViews();
        title("Set up search");
        TextView how = text(18, Ui.TEXT);
        how.setText("Search looks titles up on TMDB, which needs a free key from your own account:\n"
                + "1. On your phone or computer, sign in at themoviedb.org.\n"
                + "2. Open Settings › API and request a personal key.\n"
                + "3. Type the “API Key” shown there into the box below, then press Select.\n"
                + "It is kept on this TV only, in the weather helper app.");
        how.setLineSpacing(0, 1.3f);
        how.setPadding(0, 0, 0, dp(16));
        page.addView(how);
        EditText key = input("API key", true);
        key.setOnEditorActionListener((v, action, e) -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) return true;
            io.execute(() -> {
                call("setKey", k);
                runOnUiThread(this::searchPage);
            });
            return true;
        });
        key.requestFocus();
    }

    private void helperMissing() {
        page.removeAllViews();
        title("Search");
        TextView t = text(18, Ui.MUTED);
        t.setText("Search needs the dumbtv weather helper app (weather/ in the repo), which is not installed.");
        page.addView(t);
    }

    private void run(String query) {
        if (query.trim().isEmpty()) return;
        results.removeAllViews();
        status("Searching…");
        io.execute(() -> {
            Bundle b = call("search", query);
            runOnUiThread(() -> show(b));
        });
    }

    private void show(Bundle b) {
        if (isDestroyed()) return;
        results.removeAllViews();
        if (b == null) {
            keyPage();
            return;
        }
        String error = b.getString("error");
        String[] titles = b.getStringArray("title"), years = b.getStringArray("year"), kinds = b.getStringArray("kind"),
                stream = b.getStringArray("stream"), rent = b.getStringArray("rent");
        if (titles == null || years == null || kinds == null || stream == null || rent == null) return;
        if (error != null) status(error + (error.contains("key") ? " Press Back and open Search again to re-enter it." : ""));
        else if (titles.length == 0) status("Nothing found.");
        View first = null;
        for (int i = 0; i < titles.length; i++) {
            View row = row(titles[i], years[i], kinds[i], list(stream[i]), list(rent[i]));
            if (first == null) first = row;
            results.addView(row);
        }
        if (first != null) first.requestFocus();
        if (error != null && error.contains("key")) io.execute(() -> call("setKey", ""));
    }

    private View row(String title, String year, String kind, List<String> on, List<String> paid) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        int accent = Config.get(this).accent();
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round((accent & 0x00FFFFFF) | 0x33000000, dp(10)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(10)));
        row.setBackground(bg);

        TextView name = text(20, Ui.TEXT);
        name.setText(title + (year.isEmpty() ? "" : "  (" + year + ")") + "   " + ("tv".equals(kind) ? "Series" : "Movie"));
        row.addView(name);

        // One entry per installed app: "Netflix" and "Netflix Standard with Ads" are the same app.
        List<String> mine = new ArrayList<>(), others = new ArrayList<>(), seen = new ArrayList<>();
        for (String s : on) {
            String pkg = installed(s);
            if (pkg == null) others.add(s);
            else if (!seen.contains(pkg)) {
                seen.add(pkg);
                mine.add(s);
            }
        }
        TextView where = text(16, Ui.MUTED);
        StringBuilder w = new StringBuilder();
        if (!mine.isEmpty()) w.append("On your apps: ").append(join(mine));
        if (!others.isEmpty()) w.append(w.length() == 0 ? "" : "    ").append("Also on: ").append(join(others));
        if (w.length() == 0) w.append(paid.isEmpty() ? "Not streaming in the US right now" : "Rent or buy: " + join(paid));
        where.setText(w);
        where.setTextColor(mine.isEmpty() ? Ui.MUTED : 0xFF8FD18F);
        row.addView(where);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> open(title, mine, on, paid));
        return row;
    }

    /** One installed service opens at once; several ask which; none says where it is. */
    private void open(String title, List<String> mine, List<String> on, List<String> paid) {
        if (mine.size() == 1) {
            launch(mine.get(0), title);
            return;
        }
        if (mine.size() > 1) {
            CharSequence[] names = mine.toArray(new CharSequence[0]);
            new AlertDialog.Builder(this, Ui.DIALOG).setTitle(title)
                    .setItems(names, (d, which) -> launch(mine.get(which), title)).show();
            return;
        }
        String where = !on.isEmpty() ? "Streaming on " + join(on) : !paid.isEmpty() ? "Rent or buy on " + join(paid) : "";
        Toast.makeText(this, where.isEmpty() ? "Not on any of your apps." : where + ", not installed here.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Opens the service's app, straight into its own search for the title where the app takes
     * one (YouTube, Netflix), otherwise at its home screen.
     */
    private void launch(String service, String title) {
        String pkg = installed(service);
        if (pkg == null) return;
        List<Intent> tries = new ArrayList<>();
        if ("com.amazon.firetv.youtube".equals(pkg)) {
            tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, title));
        }
        if ("com.netflix.ninja".equals(pkg)) {
            tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(title)))
                    .setPackage(pkg));
        }
        PackageManager pm = getPackageManager();
        Intent home = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (home == null) home = pm.getLaunchIntentForPackage(pkg);
        if (home != null) tries.add(home);
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Recent.opened(this, pkg);
                // Gone once the app opens, so HOME afterwards lands on the home screen, not here.
                finish();
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // next way in
            }
        }
        Toast.makeText(this, "Could not open " + service, Toast.LENGTH_SHORT).show();
    }

    /**
     * The installed package for a service name, or null. Plans of one service ("Netflix Standard
     * with Ads", "Peacock Premium Plus") match it by their leading name.
     */
    private String installed(String service) {
        String name = service.toLowerCase(Locale.ROOT);
        String[] pkgs = APPS.get(name);
        for (Map.Entry<String, String[]> e : APPS.entrySet()) {
            if (pkgs == null && name.startsWith(e.getKey() + " ")) pkgs = e.getValue();
        }
        if (pkgs == null) return null;
        PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                if (pm.getApplicationInfo(p, 0).enabled) return p;
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed
            }
        }
        return null;
    }

    private Bundle call(String method, String arg) {
        try {
            return getContentResolver().call(HELPER, method, arg, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> list(String joined) {
        List<String> out = new ArrayList<>();
        if (joined != null) for (String s : joined.split("\\|")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static String join(List<String> names) {
        StringBuilder s = new StringBuilder();
        for (String n : names) s.append(s.length() == 0 ? "" : ", ").append(n);
        return s.toString();
    }

    private void title(String text) {
        TextView t = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        t.setText(text);
        t.setPadding(0, 0, 0, dp(12));
        page.addView(t);
    }

    private void status(String s) {
        TextView t = text(17, Ui.MUTED);
        t.setText(s);
        t.setPadding(dp(16), dp(8), 0, dp(8));
        results.addView(t);
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setTextSize(20);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.FAINT);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setBackground(Ui.round(0xFF1C1D21, dp(10)));
        e.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), WRAP);
        lp.bottomMargin = dp(12);
        page.addView(e, lp);
        return e;
    }

    private TextView text(float sp, int color) {
        return Ui.text(this, sp, color, Ui.MEDIUM);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
