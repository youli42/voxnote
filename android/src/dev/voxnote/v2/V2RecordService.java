package dev.voxnote.v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;

import dev.voxnote.HomeActivity;
import dev.voxnote.Recorder;
import dev.voxnote.Storage;

/**
 * 录音前台服务：录音不再依赖 Activity 存活。
 *
 * - 开始录音：START → 前台通知（麦克风类型）→ MediaRecorder 写临时文件；
 * - 停止：STOP → 发布音频 + 写本地库 + 触发 v2 同步 → 广播 RECORD_SAVED；
 * - Activity 只做权限、UI 和展示状态；锁屏/切后台/Activity 重建都不中断录音。
 */
public class V2RecordService extends Service {

    public static final String ACTION_START = "dev.voxnote.v2.RECORD_START";
    public static final String ACTION_STOP = "dev.voxnote.v2.RECORD_STOP";
    public static final String ACTION_SAVED = "dev.voxnote.v2.RECORD_SAVED";
    public static final String EXTRA_STAMP = "stamp";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_MS = "ms";

    private static final String CHANNEL = "recording";
    private static final int NOTIF_ID = 1001;
    private static volatile V2RecordService instance;

    private final Recorder recorder = new Recorder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private File tmp;
    private String stamp;
    private String title;
    private long startedAt;

    public interface Listener {
        void onSaved(String recordId, String stamp, long ms);
    }

    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean isRecording() {
        V2RecordService s = instance;
        return s != null && s.recorder.active();
    }

    public static int ms() {
        V2RecordService s = instance;
        return s == null ? 0 : (int) Math.max(0, System.currentTimeMillis() - s.startedAt);
    }

    public static float level() {
        V2RecordService s = instance;
        return s == null ? 0f : s.recorder.level();
    }

    public static String currentStamp() {
        V2RecordService s = instance;
        return s == null ? "" : s.stamp;
    }

    public static void start(Context ctx, String stamp, String title) {
        Intent i = new Intent(ctx, V2RecordService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_STAMP, stamp);
        i.putExtra(EXTRA_TITLE, title);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, V2RecordService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "录音", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_START.equals(action)) {
            String s = intent.getStringExtra(EXTRA_STAMP);
            String t = intent.getStringExtra(EXTRA_TITLE);
            begin(s, t);
        } else if (ACTION_STOP.equals(action)) {
            stopAndSave();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(String s, String t) {
        if (recorder.active()) return;
        stamp = s == null ? Recorder.stamp() : s;
        title = t == null ? "" : t;
        File dir = new File(getExternalFilesDir(null), "tmp");
        if (!dir.exists()) dir.mkdirs();
        tmp = new File(dir, stamp + "_memo.m4a");
        if (!recorder.start(tmp)) {
            notifyText("录音失败：无法写入文件");
            stopSelf();
            return;
        }
        startedAt = System.currentTimeMillis();
        startForegroundCompat(buildNotification());
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, HomeActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("voxnote 正在录音")
                .setContentText("点按返回；锁屏也会继续录")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(startedAt)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopAndSave() {
        if (!recorder.active()) return;
        final File f = recorder.stop();
        final long durMs = Math.max(1000, System.currentTimeMillis() - startedAt);
        final String s = stamp;
        final String t = title;
        stamp = null;
        title = null;
        if (f == null || !f.exists() || f.length() < 1024) {
            notifyText("录音失败：没有写进文件");
            broadcast("", "", "", 0);
            return;
        }
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String id = "";
                try {
                    String sha = V2Store.sha256(f);
                    long bytes = f.length();
                    String loc = new Storage(app).publish(f, f.getName(), "audio/mp4");
                    id = V2Store.get(app).recordCaptured(s, t, loc, bytes, durMs, sha);
                    if (V2Sync.hasServer(app)) V2Sync.syncAsync(app);
                } catch (Throwable e) {
                    id = "";
                }
                final String rid = id;
                main.post(new Runnable() {
                    public void run() {
                        notifyText(rid.length() == 0 ? "录音保存失败" : "已保存");
                        broadcast(rid, s, t, durMs);
                        Listener l = listener;
                        if (l != null) l.onSaved(rid, s, durMs);
                    }
                });
            }
        }, "v2-record-save").start();
    }

    private void broadcast(String id, String stamp, String title, long ms) {
        Intent i = new Intent(ACTION_SAVED);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_STAMP, stamp == null ? "" : stamp);
        i.putExtra(EXTRA_TITLE, title == null ? "" : title);
        i.putExtra(EXTRA_MS, ms);
        sendBroadcast(i);
    }

    private void notifyText(String text) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("voxnote")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID + 1, b.build());
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onDestroy() {
        if (recorder.active()) stopAndSave();
        instance = null;
        super.onDestroy();
    }
}

