package tv.dumbtv.home;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in place of Amazon's (which shows ads): the home screen's background with a
 * large clock, the date, the Christmas countdown, the weather, a quote and any weather alert.
 *
 * Chosen over adb with {@code settings put secure screensaver_components tv.dumbtv.home/.Screensaver};
 * launcher/README.md has the commands. It follows Options: background, night mode, countdown,
 * weather and quotes. The text block drifts a little every few minutes so nothing sits still.
 */
public final class Screensaver extends DreamService {
    private static final long TICK_MS = 30_000;
    private static final int MATCH = FrameLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = FrameLayout.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::tick;
    private Config cfg;
    private Christmas scene;
    private VideoBackground video;
    private View nightShade;
    private LinearLayout block;
    private TextView clock, date, days, weather, quote, alert;
    private List<String[]> quotes;
    private int moves;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        cfg = Config.get(this);
        cfg.refresh();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        String mode = cfg.get(Config.BACKGROUND);
        boolean christmas = "christmas".equals(mode);
        File movie = "video".equals(mode) || christmas ? cfg.own(Config.VIDEO) : null;
        if (movie != null) {
            video = new VideoBackground(this);
            root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));
            video.play(movie);
            View fade = new View(this);
            fade.setBackgroundColor(christmas ? Christmas.VIDEO_FADE : 0x59000000);
            root.addView(fade, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        if (christmas) {
            scene = new Christmas(this);
            scene.setOverVideo(movie != null);
            root.addView(scene, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        View scrim = new View(this);
        scrim.setBackgroundColor(0x40000000);
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        nightShade = new View(this);
        nightShade.setBackgroundColor(0x80000000);
        root.addView(nightShade, new FrameLayout.LayoutParams(MATCH, MATCH));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = Ui.text(this, 120, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 26, Ui.TEXT, Ui.MEDIUM);
        days = Ui.text(this, 22, 0xFFE8C46A, Ui.MEDIUM);
        weather = Ui.text(this, 24, Ui.TEXT, Ui.MEDIUM);
        alert = Ui.text(this, 22, 0xFFFFFFFF, Ui.MEDIUM);
        alert.setPadding(dp(20), dp(10), dp(20), dp(10));
        quote = Ui.text(this, 22, Ui.TEXT, Ui.LIGHT);
        quote.setGravity(Gravity.CENTER);
        quote.setMaxWidth(dp(820));
        for (TextView v : new TextView[] {clock, date, days, weather, alert, quote}) {
            v.setGravity(Gravity.CENTER);
            v.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.topMargin = v == clock ? 0 : dp(10);
            block.addView(v, lp);
        }
        root.addView(block, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
        File own = cfg.own(Quotes.FILE);
        io.execute(() -> {
            List<String[]> list = Quotes.load(this, own);
            ui.post(() -> {
                quotes = list;
                tick();
            });
        });
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacks(tick);
        if (scene != null) scene.stop();
        if (video != null) video.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void tick() {
        ui.removeCallbacks(tick);
        Date now = new Date();
        boolean h24 = "24h".equals(cfg.get(Config.CLOCK))
                || "auto".equals(cfg.get(Config.CLOCK)) && DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now));
        Calendar cal = Calendar.getInstance();
        String countdown = "on".equals(cfg.get(Config.COUNTDOWN)) ? Countdown.line(cal) : null;
        show(days, countdown);
        boolean night = cfg.night(cal.get(Calendar.HOUR_OF_DAY));
        nightShade.setVisibility(night ? View.VISIBLE : View.GONE);
        if (scene != null) scene.setNight(night);

        String[] q = quotes == null ? null : Quotes.pick(quotes, Quotes.slot(cfg.get(Config.QUOTES), now.getTime()));
        show(quote, q == null ? null : "“" + q[0] + "”" + (q[1].isEmpty() ? "" : "\n— " + q[1]));

        Bundle ask = Weather.request(cfg);
        if (ask != null) {
            io.execute(() -> {
                Weather w = Weather.read(this, ask);
                ui.post(() -> showWeather(w));
            });
        } else {
            showWeather(null);
        }

        // Drift the block around the middle every few minutes.
        if (++moves % 6 == 0) {
            int dx = dp((moves / 6 % 5 - 2) * 40), dy = dp((moves / 6 % 3 - 1) * 30);
            block.animate().translationX(dx).translationY(dy).setDuration(4000).start();
        }
        ui.postDelayed(tick, TICK_MS);
    }

    private void showWeather(Weather w) {
        show(weather, w == null ? null : Weather.degrees(w.now) + "  " + Weather.condition(w.code)
                + "    High " + Weather.degrees(w.hi) + "  Low " + Weather.degrees(w.lo));
        String warning = w == null ? null : w.alertLine(this);
        show(alert, warning);
        if (warning != null) alert.setBackground(Ui.round(w.urgent(0) ? 0xE6C62828 : 0xE6B26A00, dp(12)));
    }

    private static void show(TextView v, String text) {
        v.setText(text);
        v.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
