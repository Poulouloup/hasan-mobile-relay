package com.hasan.v1.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Lecture audio gapless (Media3 ExoPlayer) pour des fichiers PCM/WAV/MP3 déjà
 * synthétisés — remplace `MediaPlayer` dans [com.hasan.v1.EdgeTtsEngine], qui
 * recréait un player à chaque chunk de phrase et laissait un micro-silence
 * entre deux phrases (`mp.release()` puis nouveau `MediaPlayer` à chaque appel).
 *
 * Gapless via la playlist native d'ExoPlayer : [enqueue] ajoute un fichier à
 * la file sans interrompre la lecture en cours — ExoPlayer enchaîne
 * automatiquement dès que l'item courant se termine, sans recréer de player
 * ni introduire de silence. Contrairement à [play], qui vide la file et
 * démarre immédiatement (utilisé pour une lecture isolée, ex. son de
 * confirmation).
 *
 * Un seul thread consommateur : toutes les méthodes doivent être appelées
 * depuis le thread principal (contrainte d'ExoPlayer).
 */
class VoicePlayer(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false, // le focus audio est géré par l'appelant (ducking barge-in inclus)
        )
        .build()

    private var onAllPlaybackDone: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onAllPlaybackDone?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error)
            }
        })
    }

    /** Notifié quand la playlist entière a fini de jouer (dernier item terminé). */
    fun setOnAllPlaybackDone(callback: (() -> Unit)?) {
        onAllPlaybackDone = callback
    }

    /** Notifié sur toute erreur de lecture (fichier corrompu, format non supporté...). */
    fun setOnError(callback: ((Throwable) -> Unit)?) {
        onError = callback
    }

    /** Vide la file et joue immédiatement [file] seul — pour une lecture isolée hors pipeline TTS. */
    fun play(file: File) {
        runOnMainThread {
            player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            player.prepare()
            player.play()
        }
    }

    /**
     * Ajoute [file] à la fin de la playlist sans interrompre la lecture en
     * cours. Premier appel après [stop] : équivalent à [play]. Appels
     * suivants pendant qu'un item joue déjà : ExoPlayer enchaîne
     * automatiquement à la fin de l'item courant, sans gap.
     */
    fun enqueue(file: File) {
        runOnMainThread {
            val wasEmpty = player.mediaItemCount == 0
            player.addMediaItem(MediaItem.fromUri(file.toURI().toString()))
            if (wasEmpty) {
                player.prepare()
                player.play()
            }
        }
    }

    fun stop() {
        runOnMainThread {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun isSpeaking(): Boolean = player.isPlaying

    /** Ducking barge-in (voir [BargeInListener]) — volume réduit sans couper la lecture. */
    fun duck(volume: Float = 0.3f) {
        runOnMainThread { player.volume = volume.coerceIn(0f, 1f) }
    }

    fun unduck() {
        runOnMainThread { player.volume = 1f }
    }

    fun setVolume(volume: Float) {
        runOnMainThread { player.volume = volume.coerceIn(0f, 1f) }
    }

    fun setSpeed(speed: Float) {
        runOnMainThread {
            player.playbackParameters = player.playbackParameters.withSpeed(speed.coerceIn(0.5f, 2.0f))
        }
    }

    fun release() {
        runOnMainThread { player.release() }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
