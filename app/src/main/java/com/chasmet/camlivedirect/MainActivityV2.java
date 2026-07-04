package com.chasmet.camlivedirect;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivityV2 extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private TextView permissionsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        requestBasePermissions();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff07111f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Cam Live");
        title.setTextColor(0xffffffff);
        title.setTextSize(38);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap(0, 0, 0, 8));

        TextView subtitle = new TextView(this);
        subtitle.setText("Filmez votre ecran avec une camera flottante");
        subtitle.setTextColor(0xffd7dce8);
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, fullWrap(0, 0, 0, 14));

        statusText = label(22, 0xff101928);
        statusText.setText("Pret a enregistrer");
        root.addView(statusText, fullWrap(0, 0, 0, 10));

        Button rec = action("DEMARRER L'ENREGISTREMENT", 0xffff1744, 21);
        Button cam = action("CAMERA FLOTTANTE", 0xff006fe6, 20);
        Button stop = action("STOP ET SAUVEGARDER MP4", 0xff981b2b, 19);
        Button open = action("OUVRIR LA DERNIERE VIDEO", 0xff132033, 18);
        Button share = action("PARTAGER LA VIDEO", 0xff006b83, 18);
        Button earth = action("OUVRIR GOOGLE EARTH", 0xff0a7d2e, 18);
        Button settings = action("AUTORISER LE MODE FLOTTANT", 0xff303846, 17);

        root.addView(rec);
        root.addView(cam);
        root.addView(stop);
        root.addView(open);
        root.addView(share);
        root.addView(earth);
        root.addView(settings);

        permissionsText = label(14, 0xff101928);
        root.addView(permissionsText, fullWrap(0, 12, 0, 10));

        TextView help = label(14, 0xff07111f);
        help.setText("Fonctions: capture ecran, camera selfie flottante, micro, pause, reprise, stop, chrono, sauvegarde automatique MP4 et partage rapide.");
        help.setTextColor(0xffb9c4d6);
        root.addView(help, fullWrap(0, 4, 0, 0));

        setContentView(scroll);

        rec.setOnClickListener(v -> startRecording());
        cam.setOnClickListener(v -> showFloatingCamera());
        stop.setOnClickListener(v -> stopAll());
        open.setOnClickListener(v -> openLastVideo());
        share.setOnClickListener(v -> shareLastVideo());
        earth.setOnClickListener(v -> openEarth());
        settings.setOnClickListener(v -> openOverlaySettings());
    }

    private TextView label(int size, int color) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xffffffff);
        tv.setTextSize(size);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(color);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        return tv;
    }

    private Button action(String text, int color, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xffffffff);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setBackgroundColor(color);
        b.setMinHeight(dp(72));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setLayoutParams(fullWrap(0, 7, 0, 7));
        return b;
    }

    private LinearLayout.LayoutParams fullWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void requestBasePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, 200);
        }
    }

    private boolean hasCamera() { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasMic() { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasOverlay() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    private boolean readyForCam() {
        if (!hasCamera() || !hasMic()) {
            requestBasePermissions();
            Toast.makeText(this, "Autorise camera et micro", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!hasOverlay()) {
            Toast.makeText(this, "Active Autoriser le mode flottant", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return false;
        }
        return true;
    }

    private void refreshStatus() {
        if (permissionsText == null || statusText == null) return;
        permissionsText.setText("Camera: " + ok(hasCamera()) + "   Micro: " + ok(hasMic()) + "   Flottant: " + ok(hasOverlay()));
        SharedPreferences prefs = getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE);
        boolean active = prefs.getBoolean(ScreenRecordService.KEY_RECORDING_ACTIVE, false);
        boolean paused = prefs.getBoolean(ScreenRecordService.KEY_RECORDING_PAUSED, false);
        long elapsed = prefs.getLong(ScreenRecordService.KEY_RECORDING_ELAPSED_MS, 0L);
        long started = prefs.getLong(ScreenRecordService.KEY_RECORDING_STARTED_AT, 0L);
        if (active && !paused && started > 0) elapsed += System.currentTimeMillis() - started;
        if (active) {
            statusText.setText((paused ? "PAUSE " : "REC ") + format(elapsed));
            statusText.setBackgroundColor(paused ? 0xff8a5a00 : 0xffaa0000);
        } else {
            statusText.setText(hasOverlay() ? "Pret a enregistrer" : "Mode flottant a autoriser");
            statusText.setBackgroundColor(hasOverlay() ? 0xff10351e : 0xff3a2f00);
        }
    }

    private String ok(boolean value) { return value ? "OK" : "A faire"; }

    private String format(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.FRANCE, "%02d:%02d", s / 60, s % 60);
    }

    private void showFloatingCamera() {
        if (!readyForCam()) return;
        startServiceCompat(new Intent(this, CamOverlayV2.class));
        Toast.makeText(this, "Camera flottante active", Toast.LENGTH_SHORT).show();
    }

    private void startRecording() {
        if (!readyForCam()) return;
        startServiceCompat(new Intent(this, CamOverlayV2.class));
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent intent = new Intent(this, ScreenRecordService.class);
            intent.setAction(ScreenRecordService.ACTION_START);
            intent.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data);
            startServiceCompat(intent);
            Toast.makeText(this, "Enregistrement lance", Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void stopAll() {
        Intent stopRecord = new Intent(this, ScreenRecordService.class);
        stopRecord.setAction(ScreenRecordService.ACTION_STOP);
        startService(stopRecord);
        Intent stopCam = new Intent(this, CamOverlayV2.class);
        stopCam.setAction(CamOverlayV2.ACTION_STOP);
        startService(stopCam);
        Toast.makeText(this, "MP4 sauvegarde dans Galerie > Movies > CamLive", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private Uri lastVideoUri() {
        String value = getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE).getString(ScreenRecordService.KEY_LAST_VIDEO_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    private void openLastVideo() {
        Uri uri = lastVideoUri();
        if (uri == null) {
            startActivity(new Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void shareLastVideo() {
        Uri uri = lastVideoUri();
        if (uri == null) {
            Toast.makeText(this, "Aucune video a partager", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("video/mp4");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Partager la video Cam Live"));
    }

    private void openEarth() {
        showFloatingCamera();
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.earth");
        if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://earth.google.com/web/"));
        startActivity(intent);
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
