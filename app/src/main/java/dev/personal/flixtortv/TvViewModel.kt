package dev.personal.flixtortv

import android.app.Application
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

enum class Screen { PROFILES, HOME, MOVIES, SHOWS, SEARCH, LIST, ACCOUNT, DETAIL, PLAYER }
data class TvState(
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
)
class TvViewModel(app: Application) : AndroidViewModel(app) {
    val client = FlixtorClient(SessionStore(app))
    val updates = UpdateService(app)
    private val profiles = ProfileStore(app)
    private var library = LibraryStore(app)
    private val fitStore = ScreenFitStore(app)
    private val catalogRoot = java.io.File(app.filesDir, "catalog_pages").apply { deleteRecursively(); mkdirs() }
    private var pages = CatalogPages(catalogRoot)
    private var catalogJob: Job? = null
    private val mutable = MutableStateFlow(TvState(profiles = profiles.initialize(), screenFit = fitStore.read()))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var pairing: Job? = null
    private var origin = Screen.HOME
    private var pendingPlayback: Pair<Detail, Episode?>? = null
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
                failure == Failure.SIGNED_OUT -> Account(false)
                failure == Failure.EXPIRED -> Account(AccountStatus.EXPIRED)
                operation == "ACCOUNT" && failure in listOf(Failure.NETWORK, Failure.UNAVAILABLE, Failure.CHANGED) -> Account(AccountStatus.UNAVAILABLE)
                else -> it.account
            }) }
        if (failure == Failure.CHANGED) { updates.resetReport(); checkUpdates(true) }
    }
    fun checkUpdates(structural: Boolean = false) { viewModelScope.launch {
        updates.check(if (structural) fingerprint() else null)
    } }
    fun fingerprint() = mutable.value.failureOperation.lowercase() + ".response-v1"
    fun report() { viewModelScope.launch { updates.report(mutable.value.failureOperation, fingerprint()) } }
    fun installUpdate() { if (mutable.value.screen != Screen.PLAYER) viewModelScope.launch { updates.install() } }
    override fun onCleared() { catalogJob?.cancel(); pages.close(); updates.close(); client.close(); super.onCleared() }
    fun selectProfile(id: String) {
        val profile = profiles.all().firstOrNull { it.id == id } ?: return
        work?.cancel(); catalogJob?.cancel(); stopPairing(); pendingPlayback = null
        library = LibraryStore(getApplication(), id)
        update { TvState(screen = Screen.HOME, profiles = profiles.all(), profile = profile,
            favorites = library.favorites(), viewing = library.viewing(), account = it.account, accountChecked = it.accountChecked, screenFit = it.screenFit) }
        refresh()
    }
    fun saveProfile(id: String?, name: String, avatar: Int) {
        val list = profiles.save(id, name, avatar)
        update { it.copy(profiles = list, profile = list.firstOrNull { p -> p.id == it.profile?.id }) }
    }
    fun deleteProfile(id: String) {
        val list = profiles.delete(id)
        if (mutable.value.profile?.id == id) {
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
        mutable.value.detail?.let { pendingPlayback = it to mutable.value.episode }
        navigate(Screen.ACCOUNT)
    }
    private fun resumePendingPlayback() {
        val pending = pendingPlayback ?: return
        pendingPlayback = null
        update { it.copy(detail = pending.first) }
        play(pending.second)
    }
    fun navigate(screen: Screen) {
        if (screen != Screen.ACCOUNT) pendingPlayback = null
        work?.cancel(); catalogJob?.cancel(); stopPairing()
        update { it.copy(screen = screen, failure = null, titles = emptyList(), page = 1, hasMore = true,
            detail = null, stream = null, loading = false, appending = false, appendFailure = null, focusedKey = null) }
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
            Screen.MOVIES, Screen.SHOWS -> browse(false)
            Screen.SEARCH -> search(mutable.value.query, false)
            Screen.LIST -> update { it.copy(favorites = library.favorites(), viewing = library.viewing(), loading = false) }
            Screen.ACCOUNT -> {
                update { it.copy(account = Account(AccountStatus.CHECKING)) }
                load { val account = client.account(); ensureActive(); update { it.copy(account = account, accountChecked = true) } }
            }
            Screen.DETAIL -> mutable.value.detail?.title?.let(::open)
            Screen.PLAYER -> play(mutable.value.episode, true)
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
    private fun catalog(more: Boolean) {
        val before = mutable.value
        if (before.screen !in listOf(Screen.MOVIES, Screen.SHOWS, Screen.SEARCH)) return
        if (more && (before.loading || before.appending || !before.hasMore)) return
        val key = listOf(before.profile?.id, before.screen, before.sort, before.query.trim()).joinToString("|")
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
                if (!more && before.screen == Screen.SEARCH) delay(350)
                val result = if (before.screen == Screen.SEARCH) client.search(before.query.trim(), page)
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
    fun openFrom(title: Title, location: String) {
        pendingPlayback = null
        val previous = mutable.value
        if (previous.screen != Screen.DETAIL) origin = previous.screen
        catalogJob?.cancel()
        stopPairing()
        update { it.copy(screen = Screen.DETAIL, appending = false, detail = Detail(title, emptyList()),
            focusedKey = if (previous.screen == Screen.DETAIL) previous.focusedKey else location, stream = null) }
        load { val detail = client.detail(title); ensureActive(); update { it.copy(detail = detail) } }
    }
    fun back(): Boolean {
        return when (mutable.value.screen) {
            Screen.PLAYER -> {
                work?.cancel()
                update { it.copy(screen = Screen.DETAIL, stream = null, failure = null, loading = false, viewing = library.viewing()) }; true
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
        val title = mutable.value.detail?.title ?: return
        val previous = library.viewing().firstOrNull { it.title.key == title.key && it.episode?.key == episode?.key }
        val position = if (resume && previous != null && (previous.duration <= 0 || previous.position < previous.duration - 30000)) previous.position else 0
        update { it.copy(screen = Screen.PLAYER, episode = episode, stream = null, resumePosition = position,
            playbackIdentity = it.profile?.let { profile -> PlaybackIdentity(profile.id, title, episode) }) }
        load { val stream = client.playback(title, episode); ensureActive(); update { it.copy(stream = stream) } }
    }
    fun toggle(title: Title) { update { it.copy(favorites = library.toggle(title)) } }
    fun saveProgress(identity: PlaybackIdentity, position: Long, duration: Long) {
        if (profiles.all().none { it.id == identity.profileId }) return
        LibraryStore(getApplication(), identity.profileId).save(Viewing(identity.title, identity.episode,
            position.coerceAtLeast(0), duration.coerceAtLeast(0)))
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
