package com.chasmet.camlivedirect;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;
    private MediaProjectionManager projectionManager;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildInterface();
        askBasicPermissions();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(42, 42, 42, 42);
        root.setBackgroundColor(0xff101018);

        TextView title = new TextView(this);
        title.setText("Cam Live");
        title.setTextColor(0xffffffff);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Enregistre ton ecran, ta voix et ta camera flottante. Lance l'enregistrement, puis ouvre Google Earth, Maps, YouTube, TikTok ou une autre application.");
        subtitle.setTextColor(0xffd8d8df);
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 20, 0, 28);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        Button startButton = makeButton("Demarrer l'enregistrement", 0xffff1744);
        Button stopButton = makeButton("Arreter", 0xff333846);
        Button overlayButton = makeButton("Autoriser la camera flottante", 0xff2f80ed);
        Button openEarthButton = makeButton("Ouvrir Google Earth", 0xff242936);

        root.addView(startButton);
        root.addView(stopButton);
        root.addView(overlayButton);
        root.addView(openEarthButton);

        statusText = new TextView(this);
        statusText.setText("Pret. Autorise la camera flottante avant le premier test.");
        statusText.setTextColor(0xffffffff);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 26, 0, 0);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        startButton.setOnClickListener(v -> startRecordingFlow());
        stopButton.setOnClickListener(v -> stopRecording());
        overlayButton.setOnClickListener(v -> openOverlaySettingsIfNeeded());
        openEarthButton.setOnClickListener(v -> openGoogleEarth());
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(18);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        button.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 118);
        params.setMargins(0, 12, 0, 12);
        button.setLayoutParams(params);
        return button;
    }

    private void askBasicPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, REQUEST_PERMISSIONS);
        }
    }

    private void openOverlaySettingsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            statusText.setText("Active l'autorisation : afficher par-dessus les autres applis.");
        } else {
            Toast.makeText(this, "Camera flottante autorisee", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasNeededPermissions() {
        boolean cameraOk = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean micOk = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        return cameraOk && micOk && overlayOk;
    }

    private void startRecordingFlow() {
        if (!hasNeededPermissions()) {
            askBasicPermissions();
            openOverlaySettingsIfNeeded();
            statusText.setText("Permissions necessaires : camera, micro et affichage par-dessus les applis.");
            return;
        }
        startForegroundServiceCompat(new Intent(this, CameraOverlayService.class));
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, ScreenRecordService.class);
                serviceIntent.setAction(ScreenRecordService.ACTION_START);
                serviceIntent.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data);
                startForegroundServiceCompat(serviceIntent);
                statusText.setText("Enregistrement lance. Tu peux quitter Cam Live et ouvrir une autre application.");
            } else {
                statusText.setText("Capture annulee.");
            }
        }
    }

    private void stopRecording() {
        Intent recordStop = new Intent(this, ScreenRecordService.class);
        recordStop.setAction(ScreenRecordService.ACTION_STOP);
        startService(recordStop);
        Intent cameraStop = new Intent(this, CameraOverlayService.class);
        cameraStop.setAction(CameraOverlayService.ACTION_STOP);
        startService(cameraStop);
        statusText.setText("Arret demande. La video est dans le dossier Movies de Cam Live.");
    }

    private void openGoogleEarth() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.earth");
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://earth.google.com/web/"));
        }
        startActivity(intent);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }
}
