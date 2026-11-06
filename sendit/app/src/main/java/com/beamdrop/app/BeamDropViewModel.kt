package com.beamdrop.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beamdrop.app.data.ConnectSession
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.FileStatus
import com.beamdrop.app.data.HistoryEntry
import com.beamdrop.app.data.PeerDevice
import com.beamdrop.app.data.ReceiveOffer
import com.beamdrop.app.data.RemoteFile
import com.beamdrop.app.data.SelectedFile
import com.beamdrop.app.data.SettingsStore
import com.beamdrop.app.data.TransferFileProgress
import com.beamdrop.app.data.TransferStatus
import com.beamdrop.app.data.TransferUi
import com.beamdrop.app.net.LocalHttpServer
import com.beamdrop.app.net.NsdHelper
import com.beamdrop.app.net.ReceiveClient
import com.beamdrop.app.net.TransferCancelledException
import com.beamdrop.app.util.Files
import com.beamdrop.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.beamdrop.app.api.BeamDropApiClient
import com.beamdrop.app.api.CloudShareFile
import com.beamdrop.app.api.CreatedShare
import com.beamdrop.app.api.ShareOptions
import com.beamdrop.app.api.Contact
import com.beamdrop.app.api.FileRequest
import com.beamdrop.app.api.ContactGroup
import com.beamdrop.app.api.SupportMessage
import com.beamdrop.app.api.ShareSummary
import com.beamdrop.app.i18n.I18n
import com.beamdrop.app.net.PasswordRequiredException
import com.beamdrop.app.util.MediaShrinker
import com.beamdrop.app.data.CloudPhase
import com.beamdrop.app.data.RemoteManifest
import com.beamdrop.app.data.CloudShareUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BeamDropViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settings = SettingsStore(context)
    private val nsd = NsdHelper(context)
    val apiClient = BeamDropApiClient(context, settings)
    // Our own server gets the account token (so senders see who downloaded); other links don't.
    private val receiveClient = ReceiveClient(
        authFor = { url -> settings.authToken?.takeIf { url.startsWith(apiClient.baseUrl, ignoreCase = true) } },
        toGallery = { settings.saveToGallery },
        deviceId = settings.installId
    )

    // ---------------- End-to-end encryption ----------------

    private val deviceKeys: com.beamdrop.app.util.E2e.DeviceKeys by lazy {
        settings.e2eKeys ?: com.beamdrop.app.util.E2e.newDeviceKeys().also { settings.e2eKeys = it }
    }
    @Volatile private var keyRegistered = false

    /** Tells the server this phone's public key (once per app start) so others can send it encrypted files. */
    fun registerDeviceKey() {
        if (keyRegistered || !_isLoggedIn.value) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { apiClient.registerKey(deviceKeys.publicB64, _deviceName.value) }.onSuccess { keyRegistered = true }
        }
    }

    // ---------------- Plans ----------------

    private val _plans = MutableStateFlow<com.beamdrop.app.api.PlansInfo?>(null)
    val plans: StateFlow<com.beamdrop.app.api.PlansInfo?> = _plans.asStateFlow()

    fun refreshPlans() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch { runCatching { apiClient.plans() }.onSuccess { _plans.value = it } }
    }

    fun redeemCode(code: String, onResult: (String?, String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.redeem(code) }
                .onSuccess { plan -> refreshPlans(); refreshMyShares(); onResult(plan, null) }
                .onFailure { onResult(null, apiClient.friendlyError(it)) }
        }
    }

    // ---------------- Hidden vault ----------------

    val vault = com.beamdrop.app.util.Vault(context)
    private val _vaultItems = MutableStateFlow<List<com.beamdrop.app.util.VaultItem>>(emptyList())
    val vaultItems: StateFlow<List<com.beamdrop.app.util.VaultItem>> = _vaultItems.asStateFlow()

    fun refreshVault() { viewModelScope.launch(Dispatchers.IO) { _vaultItems.value = vault.list() } }

    /**
     * Copies files into the vault and removes them from the phone's Downloads/Gallery.
     * Returns what Android wants the user to confirm (see [deleteReceivedFiles]) through [onConfirm].
     */
    fun moveToVault(files: List<com.beamdrop.app.data.SavedFile>, onDone: (Int, android.content.IntentSender?) -> Unit) {
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) {
                files.filter { vault.add(Uri.parse(it.uri), it.name, it.mimeType) != null }
            }
            _vaultItems.value = withContext(Dispatchers.IO) { vault.list() }
            val ask = if (moved.isNotEmpty()) deleteReceivedFiles(moved) else null
            onDone(moved.size, ask)
        }
    }

    /** "Add files" inside the vault: a copy goes in, the original stays where it was. */
    fun moveToVaultCopyOnly(uri: Uri, name: String, mime: String) {
        viewModelScope.launch(Dispatchers.IO) { vault.add(uri, name, mime); _vaultItems.value = vault.list() }
    }

    fun deleteFromVault(item: com.beamdrop.app.util.VaultItem) {
        viewModelScope.launch(Dispatchers.IO) { vault.delete(item); _vaultItems.value = vault.list() }
    }

    fun exportFromVault(item: com.beamdrop.app.util.VaultItem, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { vault.export(item) != null }
            if (ok) withContext(Dispatchers.IO) { vault.delete(item); _vaultItems.value = vault.list() }
            onDone(ok)
        }
    }

    // ---------------- Place-locked shares ----------------

    private val _receiveNeedsLocation = MutableStateFlow<String?>(null)
    /** Set (with the server's message) when a share opens only near a place. */
    val receiveNeedsLocation: StateFlow<String?> = _receiveNeedsLocation.asStateFlow()
    private var lastReceiveRaw: String? = null
    private var lastReceivePassword: String? = null

    /** Where am I? For "Only opens near here" and for opening such shares. */
    suspend fun currentLocation(): android.location.Location? = com.beamdrop.app.util.Locator.current(context)

    fun retryWithLocation(onError: (String) -> Unit) {
        val raw = lastReceiveRaw ?: return
        _isFetchingOffer.value = true
        viewModelScope.launch {
            val loc = currentLocation()
            if (loc == null) {
                _isFetchingOffer.value = false
                onError("Couldn't find where you are. Turn on Location and try again outside or near a window.")
                return@launch
            }
            receiveClient.location = "${loc.latitude},${loc.longitude}"
            openReceive(raw, lastReceivePassword)
        }
    }
    private val hotspotHelper = com.beamdrop.app.net.Hotspot(context)

    private val _hotspot = MutableStateFlow<com.beamdrop.app.net.HotspotInfo?>(null)
    /** Set while this phone runs its own Wi-Fi for a nearby send. */
    val hotspot: StateFlow<com.beamdrop.app.net.HotspotInfo?> = _hotspot.asStateFlow()

    private val _saveToGallery = MutableStateFlow(settings.saveToGallery)
    val saveToGallery: StateFlow<Boolean> = _saveToGallery.asStateFlow()
    fun setSaveToGallery(value: Boolean) { settings.saveToGallery = value; _saveToGallery.value = value }

    private val _tipsSeen = MutableStateFlow(settings.tipsSeen)
    val tipsSeen: StateFlow<Boolean> = _tipsSeen.asStateFlow()
    fun hideTips() { settings.tipsSeen = true; _tipsSeen.value = true }

    /** Sending to people / my devices starts encrypted (Settings switch). */
    private val _encryptByDefault = MutableStateFlow(settings.encryptByDefault)
    val encryptByDefaultFlow: StateFlow<Boolean> = _encryptByDefault.asStateFlow()
    val encryptByDefault: Boolean get() = _encryptByDefault.value
    fun setEncryptByDefault(value: Boolean) { settings.encryptByDefault = value; _encryptByDefault.value = value }
    private val secureRandom = java.security.SecureRandom()

    private val _isLoggedIn = MutableStateFlow(settings.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userEmail = MutableStateFlow(settings.userEmail)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _userName = MutableStateFlow(settings.userName)
    val userDisplayName: StateFlow<String?> = _userName.asStateFlow()

    private val _serverUrl = MutableStateFlow(settings.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _cloudShare = MutableStateFlow<CloudShareUi?>(null)
    val cloudShare: StateFlow<CloudShareUi?> = _cloudShare.asStateFlow()

    private val _preferAnywhere = MutableStateFlow(settings.preferAnywhere)
    val preferAnywhere: StateFlow<Boolean> = _preferAnywhere.asStateFlow()

    private var cloudJob: Job? = null
    private var cloudCreated: CreatedShare? = null

    private val _shareOptions = MutableStateFlow(ShareOptions())
    val shareOptions: StateFlow<ShareOptions> = _shareOptions.asStateFlow()

    private val _sendSmaller = MutableStateFlow(settings.sendSmaller)
    val sendSmaller: StateFlow<Boolean> = _sendSmaller.asStateFlow()

    private val _language = MutableStateFlow(settings.language)
    val language: StateFlow<String> = _language.asStateFlow()

    private val _myShares = MutableStateFlow<List<ShareSummary>>(emptyList())
    val myShares: StateFlow<List<ShareSummary>> = _myShares.asStateFlow()
    private val _mySharesLoading = MutableStateFlow(false)
    val mySharesLoading: StateFlow<Boolean> = _mySharesLoading.asStateFlow()
    /** used / limit of my storage on the server (bytes). */
    private val _storage = MutableStateFlow<Pair<Long, Long>?>(null)
    val storage: StateFlow<Pair<Long, Long>?> = _storage.asStateFlow()
    private val _mySharesError = MutableStateFlow<String?>(null)
    val mySharesError: StateFlow<String?> = _mySharesError.asStateFlow()

    private val _inbox = MutableStateFlow<List<ShareSummary>>(emptyList())
    val inbox: StateFlow<List<ShareSummary>> = _inbox.asStateFlow()

    private val _pendingOpenUrl = MutableStateFlow<String?>(null)
    val pendingOpenUrl: StateFlow<String?> = _pendingOpenUrl.asStateFlow()

    /** Message from the Sendit admin shown at the top of Home (empty = none). */
    private val _update = MutableStateFlow<com.beamdrop.app.api.UpdateInfo?>(null)
    /** Set when the server says a newer app exists (or is required). */
    val update: StateFlow<com.beamdrop.app.api.UpdateInfo?> = _update.asStateFlow()
    fun dismissUpdate() { _update.update { if (it?.required == true) it else null } }

    fun reportShare(code: String, reason: String, details: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.report(code, reason, details) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    private val _announcement = MutableStateFlow("")
    val announcement: StateFlow<String> = _announcement.asStateFlow()

    fun refreshAppConfig() {
        viewModelScope.launch {
            runCatching { apiClient.appConfig() }.onSuccess { c ->
                _announcement.value = c.announcement
                val mine = BuildConfig.VERSION_CODE
                _update.value = when {
                    c.minVersion > mine -> com.beamdrop.app.api.UpdateInfo(true, c.updateUrl, c.updateNote)
                    c.latestVersion > mine && c.updateUrl.isNotBlank() -> com.beamdrop.app.api.UpdateInfo(false, c.updateUrl, c.updateNote)
                    else -> null
                }
            }
        }
    }

    private var inboxWatcher: Job? = null

    /**
     * While Sendit's process is alive (open, or recently used in the background), check every
     * 30 seconds whether someone sent me files, and notify right away.
     */
    fun startInboxWatcher() {
        registerDeviceKey()
        if (inboxWatcher?.isActive == true) return
        inboxWatcher = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                if (_isLoggedIn.value) {
                    if (ticks % 10 == 0) {
                        refreshAppConfig()
                        runCatching { _contacts.value = apiClient.contacts() }
                        runCatching { _groups.value = apiClient.groups() }
                    }
                    refreshInbox()
                    checkNotices()
                    if (ticks % 2 == 0) checkBroadcasts()
                }
                ticks++
                delay(30_000)
            }
        }
    }

    /** The share being opened needs a password (shown on the Receive screen). */
    private val _receiveNeedsPassword = MutableStateFlow(false)
    val receiveNeedsPassword: StateFlow<Boolean> = _receiveNeedsPassword.asStateFlow()

    init {
        I18n.lang = I18n.resolve(settings.language)
    }

    fun updateServerUrl(url: String) {
        settings.serverUrl = url
        _serverUrl.value = settings.serverUrl
    }

    fun login(email: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = apiClient.login(email, pass)
            if (res.success) {
                settings.authToken = res.token
                settings.userEmail = res.userEmail
                settings.userName = res.userName
                _isLoggedIn.value = true
                _userEmail.value = res.userEmail
                _userName.value = res.userName
                keyRegistered = false
                registerDeviceKey()
                onResult(true, null)
            } else {
                onResult(false, res.error ?: "Login failed")
            }
        }
    }

    fun signUp(name: String, email: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = apiClient.signup(name, email, pass)
            if (res.success) {
                settings.authToken = res.token
                settings.userEmail = res.userEmail
                settings.userName = res.userName
                _isLoggedIn.value = true
                _userEmail.value = res.userEmail
                _userName.value = res.userName
                keyRegistered = false
                registerDeviceKey()
                onResult(true, null)
            } else {
                onResult(false, res.error ?: "Sign up failed")
            }
        }
    }

    fun logout() {
        settings.logout()
        _inbox.value = emptyList()
        _myShares.value = emptyList()
        _storage.value = null
        _contacts.value = emptyList()
        _requests.value = emptyList()
        _groups.value = emptyList()
        _support.value = emptyList()
        _isLoggedIn.value = false
        _userEmail.value = null
        _userName.value = null
    }

    // ---------------------------------------------------------------------
    // Forgot password / delete account
    // ---------------------------------------------------------------------

    /** Returns Result(true) when the code was emailed, Result(false) when the admin has it. */
    suspend fun forgotPassword(email: String): Result<Boolean> =
        runCatching { apiClient.forgotPassword(email) }.recoverCatching { throw Exception(apiClient.friendlyError(it)) }

    suspend fun resetPassword(email: String, code: String, password: String): Result<Unit> =
        runCatching { apiClient.resetPassword(email, code, password) }.recoverCatching { throw Exception(apiClient.friendlyError(it)) }

    fun deleteAccount(password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.deleteAccount(password) }
                .onSuccess {
                    logout()
                    onResult(null)
                }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    /** "Write a text": the note becomes a small .txt file in the send list. */
    fun addTextNote(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "notes").apply { mkdirs() }
            val taken = _selectedFiles.value.map { it.name }.toSet()
            var n = 1
            var name = "Text.txt"
            while (name in taken) { n++; name = "Text ($n).txt" }
            val file = java.io.File(dir, "${System.currentTimeMillis()}-$n.txt").apply { writeText(clean) }
            val entry = SelectedFile(
                uri = Uri.fromFile(file), name = name, size = file.length(),
                mimeType = "text/plain", lastModified = System.currentTimeMillis()
            )
            synchronized(this@BeamDropViewModel) { _selectedFiles.value = _selectedFiles.value + entry }
        }
    }

    /** Gives a chosen file a new name (the extension is kept if the user drops it). */
    fun renameFile(uri: Uri, newName: String) {
        _selectedFiles.update { files ->
            files.map { f ->
                if (f.uri != uri) return@map f
                val ext = f.name.substringAfterLast('.', "")
                val folder = f.name.substringBeforeLast('/', "")
                var clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
                if (clean.isEmpty()) return@map f
                if (ext.isNotEmpty() && !clean.contains('.')) clean = "$clean.$ext"
                f.copy(name = if (folder.isNotEmpty()) "$folder/$clean" else clean)
            }
        }
    }

    /** A new file in the cache for the camera to write into (photo or video). */
    fun newCameraFile(video: Boolean): Uri {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, if (video) "VID_$stamp.mp4" else "IMG_$stamp.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** The camera finished: add what it made (nothing if the user backed out). */
    fun addCameraFile(uri: Uri, video: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: return@launch
            val file = java.io.File(java.io.File(context.cacheDir, "camera"), name)
            if (!file.exists() || file.length() == 0L) { file.delete(); return@launch }
            val entry = SelectedFile(
                uri = uri, name = name, size = file.length(),
                mimeType = if (video) "video/mp4" else "image/jpeg", lastModified = System.currentTimeMillis()
            )
            synchronized(this@BeamDropViewModel) {
                if (_selectedFiles.value.none { it.uri == uri }) _selectedFiles.value = _selectedFiles.value + entry
            }
        }
    }

    /** Scanned someone's "My Sendit" QR: add them as a contact. */
    fun addContactFromQr(text: String, onResult: (String?) -> Unit) {
        val email = Uri.parse(text).getQueryParameter("e")?.trim().orEmpty()
        if (!email.contains('@')) { onResult("That QR code isn't a Sendit contact."); return }
        if (!_isLoggedIn.value) { onResult("Sign in to add contacts."); return }
        if (email.equals(_userEmail.value, ignoreCase = true)) { onResult("That's your own QR code."); return }
        addContact(email, onResult)
    }

    /**
     * Deletes received files from the phone and from History/My files. Returns the files Android
     * wants the user to confirm (Android 11+, files Sendit no longer owns), as an IntentSender.
     */
    fun deleteReceivedFiles(files: List<com.beamdrop.app.data.SavedFile>): android.content.IntentSender? {
        val needAsk = mutableListOf<Uri>()
        val gone = mutableSetOf<String>()
        for (f in files) {
            val uri = Uri.parse(f.uri)
            val ok = runCatching { context.contentResolver.delete(uri, null, null) >= 0 }.getOrElse { false }
            if (ok) gone += f.uri else needAsk += uri
        }
        forgetFiles(gone)
        if (needAsk.isEmpty()) return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { android.provider.MediaStore.createDeleteRequest(context.contentResolver, needAsk).intentSender }.getOrNull()
        } else {
            forgetFiles(needAsk.map { it.toString() }.toSet())
            null
        }
    }

    /** Removes files from History/My files (after they were deleted). */
    fun forgetFiles(uris: Set<String>) {
        if (uris.isEmpty()) return
        val updated = _history.value.map { e -> e.copy(files = e.files.filterNot { it.uri in uris }) }
        _history.value = updated
        viewModelScope.launch(Dispatchers.IO) { settings.saveHistory(updated) }
    }

    // ---- No Wi-Fi: this phone makes its own hotspot ----

    fun startHotspot(onError: (String) -> Unit) {
        val session = _connectSession.value ?: return
        hotspotHelper.start(
            onReady = { info ->
                val srv = server ?: return@start
                _hotspot.value = info
                _connectSession.value = session.copy(localIp = info.ip, url = "http://${info.ip}:${srv.port}/s/${_connectSession.value?.token ?: session.token}")
            },
            onError = { onError(it) }
        )
    }

    fun stopHotspot() {
        hotspotHelper.stop()
        if (_hotspot.value != null) {
            _hotspot.value = null
            _connectSession.value?.let { s ->
                val ip = NetworkUtils.localIpAddress()
                _connectSession.value = s.copy(localIp = ip, url = "http://${ip ?: "127.0.0.1"}:${s.port}/s/${s.token}")
            }
        }
    }

    /** Scanned a hotspot QR: join that Wi-Fi, then open the files like a normal nearby link. */
    fun joinHotspotFromQr(text: String) {
        val u = Uri.parse(text)
        val ssid = u.getQueryParameter("s").orEmpty()
        val url = u.getQueryParameter("u").orEmpty()
        if (ssid.isEmpty() || url.isEmpty()) { _receiveError.value = "That QR code can't be read."; return }
        clearReceive()
        _isFetchingOffer.value = true
        hotspotHelper.join(
            ssid, u.getQueryParameter("p").orEmpty(), u.getQueryParameter("t") == "3",
            // Give the new Wi-Fi a moment to route before asking for the file list.
            onJoined = { viewModelScope.launch { delay(1200); openReceive(url) } },
            onError = { msg -> _isFetchingOffer.value = false; _receiveError.value = msg }
        )
    }

    /** Adds every file inside a folder the user picked, keeping sub-folders in the names. */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val found = Files.listFolder(context, treeUri)
            if (found.isEmpty()) return@launch
            synchronized(this@BeamDropViewModel) {
                val known = _selectedFiles.value.mapTo(mutableSetOf()) { it.uri.toString() }
                _selectedFiles.value = _selectedFiles.value + found.filter { known.add(it.uri.toString()) }
                _connectError.value = null
            }
        }
    }

    // ---------------------------------------------------------------------
    // Contacts, recent people, auto-receive
    // ---------------------------------------------------------------------

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _recentPeople = MutableStateFlow(settings.recentPeople)
    /** (email, name) of people I sent to lately, newest first. */
    val recentPeople: StateFlow<List<Pair<String, String>>> = _recentPeople.asStateFlow()

    fun refreshContacts() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch { runCatching { apiClient.contacts() }.onSuccess { _contacts.value = it } }
    }

    fun addContact(email: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.addContact(email) }
                .onSuccess { c ->
                    _contacts.update { list -> (list.filterNot { it.id == c.id } + c).sortedBy { it.name.lowercase() } }
                    onResult(null)
                }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    fun setAutoAccept(contact: Contact, on: Boolean) {
        _contacts.update { list -> list.map { if (it.id == contact.id) it.copy(autoAccept = on) else it } }
        viewModelScope.launch { runCatching { apiClient.setAutoAccept(contact.id, on) }.onFailure { refreshContacts() } }
    }

    fun removeContact(contact: Contact) {
        _contacts.update { list -> list.filterNot { it.id == contact.id } }
        viewModelScope.launch { runCatching { apiClient.removeContact(contact.id) } }
    }

    private fun rememberRecipient(email: String, name: String) {
        val updated = (listOf(email.lowercase() to name) + settings.recentPeople.filterNot { it.first.equals(email, true) }).take(8)
        settings.recentPeople = updated
        _recentPeople.value = updated
    }

    /** Files from a contact marked "Receive automatically" download without asking. */
    private fun autoReceive(item: ShareSummary) {
        if (_transfer.value?.status in ACTIVE_STATES || item.viewOnce || item.geoRadius > 0) return
        viewModelScope.launch {
            runCatching {
                val normalized = receiveClient.normalizeBaseUrl(item.url) ?: return@launch
                // Same unlock as opening by hand, so encrypted files are saved decrypted.
                startReceive(unlock(normalized, receiveClient.fetchManifest(normalized, _deviceName.value), null, null))
            }.onFailure {
                InboxWorker.notify(context, item.code, item.sender, item.fileCount, item.totalSize, item.url, item.message)
            }
        }
    }

    /**
     * Turns a fetched manifest into something downloadable: for end-to-end encrypted shares the key
     * comes from the link (#k=) or is unlocked with this phone's key, and the real names/message are decrypted.
     */
    private fun unlock(baseUrl: String, raw: RemoteManifest, password: String?, linkKey: ByteArray?): ReceiveOffer {
        val e = raw.e2e ?: return ReceiveOffer(baseUrl, raw, password)
        val k = linkKey ?: e.wrappedKey?.let { w ->
            e.epk?.let { epk -> runCatching { com.beamdrop.app.util.E2e.unwrapKey(w, epk, deviceKeys) }.getOrNull() }
        } ?: throw com.beamdrop.app.net.FatalTransferException(
            "These files are end-to-end encrypted 🔒 Open the link or QR code the sender gave you (the code alone isn't enough)."
        )
        val (meta, message) = runCatching { com.beamdrop.app.util.E2e.openMeta(k, e.meta) }
            .getOrElse { throw com.beamdrop.app.net.FatalTransferException("The key doesn't fit these files. Ask the sender for the link again.") }
        val manifest = raw.copy(message = message, files = raw.files.mapIndexed { i, f ->
            meta.getOrNull(i)?.let { m -> f.copy(name = m.name, mimeType = m.mime) } ?: f
        })
        return ReceiveOffer(baseUrl, manifest, password, k)
    }

    // ---------------------------------------------------------------------
    // File requests ("send me files" links)
    // ---------------------------------------------------------------------

    private val _requests = MutableStateFlow<List<FileRequest>>(emptyList())
    val requests: StateFlow<List<FileRequest>> = _requests.asStateFlow()

    fun refreshRequests() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch { runCatching { apiClient.myRequests() }.onSuccess { _requests.value = it } }
    }

    fun createRequest(title: String, onResult: (FileRequest?, String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.createRequest(title) }
                .onSuccess { r -> _requests.update { listOf(r) + it }; onResult(r, null) }
                .onFailure { onResult(null, apiClient.friendlyError(it)) }
        }
    }

    fun deleteRequest(code: String) {
        _requests.update { list -> list.filterNot { it.code == code } }
        viewModelScope.launch { runCatching { apiClient.deleteRequest(code) } }
    }

    // ---------------------------------------------------------------------
    // Pause / resume an upload
    // ---------------------------------------------------------------------

    private val cloudPaused = AtomicBoolean(false)

    fun pauseCloudShare() {
        if (_cloudShare.value?.phase != CloudPhase.UPLOADING) return
        cloudPaused.set(true)
        apiClient.cancelUpload()
        _cloudShare.update { it?.copy(paused = true, speedBps = 0L) }
    }

    fun resumeCloudShare() {
        cloudPaused.set(false)
        _cloudShare.update { it?.copy(paused = false) }
    }

    // ---------------------------------------------------------------------
    // Appearance & app lock
    // ---------------------------------------------------------------------

    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<String> = _theme.asStateFlow()
    private val _accent = MutableStateFlow(settings.accent)
    val accent: StateFlow<String> = _accent.asStateFlow()
    private val _appLock = MutableStateFlow(settings.appLock)
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()

    fun setTheme(value: String) { settings.theme = value; _theme.value = value }
    fun setAccent(value: String) { settings.accent = value; _accent.value = value }
    fun setAppLock(value: Boolean) { settings.appLock = value; _appLock.value = value }

    // ---------------------------------------------------------------------
    // Messages from the Sendit admin & crash reports
    // ---------------------------------------------------------------------

    private suspend fun checkBroadcasts() {
        runCatching { apiClient.broadcasts(settings.lastBroadcastId) }.onSuccess { list ->
            list.sortedBy { it.id }.forEach { InboxWorker.notifyBroadcast(context, it) }
            list.maxOfOrNull { it.id }?.let { settings.lastBroadcastId = it }
        }
    }

    /** Sends a crash saved by [CrashReporter] last time the app closed unexpectedly. */
    fun sendPendingCrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = CrashReporter.pending(context) ?: return@launch
            runCatching {
                apiClient.reportCrash(report, BuildConfig.VERSION_NAME, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", android.os.Build.VERSION.RELEASE)
            }.onSuccess { CrashReporter.clear(context) }
        }
    }

    // ---------------------------------------------------------------------
    // Groups, notices, support, welcome tour
    // ---------------------------------------------------------------------

    private val _groups = MutableStateFlow<List<ContactGroup>>(emptyList())
    val groups: StateFlow<List<ContactGroup>> = _groups.asStateFlow()

    fun refreshGroups() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch { runCatching { apiClient.groups() }.onSuccess { _groups.value = it } }
    }

    fun saveGroup(id: Int?, name: String, emails: List<String>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.saveGroup(id, name, emails) }
                .onSuccess { _groups.value = it; onResult(null) }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    fun deleteGroup(id: Int) {
        _groups.update { list -> list.filterNot { it.id == id } }
        viewModelScope.launch { runCatching { apiClient.deleteGroup(id) } }
    }

    /** "Your files were downloaded", "expires soon", support replies → notifications. */
    private suspend fun checkNotices() {
        runCatching { apiClient.notices(settings.lastNoticeId) }.onSuccess { list ->
            list.forEach { n ->
                InboxWorker.notifyNotice(context, n)
                if (n.kind == "support") refreshSupportQuietly()
            }
            list.maxOfOrNull { it.id }?.let { settings.lastNoticeId = it }
        }
    }

    fun extendShare(code: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { apiClient.extendShare(code, 24) }
                .onSuccess { refreshMyShares(); onResult(null) }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    private val _support = MutableStateFlow<List<SupportMessage>>(emptyList())
    val support: StateFlow<List<SupportMessage>> = _support.asStateFlow()

    private suspend fun refreshSupportQuietly() {
        runCatching { apiClient.supportMessages() }.onSuccess { _support.value = it }
    }

    fun refreshSupport() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch { refreshSupportQuietly() }
    }

    fun sendSupport(body: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { apiClient.sendSupport(body) }
                .onSuccess { refreshSupportQuietly(); onResult(null) }
                .onFailure { onResult(apiClient.friendlyError(it)) }
        }
    }

    private val _onboarded = MutableStateFlow(settings.onboarded)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()
    fun finishOnboarding() { settings.onboarded = true; _onboarded.value = true }

    fun setPreferAnywhere(value: Boolean) {
        settings.preferAnywhere = value
        _preferAnywhere.value = value
    }

    /** Link a receiver opens for a typed code. */
    fun shareUrlForCode(code: String): String = apiClient.shareUrlForCode(code)

    /** Uploads the selected files to the Sendit server so anyone, on any network, can download them with a code. */
    fun startCloudShare() {
        val phase = _cloudShare.value?.phase
        if (phase == CloudPhase.PREPARING || phase == CloudPhase.UPLOADING || phase == CloudPhase.READY) return
        val original = _connectSession.value?.files ?: _selectedFiles.value.filter { it.selected }
        if (original.isEmpty()) return
        val options = _shareOptions.value
        val shrink = _sendSmaller.value && original.any { MediaShrinker.canShrink(it) }

        cloudJob?.cancel()
        cloudPaused.set(false)
        _cloudShare.value = CloudShareUi(
            phase = if (shrink) CloudPhase.PREPARING else CloudPhase.UPLOADING,
            files = original.mapIndexed { i, f -> TransferFileProgress(id = i, name = f.name, size = f.size, mimeType = f.mimeType) },
            oneTime = options.oneTime,
            hasPassword = options.password.isNotEmpty(),
            ttlHours = options.ttlHours,
            encrypted = options.encrypt
        )
        TransferService.start(context)
        cloudJob = viewModelScope.launch {
            var created: CreatedShare? = null
            var speedTicker: Job? = null
            try {
                // 1) "Send smaller": lighter copies of photos and videos (only used when actually smaller).
                val files = if (shrink) original.map { MediaShrinker.shrink(context, it) } else original
                _cloudShare.update {
                    it?.copy(
                        phase = CloudPhase.UPLOADING,
                        files = files.mapIndexed { i, f -> TransferFileProgress(id = i, name = f.name, size = f.size, mimeType = f.mimeType) }
                    )
                }
                speedTicker = launch {
                    var lastBytes = 0L
                    var lastTime = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(700)
                        val cur = _cloudShare.value ?: break
                        val now = SystemClock.elapsedRealtime()
                        val instant = (cur.bytesDone - lastBytes).coerceAtLeast(0L) * 1000L / (now - lastTime).coerceAtLeast(1L)
                        lastBytes = cur.bytesDone
                        lastTime = now
                        _cloudShare.update { v -> v?.copy(speedBps = if (v.speedBps > 0) (v.speedBps * 2 + instant) / 3 else instant) }
                    }
                }

                // 2) Upload. Each file continues where it stopped if the connection drops.
                val sender = _userName.value?.takeIf { it.isNotBlank() } ?: _deviceName.value
                // End-to-end: names and message go inside an encrypted blob; the server sees "encrypted-1.bin".
                val shareKey = if (options.encrypt) com.beamdrop.app.util.E2e.newShareKey() else null
                val meta = shareKey?.let { k ->
                    com.beamdrop.app.util.E2e.sealMeta(k, files.map { com.beamdrop.app.util.E2e.MetaFile(it.name, it.mimeType, it.size) }, options.message.trim())
                }
                val sendFiles = files.mapIndexed { i, f ->
                    if (shareKey != null) CloudShareFile("encrypted-${i + 1}.bin", com.beamdrop.app.util.E2e.encryptedSize(f.size), f.mimeType)
                    else CloudShareFile(f.name, f.size, f.mimeType)
                }
                val share = apiClient.createShare(sender, sendFiles, options, meta)
                created = share
                cloudCreated = share
                if (shareKey != null && share.recipientKeys.isNotEmpty()) {
                    val eph = com.beamdrop.app.util.E2e.newEphemeral()
                    apiClient.postShareKeys(share, eph.publicB64, share.recipientKeys.map { it to com.beamdrop.app.util.E2e.wrapKey(shareKey, eph, it.publicKey) })
                }
                _cloudShare.update { it?.copy(recipientName = share.recipientName) }
                files.forEachIndexed { index, file ->
                    setCloudFile(index) { it.copy(status = FileStatus.ACTIVE) }
                    var lastEmit = 0L
                    val sendSize = sendFiles[index].size
                    apiClient.uploadShareFile(
                        share, index, file.uri, sendSize,
                        onProgress = { sentRaw ->
                            val sent = if (sendSize > 0) sentRaw * file.size / sendSize else sentRaw
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit >= 120L || sent >= file.size) {
                                lastEmit = now
                                setCloudFile(index) { it.copy(bytesDone = sent.coerceAtMost(file.size)) }
                            }
                        },
                        onWaiting = { waiting -> _cloudShare.update { it?.copy(waiting = waiting) } },
                        isPaused = { cloudPaused.get() },
                        openAt = shareKey?.let { k ->
                            { offset: Long ->
                                com.beamdrop.app.util.E2e.encryptingStream(
                                    { context.contentResolver.openInputStream(file.uri) ?: throw java.io.IOException("Could not open the file.") },
                                    k, index, offset
                                )
                            }
                        }
                    )
                    setCloudFile(index) { it.copy(status = FileStatus.DONE, bytesDone = file.size) }
                }
                apiClient.completeShare(share)
                speedTicker.cancel()
                _cloudShare.update {
                    it?.copy(
                        phase = CloudPhase.READY, code = share.code,
                        // The key rides in the link's #part, which never reaches the server.
                        url = shareKey?.let { k -> share.url + "#k=" + com.beamdrop.app.util.E2e.keyToLink(k) } ?: share.url,
                        expiresAt = share.expiresAt, speedBps = 0L, waiting = false, releaseAt = share.releaseAt
                    )
                }
                addHistory(
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        fileName = files.first().name,
                        fileCount = files.size,
                        totalBytes = files.sumOf { it.size },
                        direction = Direction.SEND,
                        peerName = share.recipientName?.let { "$it · code ${share.code}" } ?: "Anyone · code ${share.code}",
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                if (options.toEmail.isNotBlank() && options.groupId == null) rememberRecipient(options.toEmail.trim(), share.recipientName ?: options.toEmail)
                // Every option (message, password, one download, secret, place…) is for this share only.
                _shareOptions.value = ShareOptions()
                refreshMyShares()
            } catch (e: CancellationException) {
                created?.let { share -> withContext(NonCancellable) { apiClient.deleteShare(share) } }
                throw e
            } catch (t: Throwable) {
                speedTicker?.cancel()
                created?.let { apiClient.deleteShare(it) }
                cloudCreated = null
                _cloudShare.update { it?.copy(phase = CloudPhase.FAILED, error = apiClient.friendlyError(t), speedBps = 0L, waiting = false) }
            } finally {
                if (shrink) MediaShrinker.clear(context)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Share options, "My shares" and the inbox
    // ---------------------------------------------------------------------

    fun updateShareOptions(change: (ShareOptions) -> ShareOptions) {
        _shareOptions.update(change)
    }

    /** Checks that an email belongs to a Sendit user; returns their name. */
    suspend fun lookupRecipient(email: String): Result<String> =
        runCatching { apiClient.lookupUser(email) }.recoverCatching { throw Exception(apiClient.friendlyError(it)) }

    fun setSendSmaller(value: Boolean) {
        settings.sendSmaller = value
        _sendSmaller.value = value
    }

    fun setLanguage(value: String) {
        settings.language = value
        I18n.lang = I18n.resolve(value)
        _language.value = value
    }

    fun refreshMyShares() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch {
            _mySharesLoading.value = true
            runCatching { apiClient.myShares() }
                .onSuccess {
                    _myShares.value = it.shares
                    _storage.value = it.usedBytes to it.quotaBytes
                    _mySharesError.value = null
                }
                .onFailure { _mySharesError.value = apiClient.friendlyError(it) }
            _mySharesLoading.value = false
        }
    }

    fun deleteMyShare(code: String) {
        viewModelScope.launch {
            runCatching { apiClient.deleteMyShare(code) }
                .onSuccess {
                    _myShares.update { list -> list.filterNot { it.code == code } }
                    refreshMyShares()
                }
                .onFailure { _mySharesError.value = apiClient.friendlyError(it) }
        }
    }

    fun refreshInbox() {
        if (!_isLoggedIn.value) return
        viewModelScope.launch {
            runCatching { apiClient.inbox() }.onSuccess { list ->
                val opened = settings.openedInboxCodes
                val visible = list.filterNot { it.code in opened }
                _inbox.value = visible
                // Someone sent me files: tell me with a notification, once per share.
                val notified = settings.notifiedInboxCodes
                val fresh = visible.filterNot { it.code in notified }
                val trusted = _contacts.value.filter { it.autoAccept }.map { it.email.lowercase() }.toSet()
                fresh.forEach { item ->
                    if (!item.hasPassword && item.senderEmail?.lowercase() in trusted && _transfer.value?.status !in ACTIVE_STATES) {
                        autoReceive(item) // "Files received" notification comes from the transfer service
                    } else {
                        InboxWorker.notify(context, item.code, item.sender, item.fileCount, item.totalSize, item.url, item.message)
                    }
                }
                if (fresh.isNotEmpty()) settings.notifiedInboxCodes = notified + fresh.map { it.code }
            }
        }
    }

    /** Opens a file someone sent to me; it then leaves the inbox. */
    fun openInboxItem(item: ShareSummary) {
        // Stays in the inbox until the files are really downloaded (e.g. the user may go back
        // from the password screen); see markInboxDownloaded.
        openReceive(item.url)
    }

    private fun markInboxDownloaded(code: String) {
        if (code.isBlank()) return
        settings.openedInboxCodes = settings.openedInboxCodes + code
        _inbox.update { list -> list.filterNot { it.code == code } }
    }

    /** A notification asked to open a share; the UI navigates to it. */
    fun requestOpenFromNotification(url: String) {
        _pendingOpenUrl.value = url
    }

    fun consumePendingOpen(): String? = _pendingOpenUrl.value.also { _pendingOpenUrl.value = null }

    /** "send" / "receive" asked by the home-screen widget. */
    private val _pendingScreen = MutableStateFlow<String?>(null)
    val pendingScreen: StateFlow<String?> = _pendingScreen.asStateFlow()
    fun requestScreen(target: String) { _pendingScreen.value = target; if (target == "my_files") filesTabRequested = true }

    /** History should open on the "My files" tab (asked by the Home tile or the app shortcut). */
    private var filesTabRequested = false
    fun consumeFilesTab(): Boolean = filesTabRequested.also { filesTabRequested = false }
    fun consumePendingScreen(): String? = _pendingScreen.value.also { _pendingScreen.value = null }

    private fun setCloudFile(index: Int, change: (TransferFileProgress) -> TransferFileProgress) {
        _cloudShare.update { cur ->
            cur?.copy(files = cur.files.map { if (it.id == index) change(it) else it })
        }
    }

    fun retryCloudShare() {
        if (_cloudShare.value?.phase == CloudPhase.FAILED) {
            _cloudShare.value = null
            startCloudShare()
        }
    }

    /** Refreshes the download counter of a finished share. */
    suspend fun refreshCloudStatus() {
        val share = cloudCreated ?: return
        if (_cloudShare.value?.phase != CloudPhase.READY) return
        runCatching { apiClient.shareStatus(share) }.onSuccess { st ->
            _cloudShare.update { it?.copy(downloads = st.downloads) }
        }
    }

    /** Stops an upload in progress; with [deleteFromServer] also removes a finished share from the server. */
    fun cancelCloudShare(deleteFromServer: Boolean = true) {
        cloudPaused.set(false)
        val wasUploading = _cloudShare.value?.phase == CloudPhase.UPLOADING
        apiClient.cancelUpload()
        cloudJob?.cancel()
        cloudJob = null
        val share = cloudCreated
        cloudCreated = null
        _cloudShare.value = null
        if (share != null && (deleteFromServer || wasUploading)) {
            viewModelScope.launch { apiClient.deleteShare(share) }
        }
    }

    /** Leaves the send flow. A finished share stays online until it expires. */
    fun finishSendFlow() {
        cancelCloudShare(deleteFromServer = false)
        cancelSession()
        _selectedFiles.value = emptyList()
    }

    private fun addHistory(entry: HistoryEntry) {
        val updated = (listOf(entry) + _history.value).take(MAX_HISTORY)
        _history.value = updated
        viewModelScope.launch(Dispatchers.IO) { settings.saveHistory(updated) }
    }


    val deviceCode: String = settings.deviceCode

    private val _deviceName = MutableStateFlow(settings.deviceName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _history = MutableStateFlow(settings.loadHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _keepAwake = MutableStateFlow(settings.keepAwake)
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    val nearbyDevices: StateFlow<List<PeerDevice>> = nsd.peers

    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    private val _connectSession = MutableStateFlow<ConnectSession?>(null)
    val connectSession: StateFlow<ConnectSession?> = _connectSession.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private val _transfer = MutableStateFlow<TransferUi?>(null)
    val transfer: StateFlow<TransferUi?> = _transfer.asStateFlow()

    private val _receiveOffer = MutableStateFlow<ReceiveOffer?>(null)
    val receiveOffer: StateFlow<ReceiveOffer?> = _receiveOffer.asStateFlow()

    private val _receiveError = MutableStateFlow<String?>(null)
    val receiveError: StateFlow<String?> = _receiveError.asStateFlow()

    private val _isFetchingOffer = MutableStateFlow(false)
    val isFetchingOffer: StateFlow<Boolean> = _isFetchingOffer.asStateFlow()

    private val _shareRequestId = MutableStateFlow(0)
    val shareRequestId: StateFlow<Int> = _shareRequestId.asStateFlow()

    private var server: LocalHttpServer? = null
    private var serverCollectJob: Job? = null
    private var offerJob: Job? = null
    private var transferJob: Job? = null
    private var speedJob: Job? = null
    private var lastSpeedBytes = 0L
    private var lastSpeedTime = 0L
    private var lastReceiveUrl: String? = null
    private var completionScheduled = false
    private var discoveryRefs = 0
    private var discoveryStopJob: Job? = null

    private val cancelRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // File staging
    // ---------------------------------------------------------------------

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { addFilesInternal(uris) }
    }

    fun stageSharedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val added = addFilesInternal(uris)
            if (added > 0) {
                _shareRequestId.update { it + 1 }
            }
        }
    }

    @Synchronized
    private fun addFilesInternal(uris: List<Uri>): Int {
        val current = _selectedFiles.value
        val known = current.mapTo(mutableSetOf()) { it.uri.toString() }
        val added = mutableListOf<SelectedFile>()
        for (uri in uris) {
            if (!known.add(uri.toString())) continue
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            Files.query(context, uri)?.let { added += it }
        }
        if (added.isNotEmpty()) {
            _selectedFiles.value = current + added
            _connectError.value = null
        }
        return added.size
    }

    fun toggleFile(uri: Uri) {
        _selectedFiles.update { files ->
            files.map { if (it.uri == uri) it.copy(selected = !it.selected) else it }
        }
    }

    fun removeFile(uri: Uri) {
        _selectedFiles.update { files -> files.filterNot { it.uri == uri } }
    }

    fun clearFiles() {
        _selectedFiles.value = emptyList()
    }

    fun selectAll(selected: Boolean) {
        _selectedFiles.update { files -> files.map { it.copy(selected = selected) } }
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    fun startSend(): Boolean {
        val files = _selectedFiles.value.filter { it.selected }
        if (files.isEmpty()) {
            _connectError.value = "Choose at least one file to send."
            return false
        }
        if (_transfer.value?.direction == Direction.RECEIVE &&
            _transfer.value?.status in ACTIVE_STATES
        ) {
            _connectError.value = "Finish the current receive before starting a new send."
            return false
        }
        stopSharing()
        cancelCloudShare(deleteFromServer = false)
        speedJob?.cancel()
        completionScheduled = false
        cancelRequested.set(false)
        pauseRequested.set(false)
        _connectError.value = null

        return runCatching {
            val token = newToken()
            val srv = LocalHttpServer(
                context = context,
                files = files,
                initialSenderName = _deviceName.value,
                senderCode = deviceCode,
                initialToken = token
            )
            srv.start()
            server = srv
            val ip = NetworkUtils.localIpAddress()
            val host = ip ?: "127.0.0.1"
            val url = "http://$host:${srv.port}/s/$token"
            _connectSession.value = ConnectSession(
                url = url,
                token = token,
                port = srv.port,
                files = files,
                localIp = ip
            )
            _transfer.value = TransferUi(
                direction = Direction.SEND,
                peerName = "Waiting for receiver",
                status = TransferStatus.WAITING,
                files = files.mapIndexed { index, file ->
                    TransferFileProgress(
                        id = index,
                        name = file.name,
                        size = file.size,
                        mimeType = file.mimeType
                    )
                }
            )
            nsd.advertise(_deviceName.value, deviceCode, srv.port, token)
            bindServer(srv)
            startSpeedTicker()
            TransferService.start(context)
            true
        }.getOrElse { throwable ->
            _connectError.value = throwable.message ?: "Could not start a local hotspot session."
            stopSharing()
            false
        }
    }

    private fun bindServer(srv: LocalHttpServer) {
        serverCollectJob?.cancel()
        serverCollectJob = viewModelScope.launch {
            srv.state.collect { snapshot ->
                if (server !== srv) return@collect
                onServerSnapshot(snapshot)
            }
        }
    }

    private fun onServerSnapshot(snapshot: com.beamdrop.app.data.ServerSnapshot) {
        val current = _transfer.value ?: return
        if (current.direction != Direction.SEND) return
        if (current.status in TERMINAL) return

        val fileProgress = current.files.map { file ->
            val sent = snapshot.perFileBytes.getOrNull(file.id) ?: 0L
            val done = snapshot.completed || (file.size > 0L && sent >= file.size)
            val status = when {
                done -> FileStatus.DONE
                snapshot.activeFileId == file.id -> FileStatus.ACTIVE
                sent > 0L -> FileStatus.ACTIVE
                else -> FileStatus.QUEUED
            }
            file.copy(
                bytesDone = if (file.size > 0L) sent.coerceAtMost(file.size) else 0L,
                status = status
            )
        }

        val status = when {
            snapshot.completed -> TransferStatus.COMPLETE
            snapshot.paused -> TransferStatus.PAUSED
            snapshot.connected -> TransferStatus.ACTIVE
            else -> TransferStatus.WAITING
        }
        val peer = when {
            !snapshot.remoteName.isNullOrBlank() -> snapshot.remoteName!!
            snapshot.connected -> "Web browser"
            else -> current.peerName
        }

        val updated = current.copy(
            peerName = peer,
            status = status,
            files = fileProgress,
            speedBps = if (status == TransferStatus.ACTIVE) current.speedBps else 0L,
            finishedAt = if (status == TransferStatus.COMPLETE) {
                current.finishedAt ?: System.currentTimeMillis()
            } else {
                null
            }
        )
        _transfer.value = updated

        if (status == TransferStatus.COMPLETE) {
            recordHistoryOnce(updated, success = true)
            if (!completionScheduled) {
                completionScheduled = true
                viewModelScope.launch {
                    delay(2500)
                    if (_transfer.value?.status == TransferStatus.COMPLETE) stopSharing()
                }
            }
        }
    }

    fun regenerateToken() {
        val srv = server ?: return
        val session = _connectSession.value ?: return
        val token = newToken()
        srv.rotateToken(token)
        val host = session.localIp ?: "127.0.0.1"
        val url = "http://$host:${srv.port}/s/$token"
        _connectSession.value = session.copy(token = token, url = url)
        nsd.advertise(_deviceName.value, deviceCode, srv.port, token)
    }

    fun cancelSession() {
        val current = _transfer.value
        if (current != null && current.direction == Direction.SEND && current.status in ACTIVE_STATES) {
            cancelTransfer()
        } else {
            stopSharing()
            _transfer.value = null
        }
    }

    private fun stopSharing() {
        serverCollectJob?.cancel()
        serverCollectJob = null
        runCatching { server?.stop() }
        server = null
        nsd.stopAdvertising()
        _connectSession.value = null
        hotspotHelper.stop()
        _hotspot.value = null
    }

    // ---------------------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------------------

    fun openReceive(rawUrl: String, password: String? = null) {
        lastReceiveRaw = rawUrl
        lastReceivePassword = password
        val linkKey = Regex("[#&]k=([A-Za-z0-9_-]+)").find(rawUrl)?.groupValues?.get(1)?.let { com.beamdrop.app.util.E2e.keyFromLink(it) }
        val normalized = receiveClient.normalizeBaseUrl(rawUrl.substringBefore('#'))
        if (normalized == null) {
            _receiveOffer.value = null
            _isFetchingOffer.value = false
            _receiveError.value = "That does not look like a Sendit link."
            return
        }
        lastReceiveUrl = normalized
        offerJob?.cancel()
        receiveClient.cancel()
        _receiveOffer.value = null
        _receiveError.value = null
        _receiveNeedsPassword.value = false
        _receiveNeedsLocation.value = null
        _isFetchingOffer.value = true
        offerJob = viewModelScope.launch {
            try {
                val raw = receiveClient.fetchManifest(normalized, _deviceName.value, password)
                _receiveOffer.value = unlock(normalized, raw, password, linkKey)
            } catch (t: com.beamdrop.app.net.LocationRequiredException) {
                val far = { m: Int -> if (m < 1000) com.beamdrop.app.i18n.tr("%d m", m) else com.beamdrop.app.i18n.tr("%s km", "%.1f".format(m / 1000.0)) }
                _receiveNeedsLocation.value = if (t.distance >= 0) {
                    com.beamdrop.app.i18n.tr("📍 You're %s away. Get within %s of the place to open these files.", far(t.distance), far(t.radius))
                } else {
                    com.beamdrop.app.i18n.tr("📍 These files open only within %s of a place the sender picked.", far(t.radius))
                }
            } catch (t: PasswordRequiredException) {
                _receiveNeedsPassword.value = true
                _receiveError.value = if (password.isNullOrEmpty()) null else t.message
            } catch (t: Throwable) {
                _receiveError.value = friendlyMessage(t)
            } finally {
                _isFetchingOffer.value = false
            }
        }
    }

    fun retryOpenReceive() {
        val url = lastReceiveRaw ?: lastReceiveUrl ?: return
        openReceive(url)
    }

    /** The user typed the share's password on the Receive screen. */
    fun submitReceivePassword(password: String) {
        val url = lastReceiveRaw ?: lastReceiveUrl ?: return
        openReceive(url, password)
    }

    fun clearReceive() {
        offerJob?.cancel()
        offerJob = null
        receiveClient.cancel()
        _receiveOffer.value = null
        _receiveError.value = null
        _receiveNeedsPassword.value = false
        _isFetchingOffer.value = false
        lastReceiveUrl = null
        _receiveNeedsLocation.value = null
        receiveClient.location = null
        hotspotHelper.leave()
    }

    fun startReceive(offer: ReceiveOffer) {
        if (_transfer.value?.status in ACTIVE_STATES) return
        stopSharing()
        cancelRequested.set(false)
        pauseRequested.set(false)
        _receiveError.value = null

        val files = offer.manifest.files.sortedBy { it.id }.map { file ->
            TransferFileProgress(
                id = file.id,
                name = file.name,
                size = file.size,
                mimeType = file.mimeType
            )
        }
        _transfer.value = TransferUi(
            direction = Direction.RECEIVE,
            peerName = offer.manifest.sender,
            status = TransferStatus.ACTIVE,
            files = files,
            viaCloud = offer.manifest.cloud,
            viewOnce = offer.manifest.viewOnce,
            destructSeconds = offer.manifest.destructSeconds,
            encrypted = offer.key != null
        )
        startSpeedTicker()
        TransferService.start(context)

        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            try {
                receiveClient.downloadFiles(
                    context = context,
                    offer = offer,
                    onFileStart = { file, id -> onReceiveFileStart(file, id) },
                    onProgress = { id, bytes, total -> onReceiveProgress(id, bytes, total) },
                    onSaved = { id, uri ->
                        _transfer.update { cur ->
                            cur?.copy(files = cur.files.map { if (it.id == id) it.copy(savedUri = uri.toString(), status = FileStatus.DONE, bytesDone = it.size) else it })
                        }
                    },
                    onWaiting = { waiting -> _transfer.update { it?.copy(waiting = waiting) } },
                    isPaused = { pauseRequested.get() },
                    isCancelled = { cancelRequested.get() }
                )
                val done = (_transfer.value ?: return@launch).copy(
                    status = TransferStatus.COMPLETE,
                    files = (_transfer.value?.files ?: emptyList()).map {
                        it.copy(bytesDone = it.size, status = FileStatus.DONE)
                    },
                    speedBps = 0L,
                    finishedAt = System.currentTimeMillis()
                )
                _transfer.value = done
                recordHistoryOnce(done, success = true)
                if (offer.manifest.cloud) markInboxDownloaded(offer.manifest.code)
            } catch (_: TransferCancelledException) {
                receiveClient.clearPartials(context)
                setTerminal(TransferStatus.CANCELLED)
            } catch (t: Throwable) {
                if (cancelRequested.get()) {
                    receiveClient.clearPartials(context)
                    setTerminal(TransferStatus.CANCELLED)
                } else {
                    val failed = (_transfer.value ?: return@launch).copy(
                        status = TransferStatus.FAILED,
                        error = friendlyMessage(t),
                        speedBps = 0L,
                        finishedAt = System.currentTimeMillis()
                    )
                    _transfer.value = failed
                    recordHistoryOnce(failed, success = false)
                }
            }
        }
    }

    private fun onReceiveFileStart(file: RemoteFile, id: Int) {
        _transfer.update { current ->
            if (current == null || current.direction != Direction.RECEIVE) return@update current
            current.copy(
                status = if (current.status == TransferStatus.PAUSED) {
                    TransferStatus.PAUSED
                } else {
                    TransferStatus.ACTIVE
                },
                files = current.files.map { progress ->
                    when {
                        progress.id == id -> progress.copy(status = FileStatus.ACTIVE)
                        progress.status == FileStatus.ACTIVE -> progress.copy(
                            bytesDone = progress.size,
                            status = FileStatus.DONE
                        )
                        else -> progress
                    }
                }
            )
        }
    }

    private fun onReceiveProgress(fileId: Int, bytes: Long, total: Long) {
        _transfer.update { current ->
            if (current == null || current.direction != Direction.RECEIVE) return@update current
            current.copy(
                files = current.files.map { file ->
                    if (file.id != fileId) return@map file
                    val size = if (file.size > 0L) file.size else total.coerceAtLeast(0L)
                    val done = size > 0L && bytes >= size
                    file.copy(
                        size = size,
                        bytesDone = bytes.coerceAtMost(size),
                        status = if (done) FileStatus.DONE else FileStatus.ACTIVE
                    )
                }
            )
        }
    }

    fun retryReceive() {
        val offer = _receiveOffer.value ?: return
        startReceive(offer)
    }

    private fun setTerminal(status: TransferStatus) {
        val current = _transfer.value ?: return
        if (current.status in TERMINAL) return
        _transfer.value = current.copy(
            status = status,
            speedBps = 0L,
            finishedAt = System.currentTimeMillis(),
            historyRecorded = status == TransferStatus.CANCELLED || current.historyRecorded
        )
    }

    // ---------------------------------------------------------------------
    // Transfer controls
    // ---------------------------------------------------------------------

    fun togglePause() {
        val current = _transfer.value ?: return
        if (current.status != TransferStatus.ACTIVE && current.status != TransferStatus.PAUSED) return
        val pause = current.status != TransferStatus.PAUSED
        pauseRequested.set(pause)
        if (current.direction == Direction.SEND) {
            server?.setPaused(pause)
        }
        _transfer.value = current.copy(
            status = if (pause) TransferStatus.PAUSED else TransferStatus.ACTIVE,
            speedBps = 0L
        )
    }

    fun cancelTransfer() {
        val current = _transfer.value ?: return
        if (current.status !in ACTIVE_STATES) return
        cancelRequested.set(true)
        pauseRequested.set(false)
        if (current.direction == Direction.SEND) {
            stopSharing()
        } else {
            receiveClient.cancel()
        }
        _transfer.value = current.copy(
            status = TransferStatus.CANCELLED,
            speedBps = 0L,
            finishedAt = System.currentTimeMillis(),
            historyRecorded = true
        )
        transferJob?.cancel()
    }

    /** Called by the Done button / leaving a finished transfer. */
    fun finishTransfer() {
        val current = _transfer.value
        if (current?.direction == Direction.SEND) stopSharing()
        transferJob?.cancel()
        speedJob?.cancel()
        _transfer.value = null
        _selectedFiles.value = emptyList()
        _receiveOffer.value = null
        _receiveError.value = null
        lastReceiveUrl = null
        completionScheduled = false
        hotspotHelper.leave()
        receiveClient.location = null
        closeViewOnce()
    }

    /** View-once photos were looked at (or skipped): delete them for good. */
    fun closeViewOnce() {
        receiveClient.viewOnceDir(context).listFiles()?.forEach { it.delete() }
        _transfer.update { t -> if (t?.viewOnce == true) t.copy(files = t.files.map { it.copy(savedUri = null) }) else t }
    }

    // ---------------------------------------------------------------------
    // Speed sampling
    // ---------------------------------------------------------------------

    private fun startSpeedTicker() {
        speedJob?.cancel()
        lastSpeedBytes = _transfer.value?.bytesDone ?: 0L
        lastSpeedTime = SystemClock.elapsedRealtime()
        speedJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val current = _transfer.value ?: break
                if (current.status in TERMINAL) break
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastSpeedTime).coerceAtLeast(1L)
                val delta = (current.bytesDone - lastSpeedBytes).coerceAtLeast(0L)
                val instant = delta * 1000L / elapsed
                lastSpeedBytes = current.bytesDone
                lastSpeedTime = now
                if (current.status == TransferStatus.PAUSED) {
                    _transfer.update { value ->
                        value?.copy(speedBps = 0L) ?: value
                    }
                    continue
                }
                val smoothed = if (current.speedBps > 0L) {
                    (current.speedBps * 2L + instant) / 3L
                } else {
                    instant
                }
                _transfer.update { value ->
                    if (value == null || value.status in TERMINAL) {
                        value
                    } else {
                        value.copy(
                            speedBps = smoothed,
                            peakBps = maxOf(value.peakBps, instant, smoothed)
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // History & profile
    // ---------------------------------------------------------------------

    private fun recordHistoryOnce(transfer: TransferUi, success: Boolean) {
        if (transfer.historyRecorded) return
        val current = _transfer.value ?: transfer
        if (current.historyRecorded) return
        if (transfer.direction == Direction.SEND && transfer.status != TransferStatus.COMPLETE) return

        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            fileName = transfer.files.firstOrNull()?.name ?: "Files",
            fileCount = transfer.files.size,
            totalBytes = transfer.totalBytes,
            direction = transfer.direction,
            peerName = transfer.peerName.ifBlank { "Nearby device" },
            timestamp = System.currentTimeMillis(),
            success = success,
            files = if (transfer.viewOnce) emptyList() else transfer.files.mapNotNull { f ->
                f.savedUri?.let { com.beamdrop.app.data.SavedFile(f.name, it, f.mimeType, f.size) }
            }
        )
        _transfer.value = current.copy(historyRecorded = true)
        val updated = (listOf(entry) + _history.value).take(MAX_HISTORY)
        _history.value = updated
        viewModelScope.launch(Dispatchers.IO) { settings.saveHistory(updated) }
    }

    fun clearHistory() {
        _history.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { settings.clearHistory() }
    }

    fun updateDeviceName(name: String) {
        val cleaned = name.trim().take(60)
        if (cleaned.isBlank()) return
        _deviceName.value = cleaned
        settings.deviceName = cleaned
        val srv = server
        if (srv != null) {
            srv.updateSenderName(cleaned)
            nsd.advertise(cleaned, deviceCode, srv.port, srv.token)
        }
    }

    fun setKeepAwake(enabled: Boolean) {
        _keepAwake.value = enabled
        settings.keepAwake = enabled
    }

    // ---------------------------------------------------------------------
    // Discovery lifecycle
    // ---------------------------------------------------------------------

    fun acquireDiscovery() {
        discoveryStopJob?.cancel()
        discoveryStopJob = null
        if (discoveryRefs == 0) nsd.startDiscovery(deviceCode)
        discoveryRefs++
    }

    fun releaseDiscovery() {
        if (discoveryRefs > 0) discoveryRefs--
        if (discoveryRefs == 0) {
            // Tabs hand discovery between screens during navigation. Waiting a
            // moment avoids stopping and immediately restarting DNS-SD.
            discoveryStopJob?.cancel()
            discoveryStopJob = viewModelScope.launch {
                delay(1500)
                if (discoveryRefs == 0) nsd.stopDiscovery()
            }
        }
    }

    /** Restarts DNS-SD so the radar picks up devices that joined the network late. */
    fun refreshDiscovery() {
        if (discoveryRefs <= 0) return
        nsd.stopDiscovery()
        viewModelScope.launch {
            delay(650)
            if (discoveryRefs > 0) nsd.startDiscovery(deviceCode)
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return buildString {
            repeat(8) { append(alphabet[secureRandom.nextInt(alphabet.length)]) }
        }
    }

    private fun friendlyMessage(throwable: Throwable): String = when (throwable) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Could not reach the sender. Check your connection and try again."
        else -> throwable.message?.takeIf { it.isNotBlank() }
            ?: "Something went wrong during the transfer."
    }

    override fun onCleared() {
        cancelRequested.set(true)
        stopSharing()
        receiveClient.cancel()
        discoveryStopJob?.cancel()
        nsd.stopDiscovery()
        discoveryRefs = 0
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 200
        private val ACTIVE_STATES = setOf(
            TransferStatus.WAITING,
            TransferStatus.ACTIVE,
            TransferStatus.PAUSED
        )
        private val TERMINAL = setOf(
            TransferStatus.COMPLETE,
            TransferStatus.FAILED,
            TransferStatus.CANCELLED
        )
    }
}
