package com.sideload.splitinstaller.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.BundleScanner
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadNames
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.sources.LinkProbe
import com.sideload.splitinstaller.core.sources.LinkProblem
import com.sideload.splitinstaller.core.sources.Links
import com.sideload.splitinstaller.core.sources.Source
import com.sideload.splitinstaller.core.sources.Sources
import com.sideload.splitinstaller.core.update.FDroidHit
import com.sideload.splitinstaller.core.update.Http
import com.sideload.splitinstaller.core.update.SourceRef
import com.sideload.splitinstaller.core.update.UpdateChecker
import com.sideload.splitinstaller.core.update.UpdateKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    val link: LinkState = LinkState(),
    val fdroid: FDroidSearch = FDroidSearch(),
) {
    val selected: Source get() = sources.firstOrNull { it.id == selectedId } ?: sources.first()
}

/** A link checked and resolved, waiting for a yes before it is downloaded and installed. */
data class LinkPlan(
    val url: String,
    val fileName: String,
    val size: Long?,
    /** Where it comes from, as shown: a source's name or the link's host. */
    val origin: String,
    val versionName: String?,
    val expectedPackage: String?,
    /** The host that actually serves the file, when a redirect leads somewhere else. */
    val servedBy: String? = null,
)

data class LinkState(
    val text: String = "",
    val busy: Boolean = false,
    /** A [LinkProblem] code, with the HTTP status when there is one. */
    val problem: String? = null,
    val httpCode: Int = 0,
    val plan: LinkPlan? = null,
    /** A shared link that turned out to be a web page: opened only after a tap. */
    val page: String? = null,
)

/** F-Droid's own search, answered in the app; null hits means none asked yet. */
data class FDroidSearch(
    val busy: Boolean = false,
    val hits: List<FDroidHit>? = null,
    val failed: Boolean = false,
    /** The package whose newest build is being looked up. */
    val installing: String? = null,
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    private var poll: Job? = null

    init {
        Sources.load(app)
        _state.update { it.copy(selectedId = Sources.selected(app).id) }
        viewModelScope.launch { Sources.all.collect { list -> _state.update { it.copy(sources = list) } } }
        viewModelScope.launch { Downloads.completed.collect { refreshDownloads() } }
        // Downloads started elsewhere (an update check, the OTA card) show up here at once.
        viewModelScope.launch { Downloads.changed.collect { refreshDownloads() } }
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
        // F-Droid answers searches for apps: the results come with an Install button.
        if (source.id == FDROID_SOURCE && s.query.isNotBlank()) {
            searchFDroid()
            return
        }
        EventLog.info("search on ${source.name}: ${s.query.trim()}")
        open(source.searchFor(s.query), source)
    }

    /**
     * Straight to a search from elsewhere in the app, e.g. "find a newer version" or "pin a
     * page": always the site in the browser, since that is where a page gets pinned.
     */
    fun searchFor(query: String, pinFor: String? = null, pinLabel: String? = null) {
        _state.update { it.copy(query = query, pinFor = pinFor, pinLabel = pinLabel) }
        val source = _state.value.selected
        EventLog.info("search on ${source.name}: ${query.trim()}")
        open(source.searchFor(query), source)
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

    // ---- a link, pasted or shared --------------------------------------------

    fun setLink(text: String) = _state.update { it.copy(link = it.link.copy(text = text, problem = null)) }

    private var linkJob: Job? = null

    /**
     * Works out what a link would download; nothing starts until [confirmLink]. A project or
     * app page on a source with an API resolves to its newest build; any other link is probed:
     * a file is offered; a web page opens in the browser — after a tap when the link was
     * [shared] in from another app, since a page there can start downloads by itself.
     */
    fun prepareLink(text: String = _state.value.link.text, shared: Boolean = false) {
        val url = Links.extractUrl(text)
        if (url == null) {
            _state.update { it.copy(link = LinkState(text = text, problem = LinkProblem.NOT_A_LINK)) }
            return
        }
        // The newest link wins: an older check finishing late must not put its plan up.
        linkJob?.cancel()
        _state.update { it.copy(link = LinkState(text = url, busy = true)) }
        linkJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeout(LINK_DEADLINE_MS) { resolveLink(url) } }
            }
            if (_state.value.link.text != url) return@launch
            result.onSuccess { plan ->
                when {
                    plan != null -> _state.update { it.copy(link = it.link.copy(busy = false, plan = plan)) }
                    shared -> _state.update { it.copy(link = it.link.copy(busy = false, page = url)) }
                    else -> {
                        EventLog.info("the link is a web page; opening it: $url")
                        _state.update { it.copy(link = it.link.copy(busy = false)) }
                        openUrl(url)
                    }
                }
            }.onFailure { t ->
                val problem = t as? LinkProblem
                EventLog.warn("link not usable: $url (${t.message})")
                _state.update {
                    it.copy(
                        link = it.link.copy(
                            busy = false,
                            problem = problem?.code ?: LinkProblem.UNREACHABLE,
                            httpCode = problem?.httpCode ?: 0,
                        )
                    )
                }
            }
        }
    }

    /** A shared web page, opened on a tap; or a link the server refused, tried in the browser. */
    fun openLinkPage() {
        val url = _state.value.link.page ?: _state.value.link.text.takeIf { Links.isHttps(it) } ?: return
        _state.update { it.copy(link = it.link.copy(page = null, problem = null)) }
        openUrl(url)
    }

    fun dismissLinkPage() = _state.update { it.copy(link = it.link.copy(page = null)) }

    /** The plan for a file, or null for a page that has to be opened instead. */
    private suspend fun resolveLink(url: String): LinkPlan? {
        // A link to one file stays that file, even on a host that also has a release API.
        val ref = if (Links.isFileLink(url)) null else UpdateChecker.sourceFromLink(url)
        ref?.let { ref ->
            val latest = UpdateChecker.resolveLatest(ref, Build.SUPPORTED_ABIS.toList())
                ?: throw LinkProblem(LinkProblem.NO_FILE)
            return LinkPlan(
                url = latest.url,
                fileName = latest.fileName,
                size = latest.size.takeIf { it > 0 },
                origin = originOf(ref.kind),
                versionName = latest.versionName,
                expectedPackage = latest.packageName,
            )
        }
        if (!Links.isHttps(url)) throw LinkProblem(LinkProblem.CLEARTEXT)
        val probe = LinkProbe.probe(url, Http.USER_AGENT)
        if (probe.isPage) return null
        if (!probe.isFile) throw LinkProblem(LinkProblem.NO_FILE)
        // The server's name for it, else the last part of where the link ended up; a link that
        // names nothing gets the extension its first bytes call for.
        val named = DownloadNames.fileName(probe.finalUrl, probe.contentDisposition, probe.contentType)
        val name = if (BundleScanner.isBundleName(named)) {
            named
        } else {
            named.substringBeforeLast('.').ifBlank { "download" } + Links.extensionFor(probe.head)
        }
        val origin = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
        val served = runCatching { URI(probe.finalUrl).host.removePrefix("www.") }.getOrNull()
        return LinkPlan(
            url = url,
            fileName = name,
            size = probe.size,
            origin = origin,
            versionName = null,
            expectedPackage = null,
            servedBy = served?.takeIf { it != origin },
        )
    }

    private fun originOf(kind: UpdateKind): String = when (kind) {
        UpdateKind.FDROID -> "F-Droid"
        UpdateKind.IZZYONDROID -> "IzzyOnDroid"
        UpdateKind.GITHUB -> "GitHub"
        UpdateKind.GITLAB -> "GitLab"
        UpdateKind.FORGEJO -> "Codeberg / Forgejo"
        UpdateKind.WEB -> "web"
    }

    fun confirmLink() {
        val plan = _state.value.link.plan ?: return
        _state.update { it.copy(link = LinkState()) }
        startInstallDownload(plan.url, plan.fileName, plan.expectedPackage)
    }

    fun dismissLink() = _state.update { it.copy(link = it.link.copy(plan = null)) }

    /**
     * Downloads a file someone asked to install. Only a transfer still running counts as the
     * same: a finished file may be stale (a "latest" link serves a newer build) or gone.
     */
    private fun startInstallDownload(url: String, fileName: String, expectedPackage: String?) = viewModelScope.launch {
        val app = getApplication<Application>()
        val running = withContext(Dispatchers.IO) { Downloads.list(app) }
            .firstOrNull { it.sourceUrl == url && it.active && it.expectedPackage != BuildConfig.APPLICATION_ID }
        when {
            running != null -> EventLog.info("already downloading: ${running.fileName}")
            else -> runCatching {
                withContext(Dispatchers.IO) {
                    Downloads.start(
                        app,
                        DownloadRequest(url, Http.USER_AGENT, DownloadNames.attachment(fileName), null, null),
                        expectedPackage = expectedPackage,
                        installNow = true,
                        withCookies = false,
                    )
                }
            }.onFailure { EventLog.error("could not start the download: ${it.message}") }
        }
        refreshDownloads()
    }

    // ---- F-Droid search --------------------------------------------------------

    fun searchFDroid() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        EventLog.info("search on F-Droid: $q")
        _state.update { it.copy(fdroid = FDroidSearch(busy = true)) }
        viewModelScope.launch {
            val result = runCatching { UpdateChecker.searchFDroid(q) }
            result.exceptionOrNull()?.let { EventLog.warn("F-Droid search failed: ${it.message}") }
            _state.update { it.copy(fdroid = FDroidSearch(hits = result.getOrNull(), failed = result.isFailure)) }
        }
    }

    fun installFromFDroid(hit: FDroidHit) = viewModelScope.launch {
        _state.update { it.copy(fdroid = it.fdroid.copy(installing = hit.packageName)) }
        val latest = runCatching {
            UpdateChecker.resolveLatest(SourceRef(UpdateKind.FDROID, hit.packageName), Build.SUPPORTED_ABIS.toList())
        }.getOrNull()
        _state.update { it.copy(fdroid = it.fdroid.copy(installing = null, failed = latest == null)) }
        if (latest == null) {
            EventLog.warn("F-Droid has no build of ${hit.packageName} to install")
            return@launch
        }
        startInstallDownload(latest.url, latest.fileName, hit.packageName)
    }

    fun clearFDroid() = _state.update { it.copy(fdroid = FDroidSearch()) }

    private companion object {
        const val FDROID_SOURCE = "fdroid"

        /** A check that has not answered by then is not going to. */
        const val LINK_DEADLINE_MS = 45_000L
    }
}
