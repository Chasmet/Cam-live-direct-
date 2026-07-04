package com.chasmet.camlivedirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {
    public static final String ACTION_START = "com.chasmet.camlivedirect.START_RECORD";
    public static final String ACTION_STOP = "com.chasmet.camlivedirect.STOP_RECORD";
    public static final String ACTION_PAUSE = "com.chasmet.camlivedirect.PAUSE_RECORD";
    public static final String ACTION_RESUME = "com.chasmet.camlivedirect.RESUME_RECORD";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public static final String PREFS = "cam_live_prefs";
    public static final String KEY_LAST_VIDEO_URI = "last_video_uri";
    public static final String KEY_RECORDING_ACTIVE = "recording_active";
    public static final String KEY_RECORDING_PAUSED = "recording_paused";
    public static final String KEY_RECORDING_STARTED_AT = "recording_started_at";
    public static final String KEY_RECORDING_ELAPSED_MS = "recording_elapsed_ms";

    private static final String CHANNEL_ID = "cam_live_record";
    private static final int NOTIFICATION_ID = 10;
    private static final int FINISHED_NOTIFICATION_ID = 11;

    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private boolean isPaused = false;
    private long startedAt = 0L;
    private long elapsedBeforePause = 0L;
    private Uri currentVideoUri;
    private ParcelFileDescriptor outputDescriptor;
    private File legacyOutputFile;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            pauseRecording();
            return START_STICKY;
        }

        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            resumeRecording();
            return START_STICKY;
        }

        Notification notification = buildRecordingNotification("REC 00:00");
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

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date());
            String fileName = "CamLive_" + stamp + ".mp4";

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            preparePublicOutput(fileName);
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
            isPaused = false;
            startedAt = System.currentTimeMillis();
            elapsedBeforePause = 0L;
            markRecordingState(true, false);
            updateRecordingNotification("REC " + formatElapsed(getElapsedMs()));
        } catch (Exception e) {
            e.printStackTrace();
            stopRecording(false);
            stopSelf();
        }
    }

    private void preparePublicOutput(String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CamLive");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            currentVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (currentVideoUri == null) throw new IllegalStateException("Impossible de creer le fichier MP4");
            outputDescriptor = getContentResolver().openFileDescriptor(currentVideoUri, "w");
            if (outputDescriptor == null) throw new IllegalStateException("Impossible d'ouvrir le fichier MP4");
            mediaRecorder.setOutputFile(outputDescriptor.getFileDescriptor());
        } else {
            File moviesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CamLive");
            if (!moviesDir.exists()) moviesDir.mkdirs();
            legacyOutputFile = new File(moviesDir, fileName);
            currentVideoUri = Uri.fromFile(legacyOutputFile);
            mediaRecorder.setOutputFile(legacyOutputFile.getAbsolutePath());
        }
    }

    private void pauseRecording() {
        try {
            if (mediaRecorder != null && !isPaused) {
                mediaRecorder.pause();
                elapsedBeforePause = getElapsedMs();
                isPaused = true;
                markRecordingState(true, true);
                updateRecordingNotification("PAUSE " + formatElapsed(elapsedBeforePause));
            }
        } catch (Exception ignored) {}
    }

    private void resumeRecording() {
        try {
            if (mediaRecorder != null && isPaused) {
                mediaRecorder.resume();
                startedAt = System.currentTimeMillis();
                isPaused = false;
                markRecordingState(true, false);
                updateRecordingNotification("REC " + formatElapsed(getElapsedMs()));
            }
        } catch (Exception ignored) {}
    }

    private long getElapsedMs() {
        if (startedAt == 0L) return elapsedBeforePause;
        if (isPaused) return elapsedBeforePause;
        return elapsedBeforePause + (System.currentTimeMillis() - startedAt);
    }

    private String formatElapsed(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return String.format(Locale.FRANCE, "%02d:%02d", minutes, rest);
    }

    private void stopRecording(boolean showFinishedNotification) {
        boolean saved = false;
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}
        virtualDisplay = null;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                saved = true;
                mediaRecorder.reset();
                mediaRecorder.release();
            }
        } catch (Exception ignored) {}
        mediaRecorder = null;

        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Exception ignored) {}
        mediaProjection = null;
        isPaused = false;
        startedAt = 0L;
        elapsedBeforePause = 0L;

        finishPublicOutput(saved);
        markRecordingState(false, false);
        stopForeground(true);

        if (showFinishedNotification && saved && currentVideoUri != null) {
            saveLastVideoUri(currentVideoUri);
            showVideoSavedNotification(currentVideoUri);
        }
    }

    private void finishPublicOutput(boolean saved) {
        try {
            if (outputDescriptor != null) outputDescriptor.close();
        } catch (Exception ignored) {}
        outputDescriptor = null;

        try {
            if (Build.VERSION.SDK_INT >= 29 && currentVideoUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(currentVideoUri, values, null, null);
                if (!saved) {
                    getContentResolver().delete(currentVideoUri, null, null);
                    currentVideoUri = null;
                }
            } else if (legacyOutputFile != null) {
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(legacyOutputFile));
                sendBroadcast(scanIntent);
            }
        } catch (Exception ignored) {}
    }

    private void markRecordingState(boolean active, boolean paused) {
        long elapsed = active ? getElapsedMs() : 0L;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_RECORDING_ACTIVE, active)
                .putBoolean(KEY_RECORDING_PAUSED, paused)
                .putLong(KEY_RECORDING_STARTED_AT, active ? startedAt : 0L)
                .putLong(KEY_RECORDING_ELAPSED_MS, active ? elapsed : 0L)
                .apply();
    }

    private void saveLastVideoUri(Uri uri) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_VIDEO_URI, uri.toString()).apply();
    }

    private void updateRecordingNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildRecordingNotification(text));
    }

    private Notification buildRecordingNotification(String text) {
        Intent stopIntent = new Intent(this, ScreenRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 11, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pauseIntent = new Intent(this, ScreenRecordService.class);
        pauseIntent.setAction(ACTION_PAUSE);
        PendingIntent pausePendingIntent = PendingIntent.getService(this, 12, pauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent resumeIntent = new Intent(this, ScreenRecordService.class);
        resumeIntent.setAction(ACTION_RESUME);
        PendingIntent resumePendingIntent = PendingIntent.getService(this, 13, resumeIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder
                .setContentTitle("Cam Live")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true);
        if (isPaused) {
            builder.addAction(android.R.drawable.ic_media_play, "Reprendre", resumePendingIntent);
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent);
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent);
        return builder.build();
    }

    private void showVideoSavedNotification(Uri uri) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(uri, "video/mp4");
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 21, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/mp4");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent sharePendingIntent = PendingIntent.getActivity(this, 22, Intent.createChooser(shareIntent, "Partager la video MP4"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("MP4 Cam Live pret")
                .setContentText("Enregistre dans Galerie > Movies > CamLive")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Ouvrir", openPendingIntent)
                .addAction(android.R.drawable.ic_menu_share, "Partager", sharePendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(FINISHED_NOTIFICATION_ID, notification);
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
        stopRecording(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
