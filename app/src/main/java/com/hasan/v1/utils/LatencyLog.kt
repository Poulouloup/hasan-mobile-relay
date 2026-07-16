package com.hasan.v1.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Instrumentation de latence pour le pipeline chat texte (envoi -> reception WS ->
 * parsing -> flush DB -> affichage) et le cycle de vie de connexion/session.
 *
 * Double sortie : Logcat (pratique en dev, filtrable en direct via
 * `adb logcat -s HasanLatency:D`) ET fichier local persistant (survit a un tour
 * long qui fait tourner le buffer logcat circulaire avant consultation) :
 *
 *   adb pull /data/data/com.hasan.v1/files/logs/latency.log
 *
 * Ecriture fichier asynchrone (scope IO dedie) pour ne jamais bloquer le pipeline
 * chat sur une I/O disque. Rotation simple a ~2 Mo : garde la deuxieme moitie
 * (le tour en cours est plus utile que le tout debut de l'historique).
 *
 * A retirer une fois la latence/jitter Hermes identifiee et corrigee — ce n'est
 * pas un mecanisme de logging permanent du projet.
 */
object LatencyLog {

    private const val TAG = "HasanLatency"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Timestamp du dernier point logue par tour (cle = turn id) — permet de
    // calculer un delta inter-etapes sans faire circuler le timestamp
    // precedent manuellement a travers plusieurs fichiers.
    private val lastStepAt = ConcurrentHashMap<String, Long>()

    @Volatile private var logFile: File? = null

    /** A appeler une fois au demarrage (voir MainViewModel.init{}, a cote de HassanSoundPlayer.init). */
    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "latency.log")
    }

    /** Chemin absolu du fichier de log, pour adb pull — vide tant que init() n'a pas ete appele. */
    fun exportPath(): String = logFile?.absolutePath ?: "(LatencyLog.init() non appele)"

    fun mark(point: String, turn: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val previous = lastStepAt.put(turn, now)
        val delta = if (previous != null) "${now - previous}ms" else "-"
        // Aplati les retours à la ligne du detail (ex: contenu de message loggé via
        // DONE_CONTENT/SUSPECT_ERROR_AS_RESPONSE) — sans ça, un texte commençant par
        // \n coupe la ligne de log au niveau du fichier, faisant croire à un detail
        // vide alors que le contenu réapparaît, mélangé, sur les lignes suivantes.
        val flatDetail = detail.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val suffix = if (flatDetail.isNotEmpty()) " $flatDetail" else ""
        val line = "[$point] turn=$turn delta=$delta$suffix"
        Log.d(TAG, line)
        appendToFile(line)
    }

    /** A appeler quand le tour se termine (Done/Error) pour eviter une fuite mémoire du registre. */
    fun clear(turn: String) {
        lastStepAt.remove(turn)
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        val timestamped = "${timestampFormat.format(System.currentTimeMillis())} $line\n"
        ioScope.launch {
            try {
                if (file.length() > MAX_FILE_BYTES) rotate(file)
                file.appendText(timestamped)
            } catch (_: Exception) {
                // Best-effort : un echec d'ecriture du log ne doit jamais faire remonter
                // d'erreur au pipeline chat qui a appele mark().
            }
        }
    }

    private fun rotate(file: File) {
        val content = file.readText()
        val keepFrom = content.length / 2
        val truncated = content.substring(keepFrom)
        file.writeText("--- log tronque (rotation ~2Mo) ---\n$truncated")
    }
}
