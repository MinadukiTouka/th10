package com.zun.th10;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

/**
 * The game surface: SDLActivity + a two-button touch overlay.
 *
 * SDL2 drives everything else (window, GLES 3 context, audio, touch and key
 * events) on its own thread, which runs libmain.so -> SDL_main(). The buttons are
 * plain Android views on top of SDL's SurfaceView, because drawing UI in the
 * ported renderer would mean adding a whole 2D path to it.
 *
 *   "≡"    sends Escape (pause / back / cancel)
 *   "Esc"  same thing, as a round button that says what it does
 *
 * There is no quit button on purpose: leaving the game is Home, and the pause
 * menu has "return to title". The overlay sits in the corner of the dominant
 * hand (right-handed: top-left, left-handed: top-right), matching the TH08 port.
 */
public class GameActivity extends SDLActivity {
    /** Called from Java: queue an Escape key press for the game. */
    private static native void nativeTapEsc();

    /** 1 = left-handed: the bomb bar and the overlay move to the left side. */
    private static native void nativeSetHandedness(int leftHanded);

    private static final String PREFS = "port";
    private static final String KEY_LEFT_HANDED = "left_handed";

    private LinearLayout overlay;

    /**
     * Guard for the (unlikely) case that the native libraries failed to load:
     * an UnsatisfiedLinkError from a button press would otherwise crash the app.
     */
    private static void tapEsc() {
        try {
            nativeTapEsc();
        } catch (Throwable ignored) {
        }
    }

    private static void pushHandedness(boolean leftHanded) {
        try {
            nativeSetHandedness(leftHanded ? 1 : 0);
        } catch (Throwable ignored) {
        }
    }

    private boolean isLeftHanded() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getBoolean(KEY_LEFT_HANDED, false);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        addOverlay();
        requestDisplayRefreshRate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The launcher can change the handedness while the game task is alive.
        pushHandedness(isLeftHanded());
        placeOverlay();
        requestDisplayRefreshRate();
    }

    /**
     * Ask the panel to run at 60 Hz. Mountain of Faith's logic is a 60 Hz frame
     * clock; on a 90/120 Hz panel the loop can only present each game frame once
     * per display refresh, which either wastes frames or (with a naive loop) runs
     * the game fast. Asking for a fixed 60 Hz source matches the PC original and
     * the TH08 port's behaviour.
     */
    private void requestDisplayRefreshRate() {
        if (Build.VERSION.SDK_INT < 30) return;
        final long[] delays = {600, 2000};
        for (final long delay : delays) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        Surface surface = (mSurface != null) ? mSurface.getHolder().getSurface() : null;
                        if (surface != null && surface.isValid()) {
                            surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, delay);
        }
    }

    private void addOverlay() {
        if (mLayout == null) return;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);

        Button esc = overlayButton("\u2261");   // identical-to: a "menu" glyph
        esc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapEsc(); }
        });
        Button escRound = roundEscButton();
        escRound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapEsc(); }
        });
        overlay.addView(esc);
        overlay.addView(escRound);

        mLayout.addView(overlay, overlayParams());
        overlay.bringToFront();
        pushHandedness(isLeftHanded());
    }

    /** Put the overlay in the top corner of the dominant hand. */
    private void placeOverlay() {
        if (overlay == null) return;
        overlay.setLayoutParams(overlayParams());
        overlay.bringToFront();
    }

    private RelativeLayout.LayoutParams overlayParams() {
        int margin = dp(6);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.addRule(isLeftHanded()
                ? RelativeLayout.ALIGN_PARENT_RIGHT
                : RelativeLayout.ALIGN_PARENT_LEFT);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private Button overlayButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        b.setTextColor(0xCCFFFFFF);
        b.setBackgroundColor(0x00000000);
        b.setAlpha(0.55f);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(40));
        lp.rightMargin = dp(2);
        b.setLayoutParams(lp);
        return b;
    }

    /** Round outlined button with the word "Esc" inside; same action as Escape. */
    private Button roundEscButton() {
        Button b = new Button(this);
        b.setText("Esc");
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTextColor(0xE6FFFFFF);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x40000000);
        circle.setStroke(Math.max(1, dp(2)), 0xCCFFFFFF);
        b.setBackground(circle);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        int size = dp(56);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * Called from the native thread when the game loop ends (SDL_main returns).
     * finish() must run on the UI thread.
     */
    public void requestFinish() {
        runOnUiThread(new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    /**
     * Called from the native thread when startup fails (missing th10.dat, no GL
     * context, ...). Shows the reason and leaves a couple of seconds for it to be
     * read before the activity closes; the same text is in logcat under "th10".
     */
    public void showErrorAndFinish(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(GameActivity.this, message, Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { finish(); }
                }, 2500);
            }
        });
    }

    /** Back is the game's Escape: pause, back out of a menu, cancel a dialog. */
    @Override
    public void onBackPressed() {
        tapEsc();
    }

    /**
     * SDL's SurfaceView consumes KEYCODE_BACK before the activity sees it, so
     * {@link #onBackPressed()} alone never runs. Intercept the key on the way in
     * and turn it into the game's Escape.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) tapEsc();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
