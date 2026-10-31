package com.wushisan.algertv.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import com.wushisan.algertv.App;
import com.wushisan.algertv.R;
import com.wushisan.algertv.data.Song;
import com.wushisan.algertv.ui.PlayerActivity;

public class PlaybackService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.wushisan.algertv.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.wushisan.algertv.NEXT";
    public static final String ACTION_PREV = "com.wushisan.algertv.PREV";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "playback";

    private MediaSessionCompat mediaSession;

    private final PlayerController.Listener controllerListener = new PlayerController.Listener() {
        @Override
        public void onSongChanged(Song song, int index) {
            refresh();
        }

        @Override
        public void onPlaybackStateChanged(boolean playing) {
            refresh();
        }

        @Override
        public void onPlaybackError(String message) {
            Log.w(App.TAG, "service sees error: " + message);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            ComponentName mediaButton = new ComponentName(this, MediaButtonReceiver.class);
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(mediaButton);
            PendingIntent mediaButtonPi = PendingIntent.getBroadcast(this, 0,
                    mediaButtonIntent, PendingIntent.FLAG_IMMUTABLE);
            mediaSession = new MediaSessionCompat(this, "AlgerTV",
                    mediaButton, mediaButtonPi);
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    PlayerController pc = PlayerController.get();
                    if (pc != null) pc.resume();
                }

                @Override
                public void onPause() {
                    PlayerController pc = PlayerController.get();
                    if (pc != null) pc.pause();
                }

                @Override
                public void onSkipToNext() {
                    PlayerController pc = PlayerController.get();
                    if (pc != null) pc.playNext();
                }

                @Override
                public void onSkipToPrevious() {
                    PlayerController pc = PlayerController.get();
                    if (pc != null) pc.playPrev();
                }

                @Override
                public void onSeekTo(long pos) {
                    PlayerController pc = PlayerController.get();
                    if (pc != null) pc.seekTo((int) pos);
                }
            });
            mediaSession.setActive(true);
            PlayerController pc = PlayerController.get();
            if (pc != null) pc.addListener(controllerListener);
            Log.i(App.TAG, "PlaybackService onCreate");
        } catch (Exception e) {
            Log.w(App.TAG, "PlaybackService onCreate failed: " + e.getMessage());
            if (mediaSession != null) {
                mediaSession.release();
                mediaSession = null;
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PlayerController pc = PlayerController.get();
        if (intent != null && intent.getAction() != null && pc != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    pc.togglePlayPause();
                    break;
                case ACTION_NEXT:
                    pc.playNext();
                    break;
                case ACTION_PREV:
                    pc.playPrev();
                    break;
                default:
                    break;
            }
        }
        if (pc == null || pc.currentSong() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        refresh();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        PlayerController pc = PlayerController.get();
        if (pc != null) pc.removeListener(controllerListener);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "音乐播放", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("正在播放的歌曲与控制");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void refresh() {
        PlayerController pc = PlayerController.get();
        if (pc == null || mediaSession == null) return;
        Song song = pc.currentSong();
        if (song == null) {
            stopForeground(false);
            return;
        }

        try {
            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artistsText)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.albumName)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, pc.getDuration())
                    .build();
            mediaSession.setMetadata(metadata);

            long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO;
            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(pc.isPlaying() ? PlaybackStateCompat.STATE_PLAYING
                                    : PlaybackStateCompat.STATE_PAUSED,
                            pc.getPosition(), 1.0f)
                    .build();
            mediaSession.setPlaybackState(state);

            Intent openIntent = new Intent(this, PlayerActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(song.name)
                    .setContentText(song.artistsText)
                    .setContentIntent(contentIntent)
                    .setOngoing(pc.isPlaying())
                    .addAction(android.R.drawable.ic_media_previous, "上一首",
                            serviceIntent(ACTION_PREV, 1))
                    .addAction(pc.isPlaying() ? android.R.drawable.ic_media_pause
                                    : android.R.drawable.ic_media_play,
                            pc.isPlaying() ? "暂停" : "播放", serviceIntent(ACTION_PLAY_PAUSE, 2))
                    .addAction(android.R.drawable.ic_media_next, "下一首",
                            serviceIntent(ACTION_NEXT, 3))
                    .build();

            startForegroundCompat(notification);
        } catch (Exception e) {
            Log.w(App.TAG, "PlaybackService refresh failed: " + e.getMessage());
            try {
                stopForeground(false);
            } catch (Exception ignored) {
            }
        }
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.w(App.TAG, "startForeground typed failed: " + e.getMessage());
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Exception e2) {
                Log.w(App.TAG, "startForeground plain failed: " + e2.getMessage());
            }
        }
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
