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
import android.os.Handler;
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

    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor outputDescriptor;
    private Uri videoUri;
    private File oldAndroidFile;
    private boolean paused;
    private boolean recordingStarted;
    private boolean stopping;
    private long startedAt;
    private long elapsedBeforePause;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (recordingStarted && !stopping) {
                stopAndSave(true);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAndSave(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseRecording();
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeRecording();
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (recordingStarted || recorder != null) return START_STICKY;
            startAsForeground("REC 00:00");
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startRecording(resultCode, resultData);
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void startAsForeground(String text) {
        Notification notification = buildRecordNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION |
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startRecording(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Projection impossible");
            projection.registerCallback(projectionCallback, new Handler(getMainLooper()));

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);

            int width = makeEven(metrics.widthPixels);
            int height = makeEven(metrics.heightPixels);
            int density = metrics.densityDpi;
            String name = "CamLive_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date()) + ".mp4";

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoSize(width, height);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(48_000);
            recorder.setAudioEncodingBitRate(160_000);
            prepareMp4Output(name);
            recorder.prepare();

            virtualDisplay = projection.createVirtualDisplay(
                    "CamLiveScreen",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(),
                    null,
                    null
            );

            recorder.start();
            paused = false;
            stopping = false;
            recordingStarted = true;
            startedAt = System.currentTimeMillis();
            elapsedBeforePause = 0L;
            setRecordState(true, false);
            updateNotification("REC 00:00");
        } catch (Exception e) {
            setRecordState(false, false);
            cleanupFailedRecording();
            stopSelf();
        }
    }

    private void prepareMp4Output(String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CamLive");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) throw new IllegalStateException("MP4 impossible");
            outputDescriptor = getContentResolver().openFileDescriptor(videoUri, "w");
            if (outputDescriptor == null) throw new IllegalStateException("MP4 impossible");
            recorder.setOutputFile(outputDescriptor.getFileDescriptor());
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CamLive");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Dossier impossible");
            oldAndroidFile = new File(dir, name);
            videoUri = Uri.fromFile(oldAndroidFile);
            recorder.setOutputFile(oldAndroidFile.getAbsolutePath());
        }
    }

    private void pauseRecording() {
        try {
            if (recorder != null && recordingStarted && !paused && !stopping) {
                recorder.pause();
                elapsedBeforePause = getElapsed();
                paused = true;
                setRecordState(true, true);
                updateNotification("PAUSE " + formatTime(elapsedBeforePause));
            }
        } catch (Exception ignored) {}
    }

    private void resumeRecording() {
        try {
            if (recorder != null && recordingStarted && paused && !stopping) {
                recorder.resume();
                startedAt = System.currentTimeMillis();
                paused = false;
                setRecordState(true, false);
                updateNotification("REC " + formatTime(getElapsed()));
            }
        } catch (Exception ignored) {}
    }

    private synchronized void stopAndSave(boolean visibleResult) {
        if (stopping) return;
        stopping = true;
        boolean saved = false;

        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}
        virtualDisplay = null;

        try {
            if (recorder != null && recordingStarted) {
                recorder.stop();
                saved = true;
            }
        } catch (Exception ignored) {}

        try {
            if (recorder != null) {
                recorder.reset();
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;
        recordingStarted = false;

        try {
            if (projection != null) {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            }
        } catch (Exception ignored) {}
        projection = null;

        paused = false;
        startedAt = 0L;
        elapsedBeforePause = 0L;
        setRecordState(false, false);
        finishMp4(saved);
        stopForeground(true);

        if (visibleResult && saved && videoUri != null) {
            saveLastVideo(videoUri);
            notifyVideoReady(videoUri);
            openVideoNow(videoUri);
        }
    }

    private void cleanupFailedRecording() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}
        virtualDisplay = null;

        try {
            if (recorder != null) recorder.release();
        } catch (Exception ignored) {}
        recorder = null;
        recordingStarted = false;

        try {
            if (projection != null) {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            }
        } catch (Exception ignored) {}
        projection = null;

        finishMp4(false);
        stopForeground(true);
    }

    private void finishMp4(boolean saved) {
        try {
            if (outputDescriptor != null) outputDescriptor.close();
        } catch (Exception ignored) {}
        outputDescriptor = null;

        try {
            if (Build.VERSION.SDK_INT >= 29 && videoUri != null) {
                if (saved) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(videoUri, values, null, null);
                } else {
                    getContentResolver().delete(videoUri, null, null);
                    videoUri = null;
                }
            } else if (oldAndroidFile != null) {
                if (saved) {
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(oldAndroidFile)));
                } else if (oldAndroidFile.exists()) {
                    oldAndroidFile.delete();
                }
            }
        } catch (Exception ignored) {}
    }

    private void openVideoNow(Uri uri) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, "video/mp4");
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(open);
        } catch (Exception ignored) {}
    }

    private void notifyVideoReady(Uri uri) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(uri, "video/mp4");
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                21,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/mp4");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent sharePendingIntent = PendingIntent.getActivity(
                this,
                22,
                Intent.createChooser(shareIntent, "Partager le MP4"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("MP4 Cam Live sauvegarde")
                .setContentText("Galerie > Movies > CamLive")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Ouvrir", openPendingIntent)
                .addAction(android.R.drawable.ic_menu_share, "Partager", sharePendingIntent)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(FINISHED_NOTIFICATION_ID, notification);
    }

    private Notification buildRecordNotification(String text) {
        Intent pauseIntent = new Intent(this, ScreenRecordService.class);
        pauseIntent.setAction(ACTION_PAUSE);
        PendingIntent pausePending = PendingIntent.getService(
                this,
                12,
                pauseIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent resumeIntent = new Intent(this, ScreenRecordService.class);
        resumeIntent.setAction(ACTION_RESUME);
        PendingIntent resumePending = PendingIntent.getService(
                this,
                13,
                resumeIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, ScreenRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                11,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder
                .setContentTitle("Cam Live")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true);

        if (paused) {
            builder.addAction(android.R.drawable.ic_media_play, "Reprendre", resumePending);
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending);
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop MP4", stopPending);
        return builder.build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildRecordNotification(text));
    }

    private long getElapsed() {
        if (startedAt == 0L) return elapsedBeforePause;
        if (paused) return elapsedBeforePause;
        return elapsedBeforePause + (System.currentTimeMillis() - startedAt);
    }

    private String formatTime(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.FRANCE, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void setRecordState(boolean active, boolean isPaused) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RECORDING_ACTIVE, active)
                .putBoolean(KEY_RECORDING_PAUSED, isPaused)
                .putLong(KEY_RECORDING_STARTED_AT, active ? startedAt : 0L)
                .putLong(KEY_RECORDING_ELAPSED_MS, active ? getElapsed() : 0L)
                .apply();
    }

    private void saveLastVideo(Uri uri) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_VIDEO_URI, uri.toString()).apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cam Live Recording",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private int makeEven(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    @Override
    public void onDestroy() {
        if (recorder != null || recordingStarted) {
            stopAndSave(false);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
