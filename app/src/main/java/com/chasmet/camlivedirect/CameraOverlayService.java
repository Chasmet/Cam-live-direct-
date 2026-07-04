package com.chasmet.camlivedirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

public class CameraOverlayService extends Service {
    public static final String ACTION_STOP = "com.chasmet.camlivedirect.STOP_CAMERA";
    private static final String CHANNEL_ID = "cam_live_camera";
    private static final int NOTIFICATION_ID = 20;

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private TextureView textureView;
    private WindowManager.LayoutParams layoutParams;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private TextView pauseButton;
    private boolean recordPaused = false;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private int cameraWidth = 360;
    private int cameraHeight = 500;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground();
        startCameraThread();
        buildOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("Cam Live")
                .setContentText("Camera flottante active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void buildOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new FrameLayout(this);
        overlayView.setBackgroundColor(Color.BLACK);
        overlayView.setPadding(4, 4, 4, 4);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        overlayView.addView(content, new FrameLayout.LayoutParams(-1, -1));

        textureView = new TextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        content.addView(textureView, previewParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xdd111111);
        controls.setPadding(4, 4, 4, 4);
        content.addView(controls, new LinearLayout.LayoutParams(-1, 72));

        pauseButton = makeControlButton("Pause");
        TextView minusButton = makeControlButton("-");
        TextView plusButton = makeControlButton("+");
        TextView stopButton = makeControlButton("Stop");

        controls.addView(pauseButton);
        controls.addView(minusButton);
        controls.addView(plusButton);
        controls.addView(stopButton);

        TextView label = new TextView(this);
        label.setText("CAM LIVE");
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setBackgroundColor(0x99000000);
        label.setPadding(10, 6, 10, 6);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        overlayView.addView(label, labelParams);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        layoutParams = new WindowManager.LayoutParams(
                cameraWidth,
                cameraHeight,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutParams.x = 24;
        layoutParams.y = 80;

        setDragTouch(overlayView);
        setDragTouch(textureView);
        setDragTouch(label);

        pauseButton.setOnClickListener(v -> toggleRecordPause());
        minusButton.setOnClickListener(v -> resizeCamera(-60));
        plusButton.setOnClickListener(v -> resizeCamera(60));
        stopButton.setOnClickListener(v -> {
            sendRecordAction(ScreenRecordService.ACTION_STOP);
            stopSelf();
        });

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openFrontCamera(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                closeCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        windowManager.addView(overlayView, layoutParams);
    }

    private TextView makeControlButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0xff333846);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(4, 0, 4, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void setDragTouch(android.view.View view) {
        view.setOnTouchListener((target, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = layoutParams.x;
                    startY = layoutParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = startX + (int) (event.getRawX() - downX);
                    layoutParams.y = startY + (int) (event.getRawY() - downY);
                    windowManager.updateViewLayout(overlayView, layoutParams);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void toggleRecordPause() {
        if (recordPaused) {
            sendRecordAction(ScreenRecordService.ACTION_RESUME);
            pauseButton.setText("Pause");
            recordPaused = false;
        } else {
            sendRecordAction(ScreenRecordService.ACTION_PAUSE);
            pauseButton.setText("Reprendre");
            recordPaused = true;
        }
    }

    private void resizeCamera(int delta) {
        cameraWidth = Math.max(220, Math.min(720, cameraWidth + delta));
        cameraHeight = Math.max(300, Math.min(960, cameraHeight + (int) (delta * 1.35f)));
        layoutParams.width = cameraWidth;
        layoutParams.height = cameraHeight;
        windowManager.updateViewLayout(overlayView, layoutParams);
    }

    private void sendRecordAction(String action) {
        Intent intent = new Intent(this, ScreenRecordService.class);
        intent.setAction(action);
        startService(intent);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CamLiveCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void openFrontCamera(SurfaceTexture surfaceTexture) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findFrontCamera(manager);
            if (cameraId == null) return;

            surfaceTexture.setDefaultBufferSize(720, 960);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview(surfaceTexture);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                }
            }, cameraHandler);
        } catch (SecurityException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String findFrontCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        try {
            Surface surface = new Surface(surfaceTexture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) captureSession.close();
        } catch (Exception ignored) {}
        captureSession = null;
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Exception ignored) {}
        cameraDevice = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Cam Live Camera", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        closeCamera();
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
