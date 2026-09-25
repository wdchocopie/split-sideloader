package com.sideload.splitinstaller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.sources.Source
import com.sideload.splitinstaller.core.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One browsing session. A new [session] means a fresh WebView; the URL inside it may change freely. */
data class BrowserTarget(
    val session: Long,
    val url: String,
    val sourceId: String?,
    val title: String,
)

data class SourcesState(
    val sources: List<Source> = Sources.BUILTIN,
    val selectedId: String = Sources.BUILTIN.first().id,
    val query: String = "",
    val downloads: List<DownloadItem> = emptyList(),
    val browser: BrowserTarget? = null,
    /** Where the browser was last, so it can pick up there after another screen covered it. */
    val lastUrl: String? = null,
    val showAdd: Boolean = false,
    val addError: Boolean = false,
    /** Set while the browser is open to pick an update page for this package. */
    val pinFor: String? = null,
    val pinLabel: String? = null,
) {
    val selected: Source get() = sources.firstOrNull { it.id == selectedId } ?: sources.first()
}

class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    private var poll: Job? = null

    init {
        Sources.load(app)
        _state.update { it.copy(selectedId = Sources.selected(app).id) }
        viewModelScope.launch { Sources.all.collect { list -> _state.update { it.copy(sources = list) } } }
        viewModelScope.launch { Downloads.completed.collect { refreshDownloads() } }
        refreshDownloads()
    }

    // ---- searching and browsing --------------------------------------------

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun select(id: String) {
        Sources.select(getApplication(), id)
        _state.update { it.copy(selectedId = id) }
    }

    fun search() {
        val s = _state.value
        val source = s.selected
        EventLog.info("search on ${source.name}: ${s.query.trim()}")
        open(source.searchFor(s.query), source)
    }

    /** Straight to a search from elsewhere in the app, e.g. "find a newer version". */
    fun searchFor(query: String, pinFor: String? = null, pinLabel: String? = null) {
        _state.update { it.copy(query = query, pinFor = pinFor, pinLabel = pinLabel) }
        search()
    }

    fun clearPinRequest() = _state.update { it.copy(pinFor = null, pinLabel = null) }

    fun openHome(source: Source) = open(source.homeUrl, source)

    /** Opens any URL in the in-app browser, tagged with the source that owns its host. */
    fun openUrl(url: String) = open(url, _state.value.sources.firstOrNull { it.owns(url) })

    private fun open(url: String, source: Source?) {
        _state.update {
            it.copy(
                browser = BrowserTarget(System.nanoTime(), url, source?.id, source?.name ?: url),
                lastUrl = null,
            )
        }
    }

    fun onBrowserUrl(url: String) = _state.update { it.copy(lastUrl = url) }

    fun closeBrowser() = _state.update { it.copy(browser = null, lastUrl = null, pinFor = null, pinLabel = null) }

    // ---- downloads -----------------------------------------------------------

    fun download(request: DownloadRequest) = viewModelScope.launch {
        val app = getApplication<Application>()
        // "Install downloads" covers anything fetched in the app, not just pinned updates.
        val auto = Prefs.get(app).updateAutoInstall
        runCatching { withContext(Dispatchers.IO) { Downloads.start(app, request, autoInstall = auto) } }
            .onFailure { EventLog.error("could not start the download: ${it.message}") }
        refreshDownloads()
    }

    fun refreshDownloads() = viewModelScope.launch {
        val list = withContext(Dispatchers.IO) { Downloads.list(getApplication()) }
        _state.update { it.copy(downloads = list) }
        if (list.any { it.active }) keepPolling()
    }

    /** DownloadManager does not push progress, so read it while something is transferring. */
    private fun keepPolling() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (true) {
                delay(700)
                val list = withContext(Dispatchers.IO) { Downloads.list(getApplication()) }
                _state.update { it.copy(downloads = list) }
                if (list.none { it.active }) break
            }
        }
    }

    fun cancel(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { Downloads.cancel(getApplication(), id) }
        refreshDownloads()
    }

    fun forget(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { Downloads.forget(getApplication(), id) }
        refreshDownloads()
    }

    // ---- custom sources --------------------------------------------------------

    fun showAdd(show: Boolean) = _state.update { it.copy(showAdd = show, addError = false) }

    fun addSource(name: String, home: String, search: String) {
        val added = Sources.addCustom(getApplication(), name, home, search)
        if (added == null) {
            _state.update { it.copy(addError = true) }
        } else {
            EventLog.info("source added: ${added.name} (${added.host})")
            _state.update { it.copy(showAdd = false, addError = false, selectedId = added.id) }
            Sources.select(getApplication(), added.id)
        }
    }

    fun removeSource(id: String) {
        Sources.removeCustom(getApplication(), id)
        if (_state.value.selectedId == id) select(Sources.BUILTIN.first().id)
    }
}
