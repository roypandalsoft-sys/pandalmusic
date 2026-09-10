package com.pandal.music.player

import android.media.audiofx.Equalizer
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    companion object {
        const val CMD_EQ_ENABLED = "com.pandal.music.EQ_ENABLED"
        const val CMD_EQ_BAND = "com.pandal.music.EQ_BAND"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_BAND = "band"
        const val EXTRA_LEVEL = "level"
    }

    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        equalizer?.release()
                        equalizer = runCatching {
                            Equalizer(0, audioSessionId).apply { enabled = true }
                        }.getOrNull()
                    }
                }
            })
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val available = SessionCommands.Builder()
                    .addAll(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                    .add(SessionCommand(CMD_EQ_ENABLED, Bundle.EMPTY))
                    .add(SessionCommand(CMD_EQ_BAND, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(available)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    CMD_EQ_ENABLED -> {
                        equalizer?.enabled = args.getBoolean(EXTRA_ENABLED, true)
                    }
                    CMD_EQ_BAND -> {
                        val eq = equalizer
                        if (eq != null) {
                            val requestedBand = args.getInt(EXTRA_BAND, 0)
                            val band = requestedBand.coerceIn(0, eq.numberOfBands.toInt() - 1)
                            val range = eq.bandLevelRange
                            val level = args.getInt(EXTRA_LEVEL, 0)
                                .coerceIn(range[0].toInt(), range[1].toInt())
                            runCatching { eq.setBandLevel(band.toShort(), level.toShort()) }
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        equalizer?.release()
        equalizer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
