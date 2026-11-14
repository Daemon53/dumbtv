package tv.dumbtv.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 10-day forecast, opened by selecting the weather on the home screen. Each day shows its
 * sky, chance of rain, low and high, with a bar placing that day's range among the ten. Active
 * alerts are listed above with their full headlines. BACK returns home.
 */
public final class Forecast extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(0xFF0C0C0E);
        list.setPadding(dp(72), dp(32), dp(72), dp(24));
        // The window takes the remote's keys; BACK closes it.
        list.setFocusable(true);
        setContentView(list);
        title("10-day forecast");
        Bundle ask = Weather.request(Config.get(this));
        if (ask == null) {
            note("Set weather.lat and weather.lon in home.conf to see the forecast.");
            return;
        }
        note("Loading…");
        io.execute(() -> {
            Weather w = Weather.read(this, ask);
            runOnUiThread(() -> show(w));
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void show(Weather w) {
        if (isDestroyed()) return;
        list.removeAllViews();
        title("10-day forecast");
        if (w == null || w.dayTime.length == 0) {
            note("The forecast isn't ready yet. Try again in a minute.");
            return;
        }
        note("Now " + Weather.degrees(w.now) + "  " + Weather.condition(w.code));
        for (int i = 0; i < w.alertEvent.length; i++) {
            TextView a = Ui.text(this, 17, 0xFFFFFFFF, Ui.MEDIUM);
            a.setText("⚠  " + w.alertEvent[i] + (w.alertEnds[i] > 0 ? " until "
                    + android.text.format.DateFormat.format("EEE h:mm a", w.alertEnds[i]) : ""));
            a.setPadding(dp(16), dp(8), dp(16), dp(8));
            a.setBackground(Ui.round(w.urgent(i) ? 0xE6C62828 : 0xE6B26A00, dp(10)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.bottomMargin = dp(8);
            list.addView(a, lp);
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < w.dayTime.length; i++) {
            if (!Double.isNaN(w.dayLo[i])) min = Math.min(min, w.dayLo[i]);
            if (!Double.isNaN(w.dayHi[i])) max = Math.max(max, w.dayHi[i]);
        }
        int accent = Config.get(this).accent();
        for (int i = 0; i < w.dayTime.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Sized to fit a 960 dp wide TV screen with all ten days showing.
            row.setPadding(0, dp(5), 0, dp(5));
            row.addView(cell(day(w.dayTime[i], i), 18, Ui.TEXT, 140, Gravity.START));
            row.addView(cell(Weather.condition(w.dayCode[i]), 16, Ui.MUTED, 170, Gravity.START));
            row.addView(cell(w.dayRain[i] > 0 ? w.dayRain[i] + "% rain" : "", 15, 0xFF7FB8FF, 110, Gravity.START));
            row.addView(cell(Weather.degrees(w.dayLo[i]), 18, Ui.MUTED, 56, Gravity.END));
            Range bar = new Range(this, w.dayLo[i], w.dayHi[i], min, max, accent);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(220), dp(8));
            blp.leftMargin = dp(16);
            blp.rightMargin = dp(16);
            row.addView(bar, blp);
            row.addView(cell(Weather.degrees(w.dayHi[i]), 18, Ui.TEXT, 56, Gravity.START));
            list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        TextView credit = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        credit.setText("Forecast: Open-Meteo.com  ·  Alerts: National Weather Service");
        credit.setPadding(0, dp(16), 0, 0);
        list.addView(credit);
    }

    /** "Today", "Tomorrow", then "Mon 16". */
    private static String day(String iso, int index) {
        if (index == 0) return "Today";
        if (index == 1) return "Tomorrow";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            return new SimpleDateFormat("EEE d", Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return iso;
        }
    }

    private void title(String text) {
        TextView t = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        t.setText(text);
        t.setPadding(0, 0, 0, dp(8));
        list.addView(t);
    }

    private void note(String text) {
        TextView n = Ui.text(this, 18, Ui.MUTED, Ui.MEDIUM);
        n.setText(text);
        n.setPadding(0, 0, 0, dp(10));
        list.addView(n);
    }

    private TextView cell(String text, float sp, int color, int widthDp, int gravity) {
        TextView v = Ui.text(this, sp, color, Ui.MEDIUM);
        v.setText(text);
        v.setGravity(gravity);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), WRAP));
        return v;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** A day's low-to-high span, placed on the scale of the whole ten days. */
    private static final class Range extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float from, to;
        private final int accent;

        Range(Context c, double lo, double hi, double min, double max, int accent) {
            super(c);
            double span = Math.max(1, max - min);
            from = Double.isNaN(lo) ? 0f : (float) ((lo - min) / span);
            to = Double.isNaN(hi) ? 1f : (float) ((hi - min) / span);
            this.accent = accent;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            paint.setColor(0x26FFFFFF);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(accent);
            rect.set(w * from, 0, Math.max(w * from + h, w * to), h);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
