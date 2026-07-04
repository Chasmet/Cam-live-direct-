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
        root.setPadding(36, 36, 36, 36);
        root.setBackgroundColor(0xff101018);

        TextView title = new TextView(this);
        title.setText("Cam Live");
        title.setTextColor(0xffffffff);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Mode camera partout : active la camera flottante, puis ouvre Google Earth ou une autre application. Pour enregistrer, appuie d'abord sur Demarrer.");
        subtitle.setTextColor(0xffd8d8df);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 20, 0, 24);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        Button startButton = makeButton("1. Demarrer video + camera", 0xffff1744);
        Button cameraButton = makeButton("2. Activer camera partout", 0xff2f80ed);
        Button earthButton = makeButton("3. Ouvrir Google Earth", 0xff242936);
        Button browserButton = makeButton("Ouvrir Google Earth Web", 0xff242936);
        Button stopButton = makeButton("Arreter camera et video", 0xff333846);

        root.addView(startButton);
        root.addView(cameraButton);
        root.addView(earthButton);
        root.addView(browserButton);
        root.addView(stopButton);

        statusText = new TextView(this);
        statusText.setText("Pret. Appuie sur Activer camera partout pour voir la camera au-dessus des autres applis.");
        statusText.setTextColor(0xffffffff);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 24, 0, 0);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        startButton.setOnClickListener(v -> startRecordingFlow());
        cameraButton.setOnClickListener(v -> startFloatingCameraOnly());
        earthButton.setOnClickListener(v -> openGoogleEarthAppWithCamera());
        browserButton.setOnClickListener(v -> openGoogleEarthWebWithCamera());
        stopButton.setOnClickListener(v -> stopRecording());
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        button.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 106);
        params.setMargins(0, 10, 0, 10);
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

    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            statusText.setText("Active Afficher par-dessus les autres applis, puis reviens dans Cam Live.");
            return false;
        }
        return true;
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNeededPermissions() {
        return hasCameraPermission() && hasMicPermission() && checkOverlayPermission();
    }

    private void startFloatingCameraOnly() {
        if (!hasCameraPermission()) {
            askBasicPermissions();
            statusText.setText("Autorise la camera, puis recommence.");
            return;
        }
        if (!checkOverlayPermission()) return;
        startForegroundServiceCompat(new Intent(this, CameraOverlayService.class));
        statusText.setText("Camera flottante active. Tu peux ouvrir n'importe quelle application.");
        Toast.makeText(this, "Camera active partout", Toast.LENGTH_SHORT).show();
    }

    private void startRecordingFlow() {
        if (!hasNeededPermissions()) {
            askBasicPermissions();
            statusText.setText("Permissions necessaires : camera, micro et affichage par-dessus les applis.");
            return;
        }
        startFloatingCameraOnly();
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
                statusText.setText("Enregistrement lance. Ouvre Google Earth ou une autre application.");
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
        statusText.setText("Arret demande.");
    }

    private void openGoogleEarthAppWithCamera() {
        startFloatingCameraOnly();
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.earth");
        if (intent == null) {
            openGoogleEarthWebWithCamera();
            return;
        }
        startActivity(intent);
    }

    private void openGoogleEarthWebWithCamera() {
        startFloatingCameraOnly();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://earth.google.com/web/"));
        startActivity(intent);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }
}
