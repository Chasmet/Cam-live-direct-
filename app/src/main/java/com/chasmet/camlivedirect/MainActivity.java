package com.chasmet.camlivedirect;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    private TextView permissionText;
    private boolean launchCameraAfterOverlayPermission = false;
    private boolean launchRecordAfterOverlayPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildInterface();
        askBasicPermissions();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (overlayAllowed() && launchCameraAfterOverlayPermission) {
            launchCameraAfterOverlayPermission = false;
            startFloatingCameraNow();
            if (launchRecordAfterOverlayPermission) {
                launchRecordAfterOverlayPermission = false;
                startRecordingFlow();
            }
        }
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(34, 42, 34, 34);
        root.setBackgroundColor(0xff0f1118);

        TextView title = new TextView(this);
        title.setText("Cam Live");
        title.setTextColor(0xffffffff);
        title.setTextSize(38);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Filme ton ecran avec ta camera au-dessus des autres applications.");
        subtitle.setTextColor(0xffcfd3dc);
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 22);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        permissionText = new TextView(this);
        permissionText.setTextColor(0xffffffff);
        permissionText.setTextSize(15);
        permissionText.setGravity(Gravity.CENTER);
        permissionText.setPadding(18, 14, 18, 14);
        permissionText.setBackgroundColor(0xff242936);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, 0, 0, 18);
        root.addView(permissionText, infoParams);

        Button recordButton = makeButton("DEMARRER VIDEO + CAMERA", 0xffff1744, 20, 124);
        Button cameraButton = makeButton("AFFICHER CAMERA FLOTTANTE", 0xff2f80ed, 18, 112);
        Button earthButton = makeButton("OUVRIR GOOGLE EARTH", 0xff252b37, 17, 100);
        Button webButton = makeButton("GOOGLE EARTH WEB", 0xff252b37, 17, 100);
        Button stopButton = makeButton("STOPPER TOUT", 0xff3a3f4d, 17, 100);

        root.addView(recordButton);
        root.addView(cameraButton);
        root.addView(earthButton);
        root.addView(webButton);
        root.addView(stopButton);

        TextView help = new TextView(this);
        help.setText("Dans la camera flottante : glisse pour deplacer, + et - pour la taille, Pause et Stop pour controler la video.");
        help.setTextColor(0xffcfd3dc);
        help.setTextSize(15);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, 22, 0, 10);
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextColor(0xffffffff);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 8, 0, 0);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        recordButton.setOnClickListener(v -> startRecordingFlow());
        cameraButton.setOnClickListener(v -> startFloatingCameraFlow(false));
        earthButton.setOnClickListener(v -> openGoogleEarthApp());
        webButton.setOnClickListener(v -> openGoogleEarthWeb());
        stopButton.setOnClickListener(v -> stopEverything());
    }

    private Button makeButton(String text, int color, int textSize, int height) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(textSize);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        button.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.setMargins(0, 8, 0, 8);
        button.setLayoutParams(params);
        return button;
    }

    private void refreshStatus() {
        String overlay = overlayAllowed() ? "OK" : "A activer";
        String camera = hasCameraPermission() ? "OK" : "A autoriser";
        String mic = hasMicPermission() ? "OK" : "A autoriser";
        if (permissionText != null) {
            permissionText.setText("Camera : " + camera + "   Micro : " + mic + "   Flottant : " + overlay);
        }
        if (statusText != null) {
            if (!overlayAllowed()) {
                statusText.setText("Etape obligatoire : autorise Cam Live dans Afficher par-dessus les autres applications.");
            } else {
                statusText.setText("Pret. Tu peux afficher la camera flottante ou demarrer une video.");
            }
        }
    }

    private void askBasicPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, REQUEST_PERMISSIONS);
        }
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean overlayAllowed() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission(boolean recordAfter) {
        launchCameraAfterOverlayPermission = true;
        launchRecordAfterOverlayPermission = recordAfter;
        new AlertDialog.Builder(this)
                .setTitle("Autorisation obligatoire")
                .setMessage("Sur l'ecran qui va s'ouvrir :\n\n1. cherche Cam Live\n2. appuie dessus\n3. active Afficher par-dessus les autres applis\n4. reviens ici\n\nLa camera flottante se lancera ensuite.")
                .setPositiveButton("Ouvrir le reglage", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private boolean prepareCameraAndOverlay(boolean recordAfter) {
        if (!hasCameraPermission() || !hasMicPermission()) {
            askBasicPermissions();
            Toast.makeText(this, "Autorise camera et micro", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!overlayAllowed()) {
            requestOverlayPermission(recordAfter);
            return false;
        }
        return true;
    }

    private void startFloatingCameraFlow(boolean recordAfter) {
        if (!prepareCameraAndOverlay(recordAfter)) return;
        startFloatingCameraNow();
    }

    private void startFloatingCameraNow() {
        startForegroundServiceCompat(new Intent(this, CameraOverlayService.class));
        statusText.setText("Camera flottante affichee. Ouvre maintenant l'application que tu veux filmer.");
        Toast.makeText(this, "Camera flottante active", Toast.LENGTH_SHORT).show();
    }

    private void startRecordingFlow() {
        if (!prepareCameraAndOverlay(true)) return;
        startFloatingCameraNow();
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
                statusText.setText("Enregistrement lance. Tu peux ouvrir Google Earth, Maps, YouTube ou TikTok.");
            } else {
                statusText.setText("Capture annulee.");
            }
        }
    }

    private void stopEverything() {
        Intent recordStop = new Intent(this, ScreenRecordService.class);
        recordStop.setAction(ScreenRecordService.ACTION_STOP);
        startService(recordStop);
        Intent cameraStop = new Intent(this, CameraOverlayService.class);
        cameraStop.setAction(CameraOverlayService.ACTION_STOP);
        startService(cameraStop);
        statusText.setText("Camera et video arretees.");
    }

    private void openGoogleEarthApp() {
        if (!prepareCameraAndOverlay(false)) return;
        startFloatingCameraNow();
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.earth");
        if (intent == null) {
            openGoogleEarthWeb();
            return;
        }
        startActivity(intent);
    }

    private void openGoogleEarthWeb() {
        if (!prepareCameraAndOverlay(false)) return;
        startFloatingCameraNow();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://earth.google.com/web/"));
        startActivity(intent);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }
}
