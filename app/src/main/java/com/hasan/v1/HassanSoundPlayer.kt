package com.hasan.v1

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Génère et joue des sons synthétiques PCM via SoundPool.
 * SoundPool est conçu pour les effets sonores courts — pas de conflit AudioFocus,
 * pas de latence, lecture garantie même en mode silencieux partiel.
 *
 * Synthèse :
 *   - PICKUP/COIN : sweep 440→1100 Hz, 380ms, enveloppe attaque+decay exponentiel
 *   - BLIP/SELECT : sweep 720→360 Hz, 240ms, decay exponentiel
 */
object HassanSoundPlayer {

    private const val SAMPLE_RATE = 22050

    private var soundPool: SoundPool? = null
    private var wakeId: Int = 0
    private var endId: Int  = 0
    private var ready = false

    fun init(context: Context) {
        if (ready) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        pool.setOnLoadCompleteListener { _, _, _ -> }

        // Charge les sons depuis le PCM synthétisé via AudioTrack en mémoire
        wakeId = pool.load(buildWavTempFile(context, generatePickupCoin()), 1)
        endId  = pool.load(buildWavTempFile(context, generateBlipSelect()), 1)

        soundPool = pool
        ready = true
    }

    fun playWake() {
        soundPool?.play(wakeId, 1f, 1f, 1, 0, 1f)
    }

    fun playEnd() {
        soundPool?.play(endId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        ready = false
        wakeId = 0
        endId  = 0
    }

    // ─────────────────────────── Synthèse PCM ─────────────────────────────

    private fun generatePickupCoin(): ShortArray {
        val durationMs = 380
        val samples = SAMPLE_RATE * durationMs / 1000
        val buf = ShortArray(samples)
        val freqStart = 440.0
        val freqEnd   = 1100.0

        for (i in 0 until samples) {
            val t       = i.toDouble() / SAMPLE_RATE
            val tNorm   = i.toDouble() / samples
            val attack  = if (tNorm < 0.05) tNorm / 0.05 else 1.0
            val decay   = exp(-4.0 * tNorm)
            val env     = attack * decay
            val phase   = 2 * PI * (freqStart * t + (freqEnd - freqStart) * t * tNorm / 2)
            buf[i] = (sin(phase) * env * Short.MAX_VALUE * 0.7).toInt().toShort()
        }
        return buf
    }

    private fun generateBlipSelect(): ShortArray {
        val durationMs = 240
        val samples = SAMPLE_RATE * durationMs / 1000
        val buf = ShortArray(samples)
        val freqStart = 720.0
        val freqEnd   = 360.0

        for (i in 0 until samples) {
            val t       = i.toDouble() / SAMPLE_RATE
            val tNorm   = i.toDouble() / samples
            val decay   = exp(-6.0 * tNorm)
            val phase   = 2 * PI * (freqStart * t + (freqEnd - freqStart) * t * tNorm / 2)
            buf[i] = (sin(phase) * decay * Short.MAX_VALUE * 0.55).toInt().toShort()
        }
        return buf
    }

    // ─────────────────────────── Écriture WAV temporaire ──────────────────

    /**
     * Encode les samples PCM en fichier WAV dans le cache de l'app.
     * SoundPool charge uniquement depuis un fichier ou un AssetFileDescriptor.
     */
    private fun buildWavTempFile(context: Context, pcm: ShortArray): String {
        val file = java.io.File(context.cacheDir, "hasan_sound_${pcm.size}.wav")
        if (file.exists()) return file.absolutePath

        val dataSize = pcm.size * 2
        val totalSize = 36 + dataSize

        java.io.FileOutputStream(file).use { fos ->
            val buf = java.io.DataOutputStream(fos)

            // RIFF header
            buf.writeBytes("RIFF")
            writeInt32LE(buf, totalSize)
            buf.writeBytes("WAVE")

            // fmt chunk
            buf.writeBytes("fmt ")
            writeInt32LE(buf, 16)           // chunk size
            writeInt16LE(buf, 1)            // PCM
            writeInt16LE(buf, 1)            // mono
            writeInt32LE(buf, SAMPLE_RATE)
            writeInt32LE(buf, SAMPLE_RATE * 2) // byte rate
            writeInt16LE(buf, 2)            // block align
            writeInt16LE(buf, 16)           // bits per sample

            // data chunk
            buf.writeBytes("data")
            writeInt32LE(buf, dataSize)
            for (sample in pcm) {
                writeInt16LE(buf, sample.toInt())
            }
            buf.flush()
        }
        return file.absolutePath
    }

    private fun writeInt32LE(out: java.io.DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeInt16LE(out: java.io.DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
