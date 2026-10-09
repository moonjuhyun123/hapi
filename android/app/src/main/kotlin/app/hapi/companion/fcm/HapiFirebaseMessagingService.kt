package app.hapi.companion.fcm

import app.hapi.companion.HapiApp
import app.hapi.companion.di.AppGraph
import app.hapi.companion.di.localizedForAppLanguage
import app.hapi.data.push.PushPayload
import app.hapi.data.push.shouldSuppressPush
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point (B-M4a). Messages are **data-only** by contract
 * (`docs/api/native-companion-contract.md`) — a `notification` block would
 * stop `onMessageReceived` from running in the background — so every render
 * decision is client-side: [PushPayload] parses/routes, [PushNotifications]
 * builds, and the one suppression rule ([shouldSuppressPush]) skips the OS
 * notification only when the app is foreground *with that session's chat
 * open* (the in-app SSE stream is already showing the event).
 *
 * Without a Firebase config this service is inert — no token, no delivery —
 * and [app.hapi.companion.push.PushBinding] keeps the rest of the push
 * surface no-op'd to match.
 */
class HapiFirebaseMessagingService : FirebaseMessagingService() {

    private val appGraph: AppGraph
        get() = (application as HapiApp).appGraph

    /** Token minted or rotated: (re-)register it with every paired hub. */
    override fun onNewToken(token: String) {
        appGraph.onPushTokenRotated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val graph = appGraph
        val payload = graph.pushMessageDecoder.decode(message.data) ?: return
        if (shouldSuppressPush(graph.foreground, graph.openChatSessionId.value, payload.sessionId)) {
            return
        }
        // In-app language (B-M5a): notification strings resolve from this
        // service context, which per-app locales miss on API < 33.
        val context = localizedForAppLanguage(graph.appLanguage.value)
        // Jarvis 집사 알림 (step 15): 「집사」 + what it said; a hand-over ack stays silent.
        val latest = if (payload.type == app.hapi.data.push.PushType.READY) {
            kotlinx.coroutines.runBlocking { app.hapi.companion.feature.jarvis.push.fetchLatestReply(graph.pushHubAccess, payload.sessionId) }
        } else {
            null
        }
        val labels = app.hapi.companion.feature.jarvis.push.ButlerPushLabels(
            butler = context.getString(app.hapi.companion.R.string.jarvis_butler_title),
            replied = context.getString(app.hapi.companion.R.string.jarvis_push_replied),
            asks = context.getString(app.hapi.companion.R.string.jarvis_push_asks),
            permission = context.getString(app.hapi.companion.R.string.jarvis_push_permission),
        )
        val shown = app.hapi.companion.feature.jarvis.push.butlerPush(payload, latest, labels) ?: return
        PushNotifications.show(context, shown, app.hapi.companion.feature.jarvis.push.ButlerChannel.ID)
    }
}
