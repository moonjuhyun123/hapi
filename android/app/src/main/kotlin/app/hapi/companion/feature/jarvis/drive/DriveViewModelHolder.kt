package app.hapi.companion.feature.jarvis.drive

import androidx.lifecycle.ViewModel
import app.hapi.companion.di.HubGraph
import app.hapi.companion.feature.files.ApiFilesGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keeps the drive's state across configuration changes (twin of the upstream files holder). */
internal class DriveViewModelHolder(hubGraph: HubGraph, sessionId: String) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = DriveViewModel(sessionId = sessionId, gateway = ApiFilesGateway(hubGraph.session.api), scope = scope)

    override fun onCleared() {
        scope.cancel()
    }
}
