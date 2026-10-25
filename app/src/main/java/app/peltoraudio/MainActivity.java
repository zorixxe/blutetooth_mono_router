package app.peltoraudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        am = getSystemService(AudioManager.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = new TextView(this);
        title.setText("Peltor Audio");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, dp(24));
        root.addView(status);

        toggle = new Button(this);
        toggle.setTextSize(24);
        toggle.setMinHeight(dp(96));
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setPadding(0, dp(16), 0, 0);
        Button down = new Button(this);
        down.setText("Volume −");
        down.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_LOWER));
        Button up = new Button(this);
        up.setText("Volume +");
        up.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_RAISE));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        volRow.addView(down, half);
        volRow.addView(up, new LinearLayout.LayoutParams(half));
        root.addView(volRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button battery = new Button(this);
        battery.setText("Keep running in background");
        battery.setOnClickListener(v -> askBatteryExemption());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(16);
        root.addView(battery, bp);

        TextView hint = new TextView(this);
        hint.setText("1. Turn on the headset's Bluetooth (metal lever).\n"
                + "2. Tap START, then play Spotify.\n"
                + "Sound is mono, phone-call quality.");
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);

        setContentView(root);
        askPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void onToggle() {
        Intent svc = new Intent(this, RouterService.class);
        if (RouterService.running) {
            stopService(svc);
        } else {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Allow Bluetooth access first", Toast.LENGTH_LONG).show();
                askPermissions();
                return;
            }
            startForegroundService(svc);
        }
        handler.postDelayed(this::refresh, 300);
    }

    private void refresh() {
        status.setText(RouterService.statusText);
        toggle.setText(RouterService.running ? "STOP" : "START");
    }

    private void adjustVolume(int direction) {
        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI);
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    private void askBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
