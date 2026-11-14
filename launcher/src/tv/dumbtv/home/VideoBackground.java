package tv.dumbtv.home;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Background › Video file: a muted, looping video from the app's folder behind the home screen.
 *
 * A bare MediaPlayer rather than VideoView, which takes audio focus and would pause music playing
 * elsewhere. The player is released in stop(), which Home calls from onPause, so the TV's video
 * decoder is free again before any app opens.
 */
final class VideoBackground extends SurfaceView implements SurfaceHolder.Callback {
    private MediaPlayer player;
    private File file, broken;
    private boolean wanted, surface;
    private Runnable failed;
    private int retries;

    VideoBackground(Context c) {
        super(c);
        getHolder().addCallback(this);
        setVisibility(View.GONE);
    }

    void play(File f) {
        if (wanted && f.equals(file) && player != null) return;
        stop();
        file = f;
        wanted = true;
        retries = 0;
        setVisibility(View.VISIBLE);
        if (surface) open();
    }

    void stop() {
        wanted = false;
        if (player != null) {
            player.release();
            player = null;
        }
        setVisibility(View.GONE);
    }

    /**
     * The file is opened here and handed over as a descriptor: the media server cannot read
     * this app's folder by path, and a failed path leaves the player unusable. Anything that goes
     * wrong hides the video; it never takes the home screen down with it.
     */
    private void open() {
        if (!wanted || player != null || !surface) return;
        MediaPlayer p = new MediaPlayer();
        try (FileInputStream in = new FileInputStream(file)) {
            p.setDataSource(in.getFD());
        } catch (IOException | RuntimeException e) {
            // The file itself cannot be read: that will not fix itself.
            p.release();
            fail(e.toString());
            return;
        }
        try {
            p.setDisplay(getHolder());
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnErrorListener((mp, what, extra) -> {
                fail("error " + what + "/" + extra);
                return true;
            });
            p.prepareAsync();
            player = p;
        } catch (RuntimeException e) {
            // Usually the surface or the decoder was still being handed back from the last
            // play: try again shortly rather than giving up on the video.
            Log.w("dumbtv", "video background, retrying", e);
            p.release();
            if (++retries <= 3) postDelayed(this::open, 1000);
            else fail(e.toString());
        }
    }

    private void fail(String why) {
        Log.w("dumbtv", "video background: " + why);
        broken = file;
        stop();
        if (failed != null) failed.run();
    }

    /** Called when the video cannot play, so the caller can fall back to a drawn background. */
    void onFailure(Runnable r) {
        failed = r;
    }

    /** True once {@code f} has failed to play; it is not tried again until the app restarts. */
    boolean broken(File f) {
        return f.equals(broken);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = true;
        if (wanted && player == null) open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
