package ernest.airc.mediafixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.TextureView;
import android.widget.TextView;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;

/** Only a disposable integration-test player; never part of the target runtime distribution. */
public final class PlaybackActivity extends Activity {
    private MediaPlayer player;
    private ExoPlayer videoPlayer;
    private MediaSession session;
    private boolean prepared;
    private boolean customAvailable;
    private static final String CUSTOM="ernest.airc.fixture.action.once";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable update=new Runnable(){public void run(){report();handler.postDelayed(this,250);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);initializePlayer();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);stopFixture();initializePlayer();}
    private void initializePlayer(){
        customAvailable=true;
        android.util.Log.i("AIRCFixture","initialize type="+getIntent().getType());
        session=new MediaSession(this,"AIRC-owned-media-fixture");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback(){
            public void onPlay(){if(videoPlayer!=null){videoPlayer.play();report();}else if(prepared){player.start();report();}}
            public void onPause(){if(videoPlayer!=null){videoPlayer.pause();report();}else if(prepared){player.pause();report();}}
            public void onSeekTo(long position){if(videoPlayer!=null){videoPlayer.seekTo(position);report();}else if(prepared){player.seekTo((int)position);report();}}
            public void onCustomAction(String action,Bundle extras){
                android.util.Log.i("AIRCFixture","custom callback action="+action+" extrasEmpty="+(extras==null||extras.isEmpty()));
                if(customAvailable && CUSTOM.equals(action) && (extras==null||extras.isEmpty())){
                    customAvailable=false;
                    session.setMetadata(new MediaMetadata.Builder(session.getController().getMetadata()).putString(MediaMetadata.METADATA_KEY_TITLE,"AIRC custom action applied").build());
                    report();
                }
            }
            public void onStop(){finish();}
        });
        session.setActive(true);
        if(getIntent().getType()!=null && getIntent().getType().startsWith("video/")){
            videoPlayer=new ExoPlayer.Builder(this).build();
            TextureView surface=new TextureView(this);setContentView(surface);videoPlayer.setVideoTextureView(surface);
            videoPlayer.addListener(new Player.Listener(){
                public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){prepared=true;handler.removeCallbacks(update);handler.post(update);}}
                public void onPlayerError(PlaybackException error){session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR,0,0).setErrorMessage("fixture video error "+error.errorCode).build());}
            });
            videoPlayer.setMediaItem(MediaItem.fromUri(getIntent().getData()));videoPlayer.prepare();videoPlayer.play();return;
        }
        player=new MediaPlayer();
        player.setOnPreparedListener(p->{android.util.Log.i("AIRCFixture","prepared duration="+p.getDuration());prepared=true;session.setMetadata(new MediaMetadata.Builder().putLong(MediaMetadata.METADATA_KEY_DURATION,p.getDuration()).putString(MediaMetadata.METADATA_KEY_TITLE,"AIRC owned fixture").build());p.start();handler.post(update);});
        player.setOnCompletionListener(p->report());
        player.setOnErrorListener((p,what,extra)->{android.util.Log.e("AIRCFixture","error="+what+":"+extra);session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR,0,0).setErrorMessage("fixture decode failed "+what+":"+extra).build());return true;});
        try {player.setDataSource(this,getIntent().getData());}
        catch(Exception e){session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR,0,0).setErrorMessage("fixture source unavailable").build());return;}
        TextView text=new TextView(this);text.setText("AIRC silent audio integration fixture");setContentView(text);player.prepareAsync();
    }
    private PlaybackState.Builder playbackBuilder(){
        PlaybackState.Builder builder=new PlaybackState.Builder();
        if(customAvailable)builder.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM,"Fixture one-shot action",android.R.drawable.ic_media_play).build());
        return builder;
    }
    private void report(){if(prepared && videoPlayer!=null){session.setMetadata(new MediaMetadata.Builder().putLong(MediaMetadata.METADATA_KEY_DURATION,videoPlayer.getDuration()).putString(MediaMetadata.METADATA_KEY_TITLE,"AIRC owned video fixture").build());session.setPlaybackState(playbackBuilder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP).setState(videoPlayer.getPlaybackState()==Player.STATE_BUFFERING?PlaybackState.STATE_BUFFERING:(videoPlayer.isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED),videoPlayer.getCurrentPosition(),videoPlayer.isPlaying()?1:0).build());return;}if(prepared && player!=null)session.setPlaybackState(playbackBuilder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP).setState(player.isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,player.getCurrentPosition(),player.isPlaying()?1:0).build());}
    private void stopFixture(){handler.removeCallbacksAndMessages(null);prepared=false;if(videoPlayer!=null){videoPlayer.release();videoPlayer=null;}if(player!=null){player.release();player=null;}if(session!=null){session.release();session=null;}}
    @Override public void onDestroy(){stopFixture();super.onDestroy();}
}
