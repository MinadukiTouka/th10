package com.zun.th10;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

/**
 * The game surface: SDLActivity + a two-button touch overlay.
 *
 * SDL2 drives everything else (window, GLES 3 context, audio, touch and key
 * events) on its own thread, which runs libmain.so -> SDL_main(). The buttons are
 * plain Android views on top of SDL's SurfaceView, because drawing UI in the
 * ported renderer would mean adding a whole 2D path to it.
 *
 *   pause button  sends Escape to the game for a few frames (pause / back / cancel)
 *   quit button   ends the activity (the game writes saves as it goes)
 *
 * Four-finger tap also sends Escape (see TouchController in the engine); the
 * buttons exist because that gesture is unusable mid-dodge.
 */
public class GameActivity extends SDLActivity {
    /** Called from Java: queue an Escape key press for the game. */
    private static native void nativeTapEsc();

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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        addOverlay();
    }

    private void addOverlay() {
        if (mLayout == null) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button esc = overlayButton("\u2261");   // identical-to: a "menu" glyph
        esc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapEsc(); }
        });
        Button quit = overlayButton("\u2715");  // multiplication x
        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestFinish(); }
        });
        bar.addView(esc);
        bar.addView(quit);

        int margin = dp(6);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(margin, margin, margin, margin);
        mLayout.addView(bar, params);
        bar.bringToFront();
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

    /**
     * Called from the native thread when the game loop ends (SDL_main returns).
     * finish() must run on the UI thread.
     */
    public void requestFinish() {
        runOnUiThread(new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    /** Back is the game's Escape: pause, back out of a menu, cancel a dialog. */
    @Override
    public void onBackPressed() {
        tapEsc();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
