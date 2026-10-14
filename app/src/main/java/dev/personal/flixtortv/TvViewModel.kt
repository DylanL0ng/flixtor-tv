package dev.personal.flixtortv

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen { PROFILES, HOME, MOVIES, SHOWS, ANIME, SEARCH, LIST, ACCOUNT, DETAIL, PLAYER }
enum class SettingsEntry { ROOT, ACCOUNT }
data class AnimeChoice(val episode: Episode?, val resume: Boolean, val automatic: Boolean,
    val available: Set<AnimeVersion> = emptySet(), val loading: Boolean = true,
    val failure: Failure? = null, val missing: AnimeVersion? = null, val preferred: AnimeVersion? = null)

data class TvState(
    val settingsEntry: SettingsEntry = SettingsEntry.ROOT,
    val settingsNavigationId: Int = 0,
    val screen: Screen = Screen.PROFILES, val shelves: List<Shelf> = emptyList(),
    val titles: List<Title> = emptyList(), val detail: Detail? = null,
    val loading: Boolean = false, val failure: Failure? = null,
    val query: String = "", val page: Int = 1, val sort: String = "latest", val hasMore: Boolean = true,
    val account: Account = Account(AccountStatus.CHECKING), val pair: PairCode? = null, val pairing: Boolean = false,
    val favorites: List<Title> = emptyList(), val viewing: List<Viewing> = emptyList(),
    val stream: Stream? = null, val episode: Episode? = null, val resumePosition: Long = 0,
    val focusedKey: String? = null,
    val profiles: List<Profile> = emptyList(), val profile: Profile? = null,
    val playbackIdentity: PlaybackIdentity? = null, val accountChecked: Boolean = false,
    val failureOperation: String = "HOME",
    val appending: Boolean = false, val appendFailure: Failure? = null, val catalogKey: String = "",
    val screenFit: ScreenFit = ScreenFit(),
    val segments: Segments? = null, val segmentsLoading: Boolean = false,
    val segmentsUnavailable: Boolean = false, val skipRevision: Int = 0,
    val stillWatchingSeconds: Int? = null,
    val animeQuery: String = "", val version: AnimeVersion? = null, val animeChoice: AnimeChoice? = null,
    val animeAutoplayBlocked: Boolean = false,
)
class TvViewModel @JvmOverloads constructor(app: Application,
    val client: FlixtorClient = FlixtorClient(SessionStore(app)),
    private val introDb: IntroDbClient = IntroDbClient(),
    val anime: AnimeClient = AnimeClient(), private val aniSkip: AniSkipClient = AniSkipClient()) : AndroidViewModel(app) {
    val updates = UpdateService(app)
    private val profiles = ProfileStore(app)
    private var library = LibraryStore(app)
    private val fitStore = ScreenFitStore(app)
    private val skipStore = SkipStore(app)
    private var segmentsJob: Job? = null
    private var communitySegments = emptyList<SkipSegment>()
    private var segmentsDuration = 0L
    private var segmentsRequest = 0L
    private val catalogRoot = java.io.File(app.filesDir, "catalog_pages").apply { deleteRecursively(); mkdirs() }
    private var pages = CatalogPages(catalogRoot)
    private var catalogJob: Job? = null
    private val mutable = MutableStateFlow(TvState(profiles = profiles.initialize(), screenFit = fitStore.read()))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var pairing: Job? = null
    private var origin = Screen.HOME
    private var pendingPlayback: Pair<Detail, Episode?>? = null
    private var animeChoiceJob: Job? = null
    private var animeChoiceRequest = 0L
    val isAnime get() = mutable.value.screen == Screen.ANIME ||
        (mutable.value.screen in listOf(Screen.DETAIL, Screen.PLAYER) && mutable.value.detail?.title?.source == ContentSource.HIANIME)
    internal var watchingSession = WatchingSession(SystemClock::elapsedRealtime)
    internal fun recordPlaybackInteraction() {
        if (mutable.value.screen != Screen.PLAYER) return
        watchingSession.interact()
        publishWatchingPrompt()
    }
    internal fun watchPlayback(token: Long, playing: Boolean, foreground: Boolean) {
        if (token != watchingSession.token || mutable.value.screen != Screen.PLAYER) return
        watchingSession.playback(token, playing, foreground)
        publishWatchingPrompt()
    }
    private fun publishWatchingPrompt() {
        val seconds = watchingSession.responseSeconds
        if (seconds == 0) { back(); return }
        if (seconds != mutable.value.stillWatchingSeconds) update { it.copy(stillWatchingSeconds = seconds) }
    }
    internal fun playbackEnded(token: Long) {
        val state = mutable.value
        if (state.screen != Screen.PLAYER) return
        watchingSession.complete(token, state.episode != null)
        if (state.profile?.autoplay != false && state.detail?.next(state.episode) != null)
            watchingSession.requireConfirmation(token)
        publishWatchingPrompt()
    }
    internal fun autoplayNext(token: Long, episode: Episode, creditsPosition: Long? = null, duration: Long = 0,
        skippedCreditsId: String? = null): Boolean {
        val state = mutable.value
        if (token != watchingSession.token || state.screen != Screen.PLAYER ||
            state.profile?.autoplay == false || state.failure != null || state.animeChoice != null || state.animeAutoplayBlocked || state.detail?.next(state.episode)?.key != episode.key) return false
        if (creditsPosition != null) {
            if (!creditsAutoplayAllowed(token) || state.segments?.items?.any {
                    it.type == SegmentType.CREDITS && it.endMs <= duration && (it.contains(creditsPosition) ||
                        it.id == skippedCreditsId && action(it) == SkipAction.AUTO && creditsPosition >= it.endMs)
                } != true) return false
            finishAtCredits(token, duration)
        } else if (!watchingSession.finished) return false
        if (watchingSession.requireConfirmation(token)) { publishWatchingPrompt(); return false }
        startPlayback(episode, resume = true, automatic = true)
        return true
    }
    internal fun creditsAutoplayAllowed(token: Long) = !watchingSession.confirmationDueAtCredits(token)
    private fun finishAtCredits(token: Long, duration: Long) {
        if (duration <= 0) return
        mutable.value.playbackIdentity?.let { saveProgress(it, duration, duration) }
        watchingSession.complete(token, episode = true)
    }
    internal fun playNext(token: Long, episode: Episode, duration: Long): Boolean {
        val state = mutable.value
        if (token != watchingSession.token || watchingSession.pending || state.screen != Screen.PLAYER ||
            state.failure != null || state.animeChoice != null || state.detail?.next(state.episode)?.key != episode.key) return false
        finishAtCredits(token, duration)
        startPlayback(episode, resume = true, automatic = false, version = state.version)
        return true
    }
    internal fun continueWatching() {
        if (!watchingSession.pending || mutable.value.screen != Screen.PLAYER) return
        val next = nextEpisode() ?: run { back(); return }
        watchingSession.reset()
        startPlayback(next, resume = true, automatic = true)
    }
    init { viewModelScope.launch { while (true) { updates.check(); delay(6L * 60 * 60 * 1000) } } }
    private fun update(transform: (TvState) -> TvState) { mutable.value = transform(mutable.value) }
    private fun load(block: suspend CoroutineScope.() -> Unit) {
        work?.cancel()
        update { it.copy(loading = true, failure = null) }
        work = viewModelScope.launch {
            try { block(); ensureActive(); update { it.copy(loading = false) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fail((error as? SiteException)?.failure ?: Failure.CHANGED) }
        }
    }
    private fun fail(failure: Failure) {
        val operation = mutable.value.screen.name
        update { it.copy(loading = false, failure = failure, failureOperation = operation,
            account = when {
                isAnime -> it.account
                failure == Failure.SIGNED_OUT -> Account(false)
                failure == Failure.EXPIRED -> Account(AccountStatus.EXPIRED)
                failure == Failure.CAPACITY && !it.account.signedIn -> Account(false)
                operation == "ACCOUNT" && failure in listOf(Failure.NETWORK, Failure.UNAVAILABLE, Failure.CHANGED) -> Account(AccountStatus.UNAVAILABLE)
                else -> it.account
            }) }
        if (failure == Failure.CHANGED) { updates.resetReport(); checkUpdates(true) }
    }
    fun checkUpdates(structural: Boolean = false) { viewModelScope.launch {
        updates.check(if (structural) fingerprint() else null, retryInstallation = !structural)
    } }
    fun fingerprint() = (if (isAnime) "hianime." else "") + mutable.value.failureOperation.lowercase() + ".response-v1"
    fun report() { viewModelScope.launch { updates.report(mutable.value.failureOperation, fingerprint()) } }
    fun installUpdate() { if (mutable.value.screen != Screen.PLAYER) viewModelScope.launch { updates.install() } }
    override fun onCleared() { animeChoiceJob?.cancel(); anime.close(); aniSkip.close(); catalogJob?.cancel(); segmentsJob?.cancel(); pages.close(); updates.close(); client.close(); super.onCleared() }
    private fun enter(id: String) {
        val profile = profiles.all().firstOrNull { it.id == id } ?: return
        watchingSession.reset()
        cancelAnimeChoice()
        work?.cancel(); catalogJob?.cancel(); stopPairing(); pendingPlayback = null
        library = LibraryStore(getApplication(), id)
        update { TvState(screen = Screen.HOME, profiles = profiles.all(), profile = profile,
            favorites = library.favorites(), viewing = library.viewing(), account = it.account, accountChecked = it.accountChecked, screenFit = it.screenFit) }
        refresh()
    }
    fun selectProfile(id: String) = enter(id)
    /** Locked profiles stay on the chooser until the PIN matches. */
    fun unlockProfile(id: String, pin: String): Boolean {
        val profile = profiles.all().firstOrNull { it.id == id } ?: return false
        if (profile.locked && !profiles.unlock(id, pin)) return false
        enter(id); return true
    }
    fun saveProfile(id: String?, name: String, avatar: Int) {
        val list = profiles.save(id, name, avatar)
        update { it.copy(profiles = list, profile = list.firstOrNull { p -> p.id == it.profile?.id }) }
    }
    fun setProfilePin(id: String, pin: String?) {
        update { it.copy(profiles = profiles.pin(id, pin)) }
    }
    fun setAutoplay(enabled: Boolean) {
        val id = mutable.value.profile?.id ?: return
        val list = profiles.autoplay(id, enabled)
        update { it.copy(profiles = list, profile = list.first { p -> p.id == id }) }
    }
    /** The episode after the current one in watch order, or null for films and finales. */
    fun nextEpisode(): Episode? = mutable.value.detail?.next(mutable.value.episode)
    fun setBuffering(larger: Boolean) {
        val id = mutable.value.profile?.id ?: return
        val list = profiles.buffering(id, larger)
        update { it.copy(profiles = list, profile = list.first { p -> p.id == id }) }
    }

    // --- Skip segments --------------------------------------------------------

    /** Community data needs an IMDb id; without one only viewer-marked segments apply. */
    fun imdbId(): String? {
        val title = mutable.value.detail?.title ?: return null
        // Anime numbering and audio cuts are not verified IntroDB mappings.
        if (title.source == ContentSource.HIANIME) return null
        return skipStore.imdbId(title)?.takeIf { it.isNotBlank() } ?: title.imdb.takeIf { it.isNotBlank() }
    }

    fun setImdbId(imdbId: String?) {
        val title = mutable.value.detail?.title ?: return
        skipStore.setImdbId(title, imdbId)
        // A corrected id invalidates what was fetched for the old one.
        loadSegments()
    }

    /** Viewer-marked segments always win, then community data fills the gaps. */
    fun effectiveSegments(): Segments? {
        val detail = mutable.value.detail ?: return null
        val marked = skipStore.segments(detail.title, mutable.value.episode, mutable.value.version)
        return Segments(imdbId().orEmpty(), mergeSegments(communitySegments, marked))
    }

    fun playerDuration(token: Long, duration: Long) {
        if (token != watchingSession.token || duration <= 0 || duration == segmentsDuration) return
        segmentsDuration = duration
        if (isAnime) loadSegments()
    }
    fun animeMapping(): AnimeMapping? = mutable.value.detail?.title?.let {
        skipStore.animeMapping(it, mutable.value.episode, mutable.value.stream?.animeMapping)
    }
    fun setAnimeMapping(mal: String, episode: String) {
        val title = mutable.value.detail?.title ?: return
        skipStore.setAnimeMapping(title, mutable.value.episode, mal, episode)
        update { it.copy(skipRevision = it.skipRevision + 1) }
        loadSegments()
    }
    fun loadSegments() {
        val detail = mutable.value.detail ?: return
        val title = detail.title
        val episode = mutable.value.episode
        segmentsJob?.cancel()
        val request = ++segmentsRequest
        val token = watchingSession.token
        communitySegments = emptyList()
        // Marked segments are local and instant; show them before any network work.
        update { it.copy(segments = effectiveSegments(), segmentsLoading = false, segmentsUnavailable = false) }
        val imdbId = imdbId()
        val mapping = if (isAnime) animeMapping() else null
        if (isAnime && (mapping == null || segmentsDuration <= 0) || !isAnime && imdbId == null) return
        val duration = segmentsDuration
        segmentsJob = viewModelScope.launch {
            update { it.copy(segmentsLoading = true) }
            val fetched = if (title.source == ContentSource.HIANIME) aniSkip.segments(mapping!!, duration)
            else if (title.isSeries) {
                val current = episode ?: return@launch
                introDb.segments(imdbId!!, current.season, current.number)?.items
            } else introDb.segments(imdbId!!)?.items
            ensureActive()
            if (request != segmentsRequest || token != watchingSession.token || mutable.value.screen != Screen.PLAYER) return@launch
            // A failed lookup keeps whatever is already shown; skipping is best-effort.
            communitySegments = fetched.orEmpty()
            update { it.copy(segments = effectiveSegments(), segmentsLoading = false, segmentsUnavailable = fetched == null) }
        }
    }

    fun markSegment(type: SegmentType, startMs: Long, endMs: Long) {
        val detail = mutable.value.detail ?: return
        val start = startMs.coerceAtLeast(0)
        val segment = SkipSegment(type, start, endMs.coerceAtLeast(start + 1000))
        skipStore.setSegment(detail.title, mutable.value.episode, segment, mutable.value.version)
        update { it.copy(segments = effectiveSegments()) }
    }

    fun clearMarked(type: SegmentType) {
        val detail = mutable.value.detail ?: return
        val remaining = skipStore.segments(detail.title, mutable.value.episode, mutable.value.version).filterNot { it.type == type }
        skipStore.setSegments(detail.title, mutable.value.episode, remaining, mutable.value.version)
        update { it.copy(segments = effectiveSegments()) }
    }

    fun markedSegments(): List<SkipSegment> {
        val detail = mutable.value.detail ?: return emptyList()
        return skipStore.segments(detail.title, mutable.value.episode, mutable.value.version)
    }
    fun customTypes() = skipStore.customTypes()
    fun saveCustomType(label: String, id: String? = null): CustomSegmentType {
        val type = skipStore.saveCustomType(label, id)
        update { it.copy(skipRevision = it.skipRevision + 1) }
        return type
    }
    fun action(region: SkipSegment): SkipAction {
        val profile = mutable.value.profile?.id ?: return region.action ?: SkipAction.ASK
        return effectiveSkipAction(region, skipStore.action(profile, region.typeId, mutable.value.detail?.title?.key),
            skipStore.action(profile, region.typeId))
    }
    fun actionPreference(typeId: String, show: Boolean): SkipAction? {
        val profile = mutable.value.profile?.id ?: return null
        return skipStore.action(profile, typeId, if (show) mutable.value.detail?.title?.key else null)
    }
    fun setActionPreference(typeId: String, action: SkipAction?, show: Boolean) {
        val profile = mutable.value.profile?.id ?: return
        skipStore.setAction(profile, typeId, action, if (show) mutable.value.detail?.title?.key else null)
        update { it.copy(skipRevision = it.skipRevision + 1) }
    }
    fun saveRegion(region: SkipSegment, duration: Long) {
        require(region.startMs >= 0 && region.endMs > region.startMs && region.endMs <= duration)
        val s = mutable.value
        val title = s.detail?.title ?: return
        skipStore.saveRegion(title, s.episode, region.copy(source = SegmentSource.LOCAL), s.version)
        update { it.copy(segments = effectiveSegments()) }
    }
    fun deleteRegion(id: String) {
        val s = mutable.value
        val title = s.detail?.title ?: return
        skipStore.setSegments(title, s.episode, markedSegments().filterNot { it.id == id }, s.version)
        update { it.copy(segments = effectiveSegments()) }
    }

    /** Contributing is available only when the build supplied an IntroDB key. */
    val canSubmit get() = introDbConfigured && !isAnime

    /** Contributes viewer-marked segments. Returns false when a key or IMDb id is missing. */
    fun submitSegments(): Boolean {
        val detail = mutable.value.detail ?: return false
        val title = detail.title
        val episode = mutable.value.episode
        val key = introDbKey().takeIf { it.isNotEmpty() } ?: return false
        val imdbId = imdbId() ?: return false
        if (title.isSeries && episode == null) return false
        val marked = skipStore.segments(title, episode).filter { it.type.contributable }
        if (marked.isEmpty()) return false
        viewModelScope.launch {
            marked.forEach { segment ->
                val body = if (title.isSeries)
                    IntroDbClient.submitBody(segment, imdbId, episode!!.season, episode.number)
                    else IntroDbClient.submitBody(segment, imdbId)
                runCatching { introDb.submit(body, key) }
            }
            // Re-read so the result reflects the service, not just what was marked.
            loadSegments()
        }
        return true
    }
    fun deleteProfile(id: String) {
        val list = profiles.delete(id)
        if (mutable.value.profile?.id == id) {
            watchingSession.reset()
            cancelAnimeChoice()
            work?.cancel(); catalogJob?.cancel(); stopPairing(); pendingPlayback = null
            library = LibraryStore(getApplication(), list.first().id)
            update { TvState(screen = Screen.PROFILES, profiles = list, account = it.account, accountChecked = it.accountChecked, screenFit = it.screenFit) }
        } else update { it.copy(profiles = list) }
    }
    fun setQuality(height: Int) {
        val id = mutable.value.profile?.id ?: return
        val list = profiles.quality(id, height)
        update { it.copy(profiles = list, profile = list.first { p -> p.id == id }) }
    }
    fun signInForPlayback() {
        if (isAnime) return
        mutable.value.detail?.let { pendingPlayback = it to mutable.value.episode }
        openAccountSettings()
    }
    private fun resumePendingPlayback() {
        val pending = pendingPlayback ?: return
        pendingPlayback = null
        update { it.copy(detail = pending.first) }
        play(pending.second)
    }
    fun openAccountSettings() { navigateTo(Screen.ACCOUNT, SettingsEntry.ACCOUNT) }
    fun navigate(screen: Screen) { navigateTo(screen, SettingsEntry.ROOT) }
    private fun navigateTo(screen: Screen, settingsEntry: SettingsEntry) {
        watchingSession.reset()
        cancelAnimeChoice()
        if (screen != Screen.ACCOUNT) pendingPlayback = null
        work?.cancel(); catalogJob?.cancel(); stopPairing()
        update { it.copy(screen = screen, settingsEntry = settingsEntry, failure = null, titles = emptyList(), page = 1, hasMore = true,
            settingsNavigationId = it.settingsNavigationId + if (screen == Screen.ACCOUNT) 1 else 0,
            detail = null, stream = null, loading = false, appending = false, appendFailure = null, focusedKey = null,
            segments = null, segmentsLoading = false, stillWatchingSeconds = null, version = null, animeAutoplayBlocked = false) }
        refresh()
    }
    fun refresh() {
        when (mutable.value.screen) {
            Screen.PROFILES -> update { it.copy(profiles = profiles.all(), loading = false) }
            Screen.HOME -> load {
                val home = client.home(); ensureActive()
                update { it.copy(shelves = home, viewing = library.viewing()) }
                try { val account = client.account(); ensureActive(); update { it.copy(account = account, accountChecked = true) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { update { it.copy(accountChecked = false, account = Account(AccountStatus.UNAVAILABLE)) } }
                try {
                    val hero = client.detail(home.first().titles.first()).title
                    ensureActive()
                    update { it.copy(shelves = home.mapIndexed { index, shelf ->
                        if (index == 0) shelf.copy(titles = listOf(hero) + shelf.titles.drop(1)) else shelf
                    }) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The catalogue remains usable without enriched hero artwork. */ }
            }
            Screen.MOVIES, Screen.SHOWS, Screen.ANIME -> browse(false)
            Screen.SEARCH -> search(mutable.value.query, false)
            Screen.LIST -> update { it.copy(favorites = library.favorites(), viewing = library.viewing(), loading = false) }
            Screen.ACCOUNT -> {
                update { it.copy(account = Account(AccountStatus.CHECKING)) }
                load { val account = client.account(); ensureActive(); update { it.copy(account = account, accountChecked = true) } }
            }
            Screen.DETAIL -> mutable.value.detail?.title?.let(::open)
            Screen.PLAYER -> {
                val state = mutable.value
                startPlayback(state.episode, true, false, state.version)
            }
        }
    }
    fun setScreenFit(fit: ScreenFit) { fitStore.save(fit); update { it.copy(screenFit = fit) } }
    fun warmCatalog(index: Int) {
        val current = pages
        viewModelScope.launch(Dispatchers.IO) { runCatching { current.warm(index) } }
    }
    fun browse(more: Boolean) = catalog(more)
    fun sort(value: String) {
        catalogJob?.cancel()
        update { it.copy(sort = value, titles = emptyList(), page = 1) }
        catalog(false)
    }
    fun search(query: String, more: Boolean = false) {
        if (!more) update { it.copy(query = query.take(64)) }
        catalog(more)
    }
    fun searchAnime(query: String) {
        update { it.copy(animeQuery = query.take(64)) }
        catalog(false)
    }
    private fun catalog(more: Boolean) {
        val before = mutable.value
        if (before.screen !in listOf(Screen.MOVIES, Screen.SHOWS, Screen.SEARCH, Screen.ANIME)) return
        if (more && (before.loading || before.appending || !before.hasMore)) return
        val query = if (before.screen == Screen.ANIME) before.animeQuery.trim() else before.query.trim()
        val key = listOf(before.profile?.id, before.screen, before.sort, query).joinToString("|")
        if (!more) {
            work?.cancel(); catalogJob?.cancel()
            pages.close(); pages = CatalogPages(catalogRoot)
        }
        val storage = pages
        if (before.screen == Screen.SEARCH && before.query.isBlank()) {
            update { it.copy(titles = emptyList(), loading = false, appending = false, hasMore = false, failure = null, appendFailure = null, catalogKey = key) }
            return
        }
        val page = if (more) before.page + 1 else 1
        update { it.copy(loading = !more, appending = more, failure = null, appendFailure = null, catalogKey = key,
            titles = if (more) it.titles else emptyList()) }
        catalogJob = viewModelScope.launch {
            try {
                if (!more && before.screen in listOf(Screen.SEARCH, Screen.ANIME)) delay(350)
                val result = if (before.screen == Screen.ANIME) anime.browse(query, page)
                    else if (before.screen == Screen.SEARCH) client.search(query, page)
                    else client.browse(before.screen == Screen.SHOWS, page, before.sort)
                ensureActive()
                val list = withContext(Dispatchers.IO) { storage.append(result) }
                ensureActive()
                if (mutable.value.catalogKey != key || mutable.value.screen != before.screen) return@launch
                update { it.copy(titles = list, page = page, loading = false, appending = false,
                    hasMore = result.isNotEmpty() && (!more || list.size > before.titles.size)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (mutable.value.catalogKey != key || mutable.value.screen != before.screen) return@launch
                val failure = (error as? SiteException)?.failure ?: Failure.NETWORK
                if (more) update { it.copy(appending = false, appendFailure = failure) } else fail(failure)
            }
        }
    }
    fun open(title: Title) = openFrom(title, title.key)
    fun openFrom(title: Title, location: String, playMovie: Boolean = false) {
        watchingSession.reset()
        cancelAnimeChoice()
        pendingPlayback = null
        val previous = mutable.value
        if (previous.screen != Screen.DETAIL) origin = previous.screen
        catalogJob?.cancel()
        stopPairing()
        update { it.copy(screen = Screen.DETAIL, appending = false, detail = Detail(title, emptyList()),
            focusedKey = if (previous.screen == Screen.DETAIL) previous.focusedKey else location, stream = null, stillWatchingSeconds = null,
            version = null, episode = null, segments = null, animeAutoplayBlocked = false) }
        load {
            val detail = if (title.source == ContentSource.HIANIME) anime.detail(title) else client.detail(title)
            ensureActive(); update { it.copy(detail = detail) }
            if (playMovie) requestAnimeChoice(null, true, false)
        }
    }
    fun back(): Boolean {
        if (mutable.value.animeChoice != null) { cancelAnimeChoice(); return true }
        return when (mutable.value.screen) {
            Screen.PLAYER -> {
                work?.cancel(); segmentsJob?.cancel()
                watchingSession.reset()
                update { it.copy(screen = Screen.DETAIL, stream = null, failure = null, loading = false,
                    viewing = library.viewing(), segmentsLoading = false, stillWatchingSeconds = null) }; true
            }
            Screen.DETAIL -> {
                work?.cancel()
                update { it.copy(screen = origin, failure = null, loading = false, viewing = library.viewing()) }; true
            }
            Screen.HOME, Screen.PROFILES -> false
            Screen.ACCOUNT -> {
                val pending = pendingPlayback
                if (pending == null) navigate(Screen.HOME)
                else {
                    work?.cancel(); catalogJob?.cancel(); stopPairing(); pendingPlayback = null
                    update { it.copy(screen = Screen.DETAIL, detail = pending.first, episode = pending.second,
                        loading = false, failure = null) }
                }
                true
            }
            else -> { navigate(Screen.HOME); true }
        }
    }
    fun play(episode: Episode?, resume: Boolean = true) {
        if (watchingSession.pending) return
        if (isAnime) { requestAnimeChoice(episode, resume, false); return }
        startPlayback(episode, resume, automatic = false)
    }
    private fun startPlayback(episode: Episode?, resume: Boolean, automatic: Boolean, version: AnimeVersion? = null) {
        val title = mutable.value.detail?.title ?: return
        if (title.source == ContentSource.HIANIME && version == null) {
            requestAnimeChoice(episode, resume, automatic, if (automatic) mutable.value.version else null)
            return
        }
        watchingSession.start(automatic)
        segmentsDuration = 0
        val previous = library.viewing().firstOrNull { it.key == playbackKey(title, episode, version) }
        val position = if (resume && previous != null && (previous.duration <= 0 || previous.position < previous.duration - 30000)) previous.position else 0
        update { it.copy(screen = Screen.PLAYER, episode = episode, stream = null, resumePosition = position, stillWatchingSeconds = null,
            version = version, animeChoice = null, segments = null, animeAutoplayBlocked = false,
            playbackIdentity = it.profile?.let { profile -> PlaybackIdentity(profile.id, title, episode, version) }) }
        loadSegments()
        load {
            val stream = if (title.source == ContentSource.HIANIME)
                anime.playback(episode ?: mutable.value.detail!!.ordered.first(), version!!)
                else client.playback(title, episode)
            ensureActive(); update { it.copy(stream = stream) }
        }
    }
    fun cancelAnimeChoice() {
        animeChoiceRequest++; animeChoiceJob?.cancel(); animeChoiceJob = null
        update { it.copy(animeChoice = null, animeAutoplayBlocked = it.animeAutoplayBlocked ||
            (it.animeChoice != null && (it.animeChoice.automatic || watchingSession.finished))) }
    }
    fun retryAnimeChoice() {
        val choice = mutable.value.animeChoice ?: return
        requestAnimeChoice(choice.episode, choice.resume, choice.automatic, choice.preferred)
    }
    private fun requestAnimeChoice(episode: Episode?, resume: Boolean, automatic: Boolean, preferred: AnimeVersion? = null) {
        val detail = mutable.value.detail ?: return
        if (detail.title.source != ContentSource.HIANIME) return
        cancelAnimeChoice()
        val request = animeChoiceRequest
        val target = episode ?: detail.ordered.firstOrNull()
        val choice = AnimeChoice(episode, resume, automatic, preferred = preferred)
        update { it.copy(animeChoice = choice) }
        animeChoiceJob = viewModelScope.launch {
            try {
                val available = target?.let { anime.servers(it).keys } ?: emptySet()
                ensureActive()
                if (request != animeChoiceRequest) return@launch
                update { it.copy(animeChoice = choice.copy(loading = false, available = available,
                    missing = preferred?.takeIf { version -> version !in available })) }
                if (preferred != null && preferred in available) chooseAnimeVersion(preferred)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (request == animeChoiceRequest) update { it.copy(animeChoice = choice.copy(loading = false,
                    failure = (error as? SiteException)?.failure ?: Failure.CHANGED)) }
            }
        }
    }
    fun chooseAnimeVersion(version: AnimeVersion) {
        val choice = mutable.value.animeChoice ?: return
        if (choice.loading || choice.failure != null || version !in choice.available) return
        update { it.copy(animeChoice = null) }
        startPlayback(choice.episode, choice.resume, choice.automatic, version)
    }
    fun playTitle(title: Title) {
        if (title.source == ContentSource.HIANIME) { openFrom(title, "hero", !title.isSeries); return }
        if (title.isSeries) {
            openFrom(title, "hero")
            return
        }
        watchingSession.start()
        pendingPlayback = null
        val previous = mutable.value
        if (previous.screen != Screen.DETAIL) origin = previous.screen
        catalogJob?.cancel(); stopPairing()
        update { it.copy(screen = Screen.DETAIL, detail = Detail(title, emptyList()), focusedKey = "hero",
            stream = null, episode = null, resumePosition = 0, failure = null, stillWatchingSeconds = null) }
        load {
            val detail = client.detail(title)
            ensureActive()
            val history = library.viewing()
            val episode: Episode? = null
            val viewing = history.firstOrNull { it.title.key == title.key && it.episode?.key == episode?.key }
            val position = if (viewing != null && (viewing.duration <= 0 || viewing.position < viewing.duration - 30_000)) viewing.position else 0
            update { it.copy(screen = Screen.PLAYER, detail = detail, episode = episode, stream = null, resumePosition = position,
                playbackIdentity = it.profile?.let { profile -> PlaybackIdentity(profile.id, title, episode) }) }
            loadSegments()
            val stream = client.playback(title, episode)
            ensureActive()
            update { it.copy(stream = stream) }
        }
    }
    fun toggle(title: Title) { update { it.copy(favorites = library.toggle(title)) } }
    fun saveProgress(identity: PlaybackIdentity, position: Long, duration: Long) {
        if (profiles.all().none { it.id == identity.profileId }) return
        LibraryStore(getApplication(), identity.profileId).save(Viewing(identity.title, identity.episode,
            position.coerceAtLeast(0), duration.coerceAtLeast(0), identity.version))
    }
    fun playerFailure(failure: Failure) = fail(failure)
    fun login(email: String, password: String) = load {
        val account = client.login(email.trim(), password); ensureActive(); update { it.copy(account = account, accountChecked = true, pair = null) }
        resumePendingPlayback()
    }
    fun startPairing() {
        stopPairing()
        update { it.copy(pairing = true, failure = null) }
        pairing = viewModelScope.launch {
            try {
                val code = client.pair(); ensureActive(); update { it.copy(pair = code) }
                val deadline = System.currentTimeMillis() + 180000
                while (System.currentTimeMillis() < deadline) {
                    delay(2000)
                    when (client.poll(code.code)) {
                        "pending" -> Unit
                        "done" -> {
                            val account = client.account(); ensureActive()
                            if (!account.signedIn) throw SiteException(Failure.VIP)
                            update { it.copy(account = account, accountChecked = true, pair = null, pairing = false) }
                            resumePendingPlayback(); return@launch
                        }
                        "expired" -> throw SiteException(Failure.EXPIRED)
                        "blocked" -> throw SiteException(Failure.BLOCKED)
                        else -> throw SiteException(Failure.CHANGED)
                    }
                }
                throw SiteException(Failure.EXPIRED)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { update { it.copy(pairing = false, pair = null, failure = (error as? SiteException)?.failure ?: Failure.CHANGED) } }
        }
    }
    fun stopPairing() { pairing?.cancel(); update { it.copy(pairing = false, pair = null) } }
    fun clearSession() {
        pendingPlayback = null
        work?.cancel(); stopPairing(); client.clearSession()
        update { it.copy(account = Account(false), accountChecked = true, failure = null) }
    }
    fun clearLibrary() { library.clear(); update { it.copy(favorites = emptyList(), viewing = emptyList()) } }
}
