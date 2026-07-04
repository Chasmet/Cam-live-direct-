package com.chasmet.camlivedirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {
    public static final String ACTION_START = "com.chasmet.camlivedirect.START_RECORD";
    public static final String ACTION_STOP = "com.chasmet.camlivedirect.STOP_RECORD";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "cam_live_record";
    private static final int NOTIFICATION_ID = 10;

    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private String lastFilePath;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("Enregistrement en cours");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startRecording(resultCode, resultData);
        }
        return START_STICKY;
    }

    private void startRecording(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int density = metrics.densityDpi;

            File moviesDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CamLive");
            if (!moviesDir.exists()) moviesDir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date());
            File outputFile = new File(moviesDir, "cam_live_" + stamp + ".mp4");
            lastFilePath = outputFile.getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(lastFilePath);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncodingBitRate(12_000_000);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.prepare();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "CamLiveScreen",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(),
                    null,
                    null
            );
            mediaRecorder.start();
        } catch (Exception e) {
            e.printStackTrace();
            stopRecording();
            stopSelf();
        }
    }

    private void stopRecording() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}
        virtualDisplay = null;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
            }
        } catch (Exception ignored) {}
        mediaRecorder = null;

        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Exception ignored) {}
        mediaProjection = null;

        stopForeground(true);
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, ScreenRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 11, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Cam Live")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .addAction(android.R.drawable.ic_media_pause, "Arreter", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Cam Live Recording", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
