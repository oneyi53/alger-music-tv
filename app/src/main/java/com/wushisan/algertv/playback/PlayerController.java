package com.wushisan.algertv.playback;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.wushisan.algertv.App;
import com.wushisan.algertv.data.MusicRepository;
import com.wushisan.algertv.data.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerController {

    public interface Listener {
        void onSongChanged(Song song, int index);

        void onPlaybackStateChanged(boolean playing);

        void onPlaybackError(String message);
    }

    private enum PlaybackState {
        IDLE,
        PREPARING,
        PREPARED,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR
    }

    private static PlayerController sInstance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AudioManager audioManager;

    private MediaPlayer mediaPlayer;
    private List<Song> queue = new ArrayList<>();
    private int index = -1;
    private long loadToken = 0;

    private PlaybackState state = PlaybackState.IDLE;
    private PlaybackState lastReportedState = null;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS && state == PlaybackState.PLAYING) {
                        pause();
                    }
                }
            };

    public static synchronized void init(Context ctx) {
        if (sInstance == null) sInstance = new PlayerController(ctx.getApplicationContext());
    }

    public static PlayerController get() {
        return sInstance;
    }

    private PlayerController(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean hasQueue() {
        return index >= 0 && index < queue.size();
    }

    public Song currentSong() {
        return hasQueue() ? queue.get(index) : null;
    }

    public List<Song> getQueue() {
        return queue;
    }

    public int getIndex() {
        return index;
    }

    public boolean isPlaying() {
        return state == PlaybackState.PLAYING;
    }

    public boolean isPreparing() {
        return state == PlaybackState.PREPARING;
    }

    public int getPosition() {
        if (state != PlaybackState.PREPARED
                && state != PlaybackState.PLAYING
                && state != PlaybackState.PAUSED) {
            return 0;
        }
        if (mediaPlayer == null) return 0;
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                int dur = mediaPlayer.getDuration();
                if (dur > 0) return dur;
            } catch (Exception ignored) {
            }
        }
        Song s = currentSong();
        return s == null ? 0 : (int) s.durationMs;
    }

    public void playQueue(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        queue = new ArrayList<>(songs);
        index = Math.max(0, Math.min(startIndex, queue.size() - 1));
        ensureService();
        startCurrent();
    }

    public void togglePlayPause() {
        if (!hasQueue()) return;
        if (isPlaying()) pause();
        else resume();
    }

    public void resume() {
        if (!hasQueue()) return;
        if (state == PlaybackState.PREPARING) return;

        if (mediaPlayer == null
                || state == PlaybackState.IDLE
                || state == PlaybackState.STOPPED
                || state == PlaybackState.ERROR) {
            startCurrent();
            return;
        }

        if (state == PlaybackState.PREPARED || state == PlaybackState.PAUSED) {
            requestFocus();
            try {
                mediaPlayer.start();
                updateState(PlaybackState.PLAYING);
            } catch (Exception e) {
                startCurrent();
            }
        }
    }

    public void pause() {
        if (mediaPlayer == null) {
            updateState(PlaybackState.PAUSED);
            return;
        }

        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (Exception ignored) {
        }

        if (state != PlaybackState.ERROR) {
            updateState(PlaybackState.PAUSED);
        }
    }

    public void playNext() {
        if (!hasQueue()) return;

        if (index + 1 < queue.size()) {
            index++;
            startCurrent();
        } else {
            stopPlayback();
        }
    }

    public void playPrev() {
        if (!hasQueue()) return;

        if (getPosition() > 3000) {
            seekTo(0);
            return;
        }

        if (index > 0) {
            index--;
            startCurrent();
        } else {
            seekTo(0);
        }
    }

    public void seekBy(int deltaMs) {
        if (mediaPlayer == null || (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED)) return;
        try {
            int pos = mediaPlayer.getCurrentPosition() + deltaMs;
            int dur = mediaPlayer.getDuration();
            if (pos < 0) pos = 0;
            if (dur > 0 && pos > dur) pos = dur - 500;
            mediaPlayer.seekTo(pos);
        } catch (Exception ignored) {
        }
    }

    public void seekTo(int posMs) {
        if (mediaPlayer == null || (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED)) return;
        try {
            mediaPlayer.seekTo(posMs);
        } catch (Exception ignored) {
        }
    }

    public void stopPlayback() {
        loadToken++;

        try {
            if (mediaPlayer != null) {
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {
        }

        updateState(PlaybackState.STOPPED);
    }

    private void ensureService() {
        try {
            Intent i = new Intent(appContext, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                androidx.core.content.ContextCompat.startForegroundService(appContext, i);
            } else {
                appContext.startService(i);
            }
        } catch (Exception e) {
            Log.w(App.TAG, "startService failed: " + e.getMessage());
        }
    }

    private void ensurePlayer() {
        if (mediaPlayer != null) return;

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);

        if (Build.VERSION.SDK_INT >= 21) {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (loadToken == 0) return;

                updateState(PlaybackState.PREPARED);
                Song s = currentSong();
                Log.i(App.TAG, "PLAYER_PREPARED id=" + (s == null ? "?" : s.id)
                        + " dur=" + mp.getDuration());
                requestFocus();

                try {
                    mp.start();
                    updateState(PlaybackState.PLAYING);
                    Log.i(App.TAG, "PLAYER_STARTED id=" + (s == null ? "?" : s.id));
                    notifySongChanged();
                } catch (Exception e) {
                    updateState(PlaybackState.ERROR);
                    notifyError("播放启动失败");
                    advanceToNextOrStop();
                }
            }
        });

        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.i(App.TAG, "PLAYER_COMPLETED idx=" + index);
                if (index + 1 < queue.size()) {
                    index++;
                    startCurrent();
                } else {
                    updateState(PlaybackState.PAUSED);
                }
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (extra == -38) {
                    Log.w(App.TAG, "PLAYER_ERROR_IGNORED -38 idx=" + index + " state=" + state);
                    return true;
                }

                Log.e(App.TAG, "PLAYER_ERROR what=" + what + " extra=" + extra + " idx=" + index);
                updateState(PlaybackState.ERROR);
                notifyError("播放失败，自动跳到下一首");
                advanceToNextOrStop();
                return true;
            }
        });
    }

    private void startCurrent() {
        final Song song = currentSong();
        if (song == null) return;

        ensurePlayer();
        final long token = ++loadToken;

        updateState(PlaybackState.PREPARING);
        Log.i(App.TAG, "PLAYER_LOAD id=" + song.id + " name=" + song.name);

        MusicRepository.get().resolvePlayUrl(song, new MusicRepository.Result<String>() {
            @Override
            public void onSuccess(final String url) {
                if (token != loadToken || mediaPlayer == null) return;

                try {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(url);
                    mediaPlayer.prepareAsync();
                    Log.i(App.TAG, "PLAYER_PREPARING id=" + song.id);
                } catch (Exception e) {
                    if (token != loadToken) return;
                    updateState(PlaybackState.ERROR);
                    Log.e(App.TAG, "PLAYER_SETDATA_FAIL: " + e.getMessage());
                    notifyError("无法播放该歌曲");
                    advanceToNextOrStop();
                }
            }

            @Override
            public void onError(String message) {
                if (token != loadToken) return;
                updateState(PlaybackState.ERROR);
                Log.e(App.TAG, "PLAYER_URL_FAIL id=" + song.id + ": " + message);
                notifyError("无播放源：" + song.name);
                advanceToNextOrStop();
            }
        });
    }

    private void advanceToNextOrStop() {
        if (index + 1 < queue.size()) {
            index++;
            startCurrent();
        } else {
            stopPlayback();
        }
    }

    private void updateState(PlaybackState newState) {
        if (state == newState) {
            notifyState();
            return;
        }
        state = newState;
        notifyState();
    }

    private void requestFocus() {
        try {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        } catch (Exception ignored) {
        }
    }

    private void notifyState() {
        final boolean playing = state == PlaybackState.PLAYING;
        if (lastReportedState == state) {
            return;
        }
        lastReportedState = state;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onPlaybackStateChanged(playing);
            }
        });
    }

    private void notifySongChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Song s = currentSong();
                for (Listener l : listeners) l.onSongChanged(s, index);
            }
        });
    }

    private void notifyError(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onPlaybackError(msg);
            }
        });
    }
}
