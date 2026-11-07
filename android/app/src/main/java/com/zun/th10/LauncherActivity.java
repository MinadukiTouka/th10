package com.zun.th10;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * Data check + start screen.
 *
 * Mountain of Faith needs th10.dat (the game archive, 27 MB) and thbgm.dat (403 MB)
 * from a legally owned 1.00a copy. The APK ships neither. The same search order is
 * repeated in the native host (th10-port/android-host/AndroidMain.cpp); keep the two
 * lists in step:
 *
 *   1. /storage/emulated/0/Download/th10        (needs "All files access")
 *   2. /sdcard/Android/data/com.zun.th10/files/th10   (adb push, no permission)
 *   3. /data/data/com.zun.th10/files/th10       (private, import target later)
 */
public class LauncherActivity extends Activity {
    private TextView status;
    private TextView detail;
    private Button grant;

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
        detail.setPadding(0, 0, 0, dp(18));
        root.addView(detail);

        Button play = button("开始游戏");
        play.setOnClickListener(v -> {
            if (dataDir() == null) {
                refresh();
                return;
            }
            startActivity(new Intent(this, GameActivity.class));
        });
        root.addView(play);

        grant = button("授予“所有文件访问”权限");
        grant.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        });
        root.addView(grant);

        Button again = button("重新检查");
        again.setOnClickListener(v -> refresh());
        root.addView(again);

        TextView help = new TextView(this);
        help.setText("把 th10.dat 与 thbgm.dat 放进 Download/th10/ 就能玩。\n"
                + "也可以用 adb 推到 /sdcard/Android/data/com.zun.th10/files/th10/，这样不需要任何权限。\n"
                + "存档（scoreth10.dat、th10.cfg、replay/）写在 /sdcard/Android/data/com.zun.th10/files/th10-save/。");
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

    /** First folder holding th10.dat, in the documented order, or null. */
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

    private void refresh() {
        File dir = dataDir();
        grant.setVisibility(allFiles() ? TextView.GONE : TextView.VISIBLE);
        if (dir == null) {
            status.setText("找不到 th10.dat");
            status.setTextColor(0xFFFF9E64);
            StringBuilder sb = new StringBuilder("已查找：\n");
            for (File d : candidateDirs()) sb.append("  ").append(d.getAbsolutePath()).append('\n');
            detail.setText(sb.toString());
            return;
        }
        status.setText("已找到游戏数据");
        status.setTextColor(0xFF7CD98B);
        StringBuilder sb = new StringBuilder();
        sb.append("数据目录：").append(dir.getAbsolutePath()).append('\n');
        File bgm = new File(dir, "thbgm.dat");
        sb.append("thbgm.dat：").append(bgm.isFile() ? "有（可播放 BGM）" : "缺失（游戏可运行但无声）").append('\n');
        File font = new File(dir, "msgothic.ttc");
        sb.append("msgothic.ttc：").append(font.isFile() ? "有" : "无（使用系统 CJK 字体）").append('\n');
        sb.append("存档目录：").append(saveDir().getAbsolutePath());
        detail.setText(sb.toString());
    }

    private File saveDir() {
        File ext = getExternalFilesDir(null);
        return new File(ext != null ? ext : getFilesDir(), "th10-save");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
