package com.example.casa.playmusic.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.example.casa.playmusic.activity.Audio;
import com.example.casa.playmusic.activity.MainActivity;
import com.example.casa.playmusic.activity.StorageUtil;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by Mateus on 27/01/2017.
 */

public class MediaPlayerService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener,
        MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener,

        AudioManager.OnAudioFocusChangeListener {

    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private String mediaFile;  //caminho para o arquivo de audio
    private int resumePosition;//Usado para pause/resume do MediaPlayer
    private final IBinder iBinder = new LocalBinder();// Binder given to clients
    private boolean ongoingCall = false;
    private PhoneStateListener phoneStateListener;
    private TelephonyManager telephonyManager;
    private ArrayList<Audio> audioList;
    private int audioIndex = -1;
    private Audio activeAudio;

    public static final String ACTION_PLAY= "com.example.casa.playmusic.ACTION_PLAY";
    public static final String ACTION_PAUSE= "com.example.casa.playmusic.ACTION_PAUSE";
    public static final String ACTION_PREVIOUS= "com.example.casa.playmusic.ACTION_PREVIOUS";
    public static final String ACTION_NEXT= "com.example.casa.playmusic.ACTION_NEXT";
    public static final String ACTION_STOP= "com.example.casa.playmusic.ACTION_STOP";

    private MediaSessionManager mediaSessionManager;
    private MediaSessionCompat mediaSession;
    private MediaControllerCompat.TransportControls transportControls;

    private static final int NOTIFICATION_ID = 101;

    @Override
    public IBinder onBind(Intent intent) {
        return iBinder;
    }
    @Override
    public void onCreate(){
        super,onCreate();
        // Perform one-time setup procedures

        // Manage incoming phone calls during playback.
        // Pause MediaPlayer on incoming call,
        // Resume on hangup.
        callStateListener();
        //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver();
        //Listen for new Audio to play -- BroadcastReceiver
        register_playNewAudio();

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mediaPlayer != null) {
                stopMedia();
                mediaPlayer.release();
            }
            removeAudioFocus();
            //Disable the PhoneStateListener
            if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }

            removeNotification();

            //unregister BroadcastReceivers
            unregisterReceiver(becomingNoisyReceiver);
            unregisterReceiver(playNewAudio);

            //clear cached playlist
            new StorageUtil(getApplicationContext()).clearCachedAudioPlaylist();
        }

    }

    }
    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        //Invoked indicating buffering status of
        //a media resource being streamed over the network.
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        //chamado para comunicar algumas infos
        return false;
    }


    @Override
    public void onCompletion(MediaPlayer mp) {
        //Invoked when playback of a media source has completed.
        stopMedia();
        //stop the service
        stopSelf();
    }

    //Handle errors
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        //Invoked when there has been an error during an asynchronous operation
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                Log.d("MediaPlayer Error", "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN " + extra);
                break;
        }
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        //Invoked when the media source is ready for playback.
        playMedia();
    }


    @Override
    public void onSeekComplete(MediaPlayer mp) {
        //Invoked indicating the completion of a seek operation.
    }

    @Override
    public void onAudioFocusChange(int focusState) {
        //Invoked when the audio focus of the system is updated.
        switch (focusState) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                if (mediaPlayer == null) initMediaPlayer();
                else if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                mediaPlayer.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mediaPlayer.isPlaying()) mediaPlayer.setVolume(0.1f, 0.1f);
                break;
        }

    }

    private boolean requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            //Focus gained
            return true;
        }
        //Could not gain focus
        return false;
    }

    private boolean removeAudioFocus() {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                audioManager.abandonAudioFocus(this);
    }

    public class LocalBinder extends Binder {
        public MediaPlayerService getService() {
            return MediaPlayerService.this;
        }
    }
//********* quando o audio n é mais o principal *********

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        //Set up MediaPlayer event listeners
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnBufferingUpdateListener(this);
        mediaPlayer.setOnSeekCompleteListener(this);
        mediaPlayer.setOnInfoListener(this);
        //Reset so that the MediaPlayer is not pointing to another data source
        mediaPlayer.reset();

        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        try {
            // Set the data source to the mediaFile location
            mediaPlayer.setDataSource(mediaFile);
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
        }
        mediaPlayer.prepareAsync();
    }



    private void playMedia() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    private void stopMedia() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
    }

    private void pauseMedia() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            resumePosition = mediaPlayer.getCurrentPosition();
        }
    }

    private void resumeMedia() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.seekTo(resumePosition);
            mediaPlayer.start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            //An audio file is passed to the service through putExtra();
            mediaFile = intent.getExtras().getString("media");
        } catch (NullPointerException e) {
            stopSelf();
        }

        //Request audio focus
        if (requestAudioFocus() == false) {
            //Could not gain focus
            stopSelf();
        }

        if (mediaFile != null && mediaFile != "")
            initMediaPlayer();

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            stopMedia();
            mediaPlayer.release();
        }
        removeAudioFocus();
    }

    //Becoming noisy
    private BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //pause audio on ACTION_AUDIO_BECOMING_NOISY
            pauseMedia();
            buildNotification(PlaybackStatus.PAUSED);
        }
    };

    private void registerBecomingNoisyReceiver() {
        //register after getting audio focus
        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(becomingNoisyReceiver, intentFilter);
    }

    //Manipular chamadas recebidas
    private void callStateListener() {
        // Get the telephony manager
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        //Starting listening for PhoneState changes
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                switch (state) {
                    //if at least one call exists or the phone is ringing
                    //pause the MediaPlayer
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                    case TelephonyManager.CALL_STATE_RINGING:
                        if (mediaPlayer != null) {
                            pauseMedia();
                            ongoingCall = true;
                        }
                        break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        // Phone idle. Start playing.
                        if (mediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false;
                                resumeMedia();
                            }
                        }
                        break;
                }
            }
        };
        // Register the listener with the telephony manager
        // Listen for changes to the device call state.
        telephonyManager.listen(phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE);
    }

    private BroadcastReceiver playNewAudio = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            //Get the new media index form SharedPreferences
            audioIndex = new StorageUtil(getApplicationContext()).loadAudioIndex();
            if (audioIndex != -1 && audioIndex < audioList.size()) {
                //index is in a valid range
                activeAudio = audioList.get(audioIndex);
            } else {
                stopSelf();
            }

            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new Audio
            stopMedia();
            mediaPlayer.reset();
            initMediaPlayer();
            updateMetaData();
            buildNotification(PlaybackStatus.PLAYING);
        }
    };

    private void register_playNewAudio() {
        //Register playNewMedia receiver
        IntentFilter filter = new IntentFilter(MainActivity.Broadcast_PLAY_NEW_AUDIO);
        registerReceiver(playNewAudio, filter);
    }

        private void initMediaSession() throws RemoteException {
            if (mediaSessionManager != null) return; //mediaSessionManager exists

            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            // Create a new MediaSession
            mediaSession = new MediaSessionCompat(getApplicationContext(), "AudioPlayer");
            //Get MediaSessions transport controls
            transportControls = mediaSession.getController().getTransportControls();
            //set MediaSession -> ready to receive media commands
            mediaSession.setActive(true);
            //indicate that the MediaSession handles transport control commands
            // through its MediaSessionCompat.Callback.
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

            //Set mediaSession's MetaData
            updateMetaData();

            // Attach Callback to receive MediaSession updates
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                // Implement callbacks
                @Override
                public void onPlay() {
                    super.onPlay();
                    resumeMedia();
                    buildNotification(PlaybackStatus.PLAYING);
                }

                @Override
                public void onPause() {
                    super.onPause();
                    pauseMedia();
                    buildNotification(PlaybackStatus.PAUSED);
                }

                @Override
                public void onSkipToNext() {
                    super.onSkipToNext();
                    skipToNext();
                    updateMetaData();
                    buildNotification(PlaybackStatus.PLAYING);
                }

                @Override
                public void onSkipToPrevious() {
                    super.onSkipToPrevious();
                    skipToPrevious();
                    updateMetaData();
                    buildNotification(PlaybackStatus.PLAYING);
                }

                @Override
                public void onStop() {
                    super.onStop();
                    removeNotification();
                    //Stop the service
                    stopSelf();
                }

                @Override
                public void onSeekTo(long position) {
                    super.onSeekTo(position);
                }
            });
        }


        private void skipToNext() {

            if (audioIndex == audioList.size() - 1) {
                //if last in playlist
                audioIndex = 0;
                activeAudio = audioList.get(audioIndex);
            } else {
                //get next in playlist
                activeAudio = audioList.get(++audioIndex);
            }

            //Update stored index
            new StorageUtil(getApplicationContext()).storeAudioIndex(audioIndex);

            stopMedia();
            //reset mediaPlayer
            mediaPlayer.reset();
            initMediaPlayer();
        }

        private void skipToPrevious() {

            if (audioIndex == 0) {
                //if first in playlist
                //set index to the last of audioList
                audioIndex = audioList.size() - 1;
                activeAudio = audioList.get(audioIndex);
            } else {
                //get previous in playlist
                activeAudio = audioList.get(--audioIndex);
            }

            //Update stored index
            new StorageUtil(getApplicationContext()).storeAudioIndex(audioIndex);

            stopMedia();
            //reset mediaPlayer
            mediaPlayer.reset();
            initMediaPlayer();
        }


        private void updateMetaData() {
            Bitmap albumArt = BitmapFactory.decodeResource(getResources(),
                    R.drawable.image); //replace with medias albumArt
            // Update the current metadata
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio.getArtist())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio.getAlbum())
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio.getTitle())
                    .build());
        }



        private void buildNotification(PlaybackStatus playbackStatus) {

            int notificationAction = android.R.drawable.ic_media_pause;//needs to be initialized
            PendingIntent play_pauseAction = null;

            //Build a new notification according to the current state of the MediaPlayer
            if (playbackStatus == PlaybackStatus.PLAYING) {
                notificationAction = android.R.drawable.ic_media_pause;
                //create the pause action
                play_pauseAction = playbackAction(1);
            } else if (playbackStatus == PlaybackStatus.PAUSED) {
                notificationAction = android.R.drawable.ic_media_play;
                //create the play action
                play_pauseAction = playbackAction(0);
            }

            Bitmap largeIcon = BitmapFactory.decodeResource(getResources(),
                    R.drawable.image); //replace with your own image

            // Create a new Notification
            NotificationCompat.Builder notificationBuilder = (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                    .setShowWhen(false)
                    // Set the Notification style
                    .setStyle(new NotificationCompat.MediaStyle()
                            // Attach our MediaSession token
                            .setMediaSession(mediaSession.getSessionToken())
                            // Show our playback controls in the compact notification view.
                            .setShowActionsInCompactView(0, 1, 2))
                    // Set the Notification color
                    .setColor(getResources().getColor(R.color.colorPrimary))
                    // Set the large and small icons
                    .setLargeIcon(largeIcon)
                    .setSmallIcon(android.R.drawable.stat_sys_headset)
                    // Set Notification content information
                    .setContentText(activeAudio.getArtist())
                    .setContentTitle(activeAudio.getAlbum())
                    .setContentInfo(activeAudio.getTitle())
                    // Add playback actions
                    .addAction(android.R.drawable.ic_media_previous, "previous", playbackAction(3))
                    .addAction(notificationAction, "pause", play_pauseAction)
                    .addAction(android.R.drawable.ic_media_next, "next", playbackAction(2));

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notificationBuilder.build());
        }

        private void removeNotification() {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
        }


        private PendingIntent playbackAction(int actionNumber) {
            Intent playbackAction = new Intent(this, MediaPlayerService.class);
            switch (actionNumber) {
                case 0:
                    // Play
                    playbackAction.setAction(ACTION_PLAY);
                    return PendingIntent.getService(this, actionNumber, playbackAction, 0);
                case 1:
                    // Pause
                    playbackAction.setAction(ACTION_PAUSE);
                    return PendingIntent.getService(this, actionNumber, playbackAction, 0);
                case 2:
                    // Next track
                    playbackAction.setAction(ACTION_NEXT);
                    return PendingIntent.getService(this, actionNumber, playbackAction, 0);
                case 3:
                    // Previous track
                    playbackAction.setAction(ACTION_PREVIOUS);
                    return PendingIntent.getService(this, actionNumber, playbackAction, 0);
                default:
                    break;
            }
            return null;
        }



        private void handleIncomingActions(Intent playbackAction) {
            if (playbackAction == null || playbackAction.getAction() == null) return;

            String actionString = playbackAction.getAction();
            if (actionString.equalsIgnoreCase(ACTION_PLAY)) {
                transportControls.play();
            } else if (actionString.equalsIgnoreCase(ACTION_PAUSE)) {
                transportControls.pause();
            } else if (actionString.equalsIgnoreCase(ACTION_NEXT)) {
                transportControls.skipToNext();
            } else if (actionString.equalsIgnoreCase(ACTION_PREVIOUS)) {
                transportControls.skipToPrevious();
            } else if (actionString.equalsIgnoreCase(ACTION_STOP)) {
                transportControls.stop();
            }
        }


        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                //Load data from SharedPreferences
                StorageUtil storage = new StorageUtil(getApplicationContext());
                audioList = storage.loadAudio();
                audioIndex = storage.loadAudioIndex();

                if (audioIndex != -1 && audioIndex < audioList.size()) {
                    //index is in a valid range
                    activeAudio = audioList.get(audioIndex);
                } else {
                    stopSelf();
                }
            } catch (NullPointerException e) {
                stopSelf();
            }

            //Request audio focus
            if (requestAudioFocus() == false) {
                //Could not gain focus
                stopSelf();
            }

            if (mediaSessionManager == null) {
                try {
                    initMediaSession();
                    initMediaPlayer();
                } catch (RemoteException e) {
                    e.printStackTrace();
                    stopSelf();
                }
                buildNotification(PlaybackStatus.PLAYING);
            }

            //Handle Intent action from MediaSession.TransportControls
            handleIncomingActions(intent);
            return super.onStartCommand(intent, flags, startId);
        }

}