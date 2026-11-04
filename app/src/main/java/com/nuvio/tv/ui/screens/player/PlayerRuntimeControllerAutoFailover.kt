package com.nuvio.tv.ui.screens.player

import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTOPLAY_FAILOVERS = 8
private const val CANDIDATE_POLL_MS = 300L
private const val CANDIDATE_SETTLE_MS = 8_000L
private const val CANDIDATE_GIVE_UP_MS = 25_000L
private const val SWITCH_RESOLVE_TIMEOUT_MS = 45_000L

/**
 * Called when the stream autoplay picked turns out to be dead (fatal player error).
 *
 * Instead of showing the error, picks the next stream autoplay would have chosen, skipping every
 * stream that already failed, and plays it. Repeats until something plays, the candidates run out
 * or [MAX_AUTOPLAY_FAILOVERS] switches were made.
 *
 * Returns true when the failure is being handled (the caller must not show the error). Returns
 * false when the caller should publish the error as usual: the user chose this stream, or the cap
 * was reached. [onNoCandidate] is invoked later, on the controller scope, if the chain runs out of
 * candidates; it should publish the original error.
 */
internal fun PlayerRuntimeController.tryAutoSwitchToNextStream(onNoCandidate: () -> Unit): Boolean {
    if (!autoFailoverActive) return false
    if (autoFailoverJob?.isActive == true) return true
    if (autoPlayFailoverCount >= MAX_AUTOPLAY_FAILOVERS) {
        autoFailoverActive = false
        return false
    }

    // Remember what just failed, both the played URL and the underlying torrent/debrid identity.
    failedAutoPlayStreamKeys += "url:$currentStreamUrl"
    _uiState.value.currentStreamInfoHash?.takeIf { it.isNotBlank() }?.let {
        failedAutoPlayStreamKeys += "hash:${it.lowercase()}"
    }
    navigationArgs.infoHash?.takeIf { it.isNotBlank() }?.let {
        failedAutoPlayStreamKeys += "hash:${it.lowercase()}"
    }

    showRecoveryOverlay()
    autoFailoverJob = scope.launch {
        try {
            var switched = false
            while (autoPlayFailoverCount < MAX_AUTOPLAY_FAILOVERS) {
                val next = awaitNextAutoPlayCandidate() ?: break
                failedAutoPlayStreamKeys += StreamAutoPlaySelector.failureKeys(next)
                autoPlayFailoverCount++
                Toast.makeText(
                    context,
                    context.getString(R.string.player_autoplay_failover_next),
                    Toast.LENGTH_SHORT
                ).show()

                _uiState.update { it.copy(sourceStreamsError = null) }
                switchToSourceStream(next, fromAutoFailover = true)

                // Debrid / torrent / server / YouTube streams are resolved asynchronously. Wait for
                // that to finish; a resolution failure just moves on to the following candidate.
                var waited = 0L
                while (_uiState.value.isLoadingSourceStreams && waited < SWITCH_RESOLVE_TIMEOUT_MS) {
                    delay(CANDIDATE_POLL_MS)
                    waited += CANDIDATE_POLL_MS
                }
                val state = _uiState.value
                if (state.sourceStreamsError == null && !state.isLoadingSourceStreams) {
                    switched = true
                    break
                }
                showRecoveryOverlay()
            }
            if (!switched) {
                autoFailoverActive = false
                onNoCandidate()
            }
        } finally {
            autoFailoverJob = null
        }
    }
    return true
}

/** Waits for the stream list to load and returns the best not-yet-failed autoplay candidate. */
private suspend fun PlayerRuntimeController.awaitNextAutoPlayCandidate(): Stream? {
    val videoId = currentVideoId ?: contentId ?: return null
    val settings = playerSettingsDataStore.playerSettings.first()
    val installedAddonNames = streamAddonsFor(videoId).map { it.displayName }.toSet()

    // The stream list is loaded in the background; MANUAL mode (autoplay via a remembered binge
    // group) has no ordering preference of its own, so use the plain first playable stream.
    loadSourceStreams(forceRefresh = false)

    fun pick(streams: List<Stream>): Stream? {
        val configured = settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL
        val primary = if (configured) {
            StreamAutoPlaySelector.selectNextAutoPlayStream(
                streams = streams,
                failedKeys = failedAutoPlayStreamKeys,
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedAddonNames,
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                selectedPlugins = settings.streamAutoPlaySelectedPlugins
            )
        } else {
            null
        }
        // The stream we are replacing is dead; any other playable stream beats an error screen.
        return primary ?: StreamAutoPlaySelector.selectNextAutoPlayStream(
            streams = streams,
            failedKeys = failedAutoPlayStreamKeys,
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = installedAddonNames,
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )
    }

    var elapsed = 0L
    while (elapsed < CANDIDATE_GIVE_UP_MS) {
        delay(CANDIDATE_POLL_MS)
        elapsed += CANDIDATE_POLL_MS
        val candidate = pick(_uiState.value.sourceAllStreams)
        if (candidate != null && (sourceStreamsFetchCompleted || elapsed >= CANDIDATE_SETTLE_MS)) {
            return candidate
        }
        if (sourceStreamsFetchCompleted && candidate == null && elapsed >= 1_500L) {
            return null
        }
    }
    return pick(_uiState.value.sourceAllStreams)
}
