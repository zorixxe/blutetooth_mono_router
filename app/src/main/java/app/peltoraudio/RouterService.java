package app.peltoraudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the phone in "communication" audio mode with the Bluetooth call
 * channel (SCO) open, so media apps like Spotify play through a calls-only
 * headset.
 *
 * Newer Android resets communication mode after a few seconds if the app
 * that set it isn't playing anything itself, which made audio cut out and
 * come back. So the service plays continuous silence as a voice-call stream
 * to keep ownership of the mode. Listeners re-apply routing instantly when
 * the mode or device changes, and a watchdog catches anything they miss
 * (e.g. after a real phone call ends).
 */
public class RouterService extends Service {
    public static final String ACTION_STOP = "app.peltoraudio.STOP";
    private static final String CHANNEL = "routing";
    private static final int NOTIF_ID = 1;

    static volatile boolean running = false;
    static volatile String statusText = "Off";

    private AudioManager am;
    private AudioTrack keepAlive;
    private Object modeListener;
    private Object deviceListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            ensureRouting();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        am = getSystemService(AudioManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Audio routing", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = buildNotification("Starting…");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            statusText = "Could not start: allow Bluetooth permission";
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        startKeepAlive();
        registerListeners();
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        return START_STICKY;
    }

    /** Loops one second of silence on the voice-call stream. */
    private void startKeepAlive() {
        if (keepAlive != null) return;
        try {
            int rate = 16000;
            int frames = rate; // 1 second
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            t.write(new short[frames], 0, frames);
            t.setLoopPoints(0, frames, -1);
            t.play();
            keepAlive = t;
        } catch (Exception ignored) {
            // Routing still works without it, just with possible dropouts.
        }
    }

    private void stopKeepAlive() {
        if (keepAlive == null) return;
        try {
            keepAlive.stop();
        } catch (Exception ignored) { }
        keepAlive.release();
        keepAlive = null;
    }

    private void registerListeners() {
        if (Build.VERSION.SDK_INT < 31 || modeListener != null) return;
        AudioManager.OnModeChangedListener ml = mode -> handler.post(this::ensureRouting);
        AudioManager.OnCommunicationDeviceChangedListener dl =
                device -> handler.post(this::ensureRouting);
        am.addOnModeChangedListener(getMainExecutor(), ml);
        am.addOnCommunicationDeviceChangedListener(getMainExecutor(), dl);
        modeListener = ml;
        deviceListener = dl;
    }

    private void unregisterListeners() {
        if (Build.VERSION.SDK_INT < 31 || modeListener == null) return;
        am.removeOnModeChangedListener((AudioManager.OnModeChangedListener) modeListener);
        am.removeOnCommunicationDeviceChangedListener(
                (AudioManager.OnCommunicationDeviceChangedListener) deviceListener);
        modeListener = null;
        deviceListener = null;
    }

    private void ensureRouting() {
        try {
            int mode = am.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) {
                setStatus("Paused during phone call");
                return;
            }
            if (mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo cur = am.getCommunicationDevice();
                if (cur != null && cur.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    setStatus("Playing through " + cur.getProductName());
                    return;
                }
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            && am.setCommunicationDevice(d)) {
                        setStatus("Playing through " + d.getProductName());
                        return;
                    }
                }
                setStatus("Waiting for headset (is its Bluetooth on?)");
            } else {
                if (!am.isBluetoothScoOn()) {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                }
                setStatus(am.isBluetoothScoOn()
                        ? "Playing through headset"
                        : "Waiting for headset (is its Bluetooth on?)");
            }
        } catch (SecurityException e) {
            setStatus("Missing Bluetooth permission");
        }
    }

    private void setStatus(String s) {
        if (s.equals(statusText)) return;
        statusText = s;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(s));
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RouterService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Stop", stop).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Peltor Audio")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(watchdog);
        unregisterListeners();
        stopKeepAlive();
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) { }
        running = false;
        statusText = "Off";
        super.onDestroy();
    }
}
