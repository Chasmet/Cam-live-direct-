package com.chasmet.camlivedirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

public class CamOverlayV2 extends Service {
    public static final String ACTION_STOP = "com.chasmet.camlivedirect.STOP_CAMERA_V2";
    private static final String CHANNEL_ID = "cam_live_camera_v2";
    private static final int NOTIFICATION_ID = 30;

    private WindowManager windowManager;
    private FrameLayout overlay;
    private TextureView preview;
    private TextView badge;
    private TextView pauseButton;
    private WindowManager.LayoutParams params;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler uiHandler;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private int widthPx;
    private int heightPx;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshBadge();
            if (uiHandler != null) uiHandler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundSafe();
        uiHandler = new Handler(getMainLooper());
        startCameraThread();
        buildOverlay();
        uiHandler.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startForegroundSafe() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setContentTitle("Cam Live").setContentText("Camera flottante active").setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void buildOverlay() {
        widthPx = dp(210);
        heightPx = dp(285);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(column, new FrameLayout.LayoutParams(-1, -1));

        preview = new TextureView(this);
        column.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xee101018);
        controls.setPadding(dp(3), dp(3), dp(3), dp(3));
        column.addView(controls, new LinearLayout.LayoutParams(-1, dp(46)));

        pauseButton = control("Pause");
        TextView minus = control("-");
        TextView plus = control("+");
        TextView stop = control("Stop");
        controls.addView(pauseButton);
        controls.addView(minus);
        controls.addView(plus);
        controls.addView(stop);

        badge = new TextView(this);
        badge.setText("CAM PRETE");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(0xcc000000);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5));
        overlay.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(widthPx, heightPx, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(12);
        params.y = dp(72);

        setDrag(preview);
        setDrag(badge);
        pauseButton.setOnClickListener(v -> togglePause());
        minus.setOnClickListener(v -> resize(-dp(35)));
        plus.setOnClickListener(v -> resize(dp(35)));
        stop.setOnClickListener(v -> stopAction());

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openCamera(surface); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        windowManager.addView(overlay, params);
    }

    private TextView control(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(0xff333846);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        view.setLayoutParams(lp);
        return view;
    }

    private void setDrag(android.view.View view) {
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                params.x = startX + (int) (event.getRawX() - downX);
                params.y = startY + (int) (event.getRawY() - downY);
                windowManager.updateViewLayout(overlay, params);
                return true;
            }
            return true;
        });
    }

    private void refreshBadge() {
        SharedPreferences p = getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE);
        boolean active = p.getBoolean(ScreenRecordService.KEY_RECORDING_ACTIVE, false);
        boolean paused = p.getBoolean(ScreenRecordService.KEY_RECORDING_PAUSED, false);
        long elapsed = p.getLong(ScreenRecordService.KEY_RECORDING_ELAPSED_MS, 0L);
        long started = p.getLong(ScreenRecordService.KEY_RECORDING_STARTED_AT, 0L);
        if (active && !paused && started > 0) elapsed += System.currentTimeMillis() - started;
        if (active) {
            badge.setText((paused ? "PAUSE " : "REC ") + time(elapsed));
            badge.setBackgroundColor(paused ? 0xccff9800 : 0xccd50000);
            pauseButton.setText(paused ? "Reprendre" : "Pause");
        } else {
            badge.setText("CAM PRETE");
            badge.setBackgroundColor(0xcc000000);
            pauseButton.setText("Pause");
        }
    }

    private String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.FRANCE, "%02d:%02d", s / 60, s % 60);
    }

    private boolean recordingActive() {
        return getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE).getBoolean(ScreenRecordService.KEY_RECORDING_ACTIVE, false);
    }

    private void togglePause() {
        if (!recordingActive()) return;
        SharedPreferences p = getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE);
        boolean paused = p.getBoolean(ScreenRecordService.KEY_RECORDING_PAUSED, false);
        Intent intent = new Intent(this, ScreenRecordService.class);
        intent.setAction(paused ? ScreenRecordService.ACTION_RESUME : ScreenRecordService.ACTION_PAUSE);
        startService(intent);
    }

    private void stopAction() {
        if (recordingActive()) {
            Intent stopRecord = new Intent(this, ScreenRecordService.class);
            stopRecord.setAction(ScreenRecordService.ACTION_STOP);
            startService(stopRecord);
        }
        stopSelf();
    }

    private void resize(int delta) {
        widthPx = Math.max(dp(155), Math.min(dp(340), widthPx + delta));
        heightPx = Math.max(dp(210), Math.min(dp(455), heightPx + (int) (delta * 1.35f)));
        params.width = widthPx;
        params.height = heightPx;
        windowManager.updateViewLayout(overlay, params);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CamLiveV2Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void openCamera(SurfaceTexture texture) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = frontCameraId(manager);
            if (id == null) return;
            texture.setDefaultBufferSize(720, 960);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) { cameraDevice = camera; startPreview(texture); }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);
        } catch (SecurityException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String frontCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    private void startPreview(SurfaceTexture texture) {
        try {
            Surface surface = new Surface(texture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    try { session.setRepeatingRequest(builder.build(), null, cameraHandler); } catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {}
            }, cameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        session = null;
        cameraDevice = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Cam Live Camera V2", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (uiHandler != null) uiHandler.removeCallbacks(ticker);
        closeCamera();
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        if (cameraThread != null) cameraThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
