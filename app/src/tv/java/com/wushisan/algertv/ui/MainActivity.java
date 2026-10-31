package com.wushisan.algertv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.wushisan.algertv.App;
import com.wushisan.algertv.R;
import com.wushisan.algertv.data.Song;
import com.wushisan.algertv.playback.PlayerController;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_container, new MainFragment())
                    .commit();
        }
        maybeSmokeDirectPlay();
    }

    /** Smoke hook: with --es alger_smoke_direct 1, play a direct-URL track
     *  through the full playback pipeline (data source bypassed — the CI
     *  egress IP is rate-limited there) and open the player screen, whose
     *  500 ms ticker is exactly the poller that used to trigger error -38
     *  during prepare. */
    private void maybeSmokeDirectPlay() {
        if (!"1".equals(getIntent().getStringExtra("alger_smoke_direct"))) return;
        PlayerController controller = PlayerController.get();
        if (controller == null) {
            Log.w(App.TAG, "SMOKE_DIRECT_PLAY skipped: PlayerController not initialized");
            return;
        }
        Song s = new Song(
                "direct:https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                "Smoke Direct", "SoundHelix", "", null, null, 0);
        Log.i(App.TAG, "SMOKE_DIRECT_PLAY tv");
        List<Song> q = new ArrayList<>();
        q.add(s);
        controller.playQueue(q, 0);
        startActivity(new Intent(this, PlayerActivity.class));
    }
}
