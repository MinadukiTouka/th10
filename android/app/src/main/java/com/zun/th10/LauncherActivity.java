package com.zun.th10;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Data check + start screen, plus the two port settings that have to exist
 * before the game starts: handedness (which side the bomb bar and the overlay
 * live on) and unpacking the assets that are bundled in the APK.
 *
 * Mountain of Faith needs th10.dat (the game archive, 27 MB) and thbgm.dat
 * (403 MB) from a legally owned 1.00a copy. The public APK ships neither; the
 * "-full" build bundles both plus msgothic.ttc and unpacks them here on first
 * launch. The same search order is repeated in the native host
 * (th10-port/android-host/AndroidMain.cpp); keep the two lists in step:
 *
 *   1. /storage/emulated/0/Download/th10        (needs "All files access")
 *   2. /sdcard/Android/data/com.zun.th10/files/th10   (adb push, no permission)
 *   3. /data/data/com.zun.th10/files/th10       (private)
 */
public class LauncherActivity extends Activity {
    private static final String PREFS = "port";
    private static final String KEY_LEFT_HANDED = "left_handed";
    /** Assets that may be bundled; the -full build has all three. */
    private static final String[] BUNDLED = {"th10.dat", "thbgm.dat", "msgothic.ttc"};

    private TextView status;
    private TextView detail;
    private TextView progress;
    private Button play;
    private Button handed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101014);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("東方風神録 ～ Mountain of Faith");
        title.setTextSize(22f);
        title.setTextColor(0xFFF2F2F2);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android 移植 · 原版 1.00a 数据");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(0xFF9A9AA5);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(16f);
        status.setPadding(0, 0, 0, dp(6));
        root.addView(status);

        detail = new TextView(this);
        detail.setTextSize(12f);
        detail.setTextColor(0xFF9A9AA5);
        detail.setPadding(0, 0, 0, dp(10));
        root.addView(detail);

        progress = new TextView(this);
        progress.setTextSize(12f);
        progress.setTextColor(0xFF8AB4F8);
        progress.setPadding(0, 0, 0, dp(12));
        root.addView(progress);

        play = button("开始游戏");
        play.setOnClickListener(v -> onPlay());
        root.addView(play);

        handed = button(labelForHandedness());
        handed.setOnClickListener(v -> {
            setLeftHanded(!isLeftHanded());
            handed.setText(labelForHandedness());
            refresh();
        });
        root.addView(handed);

        Button again = button("重新检查");
        again.setOnClickListener(v -> refresh());
        root.addView(again);

        TextView help = new TextView(this);
        help.setText("把 th10.dat 与 thbgm.dat 放进 Download/th10/ 就能玩。\n"
                + "也可以用 adb 推到 /sdcard/Android/data/com.zun.th10/files/th10/，这样不需要任何权限。\n"
                + "存档（scoreth10.dat、th10.cfg、replay/）写在 /sdcard/Android/data/com.zun.th10/files/th10-save/。\n\n"
                + "触屏：按住拖动移动并射击，第二根手指 = 低速。\n"
                + "Bomb = 点画面一侧的黑边（右手模式在右侧，左手模式在左侧）。\n"
                + "左上角「≡」和圆形「Esc」都是 Esc；Back 键同 Esc。没有退出按钮，回桌面用 Home。");
        help.setTextSize(12f);
        help.setTextColor(0xFF7C7C88);
        help.setPadding(0, dp(20), 0, 0);
        root.addView(help);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- settings

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isLeftHanded() {
        return prefs().getBoolean(KEY_LEFT_HANDED, false);
    }

    private void setLeftHanded(boolean left) {
        prefs().edit().putBoolean(KEY_LEFT_HANDED, left).apply();
    }

    private String labelForHandedness() {
        return "触屏惯用手：" + (isLeftHanded() ? "左手（Esc 右上 / Bomb 左侧黑边）"
                                               : "右手（Esc 左上 / Bomb 右侧黑边）");
    }

    // ------------------------------------------------------------------ launch

    private void onPlay() {
        List<String> todo = missingBundled();
        if (todo.isEmpty()) {
            if (dataDir() == null) {
                refresh();
                return;
            }
            startGame();
            return;
        }
        extractThenPlay(todo);
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------- bundled assets

    private File extractDir() {
        File ext = getExternalFilesDir(null);
        File base = (ext != null) ? ext : getFilesDir();
        File dir = new File(base, "th10");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private boolean isBundled(String name) {
        try (AssetFileDescriptor fd = getAssets().openFd(name)) {
            return fd != null;
        } catch (IOException e) {
            return false;
        }
    }

    private long bundledLength(String name) {
        try (AssetFileDescriptor fd = getAssets().openFd(name)) {
            return (fd != null) ? fd.getLength() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Bundled files that are not unpacked yet (or are a different size). */
    private List<String> missingBundled() {
        File dir = extractDir();
        List<String> todo = new ArrayList<>();
        for (String name : BUNDLED) {
            if (!isBundled(name)) continue;
            File out = new File(dir, name);
            long length = bundledLength(name);
            if (out.isFile() && (length < 0 ? true : out.length() == length)) continue;
            todo.add(name);
        }
        return todo;
    }

    private void extractThenPlay(final List<String> todo) {
        play.setEnabled(false);
        progress.setText("准备释放内置数据…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (String name : todo) {
                        unpack(name);
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setText("内置数据已就绪");
                            play.setEnabled(true);
                            refresh();
                            startGame();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setText("释放失败：" + e.getMessage());
                            play.setEnabled(true);
                        }
                    });
                }
            }
        }, "th10-unpack").start();
    }

    private void unpack(final String name) throws IOException {
        File dir = extractDir();
        File out = new File(dir, name);
        final long total = bundledLength(name);
        File tmp = new File(dir, name + ".part");
        long done = 0;
        try (InputStream in = getAssets().open(name);
             FileOutputStream os = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
                done += read;
                if (total > 0 && done % (16L << 20) < (1L << 20)) {
                    final int percent = (int) (done * 100 / total);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setText("释放 " + name + "：" + percent + "%");
                        }
                    });
                }
            }
            os.getFD().sync();
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("rename failed for " + name);
        }
    }

    // ------------------------------------------------------------ data status

    private File dataDir() {
        for (File dir : candidateDirs()) {
            if (new File(dir, "th10.dat").isFile()) return dir;
        }
        return null;
    }

    private File[] candidateDirs() {
        File pub = new File(Environment.getExternalStorageDirectory(), "Download/th10");
        File ext = getExternalFilesDir(null);
        File extDir = ext != null ? new File(ext, "th10") : pub;
        return new File[]{pub, extDir, new File(getFilesDir(), "th10")};
    }

    private boolean allFiles() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private File saveDir() {
        File ext = getExternalFilesDir(null);
        return new File(ext != null ? ext : getFilesDir(), "th10-save");
    }

    private void refresh() {
        File dir = dataDir();
        StringBuilder sb = new StringBuilder();
        if (dir == null) {
            status.setText("找不到 th10.dat");
            status.setTextColor(0xFFFF9E64);
            sb.append("已查找：\n");
            for (File d : candidateDirs()) sb.append("  ").append(d.getAbsolutePath()).append('\n');
            if (!missingBundled().isEmpty()) sb.append("本 APK 内置了数据，点「开始游戏」会先释放到应用目录。");
            detail.setText(sb.toString());
            progress.setText("");
            return;
        }
        status.setText("已找到游戏数据");
        status.setTextColor(0xFF7CD98B);
        sb.append("数据目录：").append(dir.getAbsolutePath()).append('\n');
        File bgm = new File(dir, "thbgm.dat");
        sb.append("thbgm.dat：").append(bgm.isFile() ? "有（可播放 BGM）" : "缺失（游戏可运行但无声）").append('\n');
        File font = new File(dir, "msgothic.ttc");
        sb.append("msgothic.ttc：").append(font.isFile() ? "有" : "无（使用系统 CJK 字体）").append('\n');
        sb.append("存档目录：").append(saveDir().getAbsolutePath());
        detail.setText(sb.toString());
        if (missingBundled().isEmpty()) progress.setText("");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
