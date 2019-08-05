package com.reactnativeaudiodemo;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.os.RemoteException;
import android.text.TextUtils;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import android.util.Log;

public class MediaPlayerService extends MediaBrowserServiceCompat  implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener{

    public static final String COMMAND_EXAMPLE = "command_example";

    private static final String TAG = "MyApp";
    private static final int NOTIFICATION_ID = 101;
    public static final String CHANNEL_ID = "com_yajananrao_trackplayer";
    private static final String CHANNEL_NAME = "Track Player";
    private NotificationManager mNotificationManager;
    private NotificationManagerCompat mNotificationManagerCompat;

    private MediaPlayer mMediaPlayer;
    private MediaSessionCompat mMediaSessionCompat;
    private AudioFocusRequest focus;


    private BroadcastReceiver mNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( mMediaPlayer != null && mMediaPlayer.isPlaying() ) {
                mMediaPlayer.pause();
            }
        }
    };

    private MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {

        @Override
        public void onPlay() {
            super.onPlay();
            if( !successfullyRetrievedAudioFocus() ) {
                return;
            }

            mMediaSessionCompat.setActive(true);
            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
            initNoisyReceiver();
            showPlayingNotification();
            mMediaPlayer.start();
        }

        @Override
        public void onStop() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            // Abandon audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.abandonAudioFocusRequest(focus);
            }
            unregisterReceiver(mNoisyReceiver);
            // Stop the service
            stopSelf();
            // Set the session inactive  (and update metadata and state)
            mMediaSessionCompat.setActive(false);
            // stop the player (custom call)
            mMediaPlayer.stop();
            // Take the service out of the foreground
            stopForeground(false);
        }


        @Override
        public void onPause() {
            super.onPause();

            if( mMediaPlayer.isPlaying() ) {
                mMediaPlayer.pause();
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                unregisterReceiver(mNoisyReceiver);
                showPausedNotification();
            }
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            super.onPlayFromSearch(query, extras);
        }

        @Override
        public void onPlayFromUri(Uri uri, Bundle extras) {
            super.onPlayFromUri(uri, extras);
            Log.i(TAG, "onPlayFromUri: song received");
            try {


                try {
                    mMediaPlayer.setDataSource(uri.toString());

                } catch( IllegalStateException e ) {
                    mMediaPlayer.release();
                    initMediaPlayer();
                    mMediaPlayer.setDataSource(uri.toString());
                }

                initMediaSessionMetadata(uri.toString());

            } catch (IOException e) {
                return;
            }

            try {
                mMediaPlayer.prepare();
            } catch (IOException e) {}

            //Work with extras here if you want
        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS);
        }


        @Override
        public void onSeekTo(long pos) {
            super.onSeekTo(pos);
        }

    };



    @Override
    public void onCreate(){
        try {
            super.onCreate();
            initMediaPlayer();
            initMediaSession();
            initNotification();
        } catch (Exception e) {
            //TODO: handle exception
            Log.e(TAG, "onCreate: "+ e.toString());
        }
    }

    private void initNotification() {

        mNotificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID,
                    CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            notificationChannel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            notificationChannel.setShowBadge(true);
            notificationChannel.setSound(null,null);
            notificationChannel.enableVibration(false);
            mNotificationManager.createNotificationChannel(notificationChannel);
        }
    }

    private void initNoisyReceiver() {
        //Handles headphones coming unplugged. cannot be done through a manifest receiver
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mNoisyReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocus(this);
        unregisterReceiver(mNoisyReceiver);
        mMediaSessionCompat.release();
        NotificationManagerCompat.from(this).cancel(1);
    }

    private void initMediaPlayer() {
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setVolume(1.0f, 1.0f);
    }

    private void showPlayingNotification() {
        try {
            NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
            if( builder == null ) {
                return;
            }
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));

            builder.setSmallIcon(R.drawable.ic_play_circle);
            builder.setChannelId(CHANNEL_ID);
            mNotificationManagerCompat = NotificationManagerCompat.from(this);
            startForeground(NOTIFICATION_ID, builder.build());
        }catch (Exception exp){
        }
    }

    private void showPausedNotification() {
        try {
            NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
            if( builder == null ) {
                return;
            }
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
            builder.setSmallIcon(R.drawable.ic_play_circle);
            builder.setChannelId(CHANNEL_ID);
            mNotificationManagerCompat = NotificationManagerCompat.from(this);
            mNotificationManagerCompat.notify(NOTIFICATION_ID,builder.build());
//            startForeground(NOTIFICATION_ID, builder.build());
            stopForeground(false);
        }catch (Exception exp){
        }
    }


    private void initMediaSession() {
        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MediaButtonReceiver.class);
        mMediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "Tag", mediaButtonReceiver, null);

        mMediaSessionCompat.setCallback(mMediaSessionCallback);
        mMediaSessionCompat.setFlags( MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS );

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mMediaSessionCompat.setMediaButtonReceiver(pendingIntent);

        setSessionToken(mMediaSessionCompat.getSessionToken());
    }

    private void setMediaPlaybackState(int state) {
        PlaybackStateCompat.Builder playbackstateBuilder = new PlaybackStateCompat.Builder();
        if( state == PlaybackStateCompat.STATE_PLAYING ) {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE);
        } else {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY);
        }
        playbackstateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0);
        mMediaSessionCompat.setPlaybackState(playbackstateBuilder.build());
    }

    private void initMediaSessionMetadata(String url) {
        Intent appIntent = new Intent(this, MediaPlayerService.class);

        PendingIntent appPendingIntent = PendingIntent.getActivity(this,000,appIntent,PendingIntent.FLAG_UPDATE_CURRENT);

        Utils utils = new Utils();
        HashMap<String, Object> metaData = utils.extractMetaData(url);
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        //Notification icon in card
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));

        //lock screen icon for pre lollipop
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, (Bitmap) metaData.get("artcover"));
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, (String) metaData.get("title"));
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, (String) metaData.get("albumArtist"));
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1);
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1);
        mMediaSessionCompat.setMetadata(metadataBuilder.build());
        mMediaSessionCompat.setSessionActivity(appPendingIntent);
    }

    private boolean successfullyRetrievedAudioFocus() {
        int result;
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if(Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(this)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(false)
                    .build();

            result = audioManager.requestAudioFocus(focus);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(this,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }




        return result == AudioManager.AUDIOFOCUS_GAIN;
    }


    //Not important for general audio service, required for class
    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if(TextUtils.equals(clientPackageName, getPackageName())) {
            return new BrowserRoot(getString(R.string.app_name), null);
        }
        return null;
    }

    //Not important for general audio service, required for class
    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch( focusChange ) {
            case AudioManager.AUDIOFOCUS_LOSS: {
                if( mMediaPlayer.isPlaying() ) {
                    mMediaPlayer.stop();
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                mMediaPlayer.pause();
                showPausedNotification();
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                if( mMediaPlayer != null ) {
                    mMediaPlayer.setVolume(0.3f, 0.3f);
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_GAIN: {
                if( mMediaPlayer != null ) {
                    if( !mMediaPlayer.isPlaying() ) {

                        mMediaPlayer.prepareAsync();

                        mMediaPlayer.start();
                    }
                    mMediaPlayer.setVolume(1.0f, 1.0f);
                }
                break;
            }
        }
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        if( mMediaPlayer != null ) {
            mMediaPlayer.release();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mMediaSessionCompat, intent);
        return super.onStartCommand(intent, flags, startId);
    }
}
