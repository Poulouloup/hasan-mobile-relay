package com.hasan.v1.network

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hasan.v1.MainActivity
import com.hasan.v1.R
import com.hasan.v1.utils.MarkdownUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Consomme le canal `proactive` du relay (messages poussés par Hermes hors
 * d'un tour de conversation initié par l'utilisateur) et les affiche :
 *   - app au premier plan : rien à faire ici, le ViewModel collecte le
 *     même canal directement pour l'UI ;
 *   - app en arrière-plan : notification Android (tap → ouvre l'app).
 *
 * Pas d'action "Répondre" (RemoteInput) pour l'instant — nécessite un
 * point d'accès à [ConnectionManager] depuis un BroadcastReceiver, qui
 * sera clarifié à l'intégration dans MainViewModel (étape suivante du
 * portage) plutôt que d'introduire un singleton prématurément ici.
 *
 * L'inbox (historique des messages proactifs non lus) reste dans
 * [com.hasan.v1.db] via le ViewModel — ce handler ne fait qu'afficher,
 * il ne persiste rien lui-même.
 */
class ProactiveMessageHandler(
    private val context: Context,
    private val multiplexer: ChannelMultiplexer,
    private val isAppInForeground: () -> Boolean = { defaultIsAppInForeground(context) }
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            multiplexer.proactive.collect { envelope ->
                if (envelope.type != "message") return@collect
                val text = envelope.payload.optString("text").takeIf { it.isNotBlank() } ?: return@collect

                if (isAppInForeground()) {
                    // L'UI (ViewModel) collecte multiplexer.proactive elle-même pour l'affichage
                    // en direct — pas de notification à pousser par-dessus.
                    return@collect
                }
                showNotification(text)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun showNotification(content: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROACTIVE,
                "Messages proactifs Hasan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages envoyés par Hasan sans action de votre part"
                enableVibration(true)
            }
        )

        val body = MarkdownUtils.stripMarkdown(content).take(200)

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_PROACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hasan")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify(NOTIF_ID_PROACTIVE, notif)
    }

    companion object {
        private const val NOTIF_ID_PROACTIVE = 5
        private const val CHANNEL_PROACTIVE = "hasan_proactive_v1"

        private fun defaultIsAppInForeground(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return am.runningAppProcesses?.any {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == context.packageName
            } == true
        }
    }
}
