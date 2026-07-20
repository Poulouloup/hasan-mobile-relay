package com.hasan.v1.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hasan.v1.MainActivity
import com.hasan.v1.R

/**
 * Notifications de message entrant Hasan — extrait de l'ancien
 * HassanNotificationService (supprimé, voir audit v2 B1+B4 : le service
 * portait un trustAll TLS et un foregroundServiceType dataSync inutilisés,
 * son polling SSE était un no-op car l'endpoint stream n'existe pas côté
 * Hermes). Seule cette partie était réellement utile.
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val NOTIF_ID_MESSAGE = 4
    private const val CHANNEL_MESSAGES = "hasan_messages_v2"

    /** Affiche une notification de message — appelable depuis n'importe quel contexte. */
    fun notifyMessage(context: Context, content: String) {
        showNotification(context, content)
    }

    private fun showNotification(context: Context, content: String) {
        // Vérifier la permission POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS permission manquante — notif ignorée")
            return
        }

        val nm = context.getSystemService(NotificationManager::class.java)

        // Créer le canal si absent (idempotent)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages Hasan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour les messages entrants de Hasan"
                enableVibration(true)
                enableLights(true)
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
        val notif = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nouveau message")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify(NOTIF_ID_MESSAGE, notif)
        Log.d(TAG, "Notification envoyée : ${body.take(60)}")
    }
}
