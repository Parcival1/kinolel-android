package org.jellyfin.mobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.ServerUser
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

class ApiClientController(
    private val appPreferences: AppPreferences,
    private val jellyfin: Jellyfin,
    private val apiClient: ApiClient,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
) {
    private val baseDeviceInfo: DeviceInfo
        get() = jellyfin.options.deviceInfo!!

    /**
     * Store server with [hostname] in the database.
     */
    suspend fun setupServer(hostname: String) {
        appPreferences.currentServerId = withContext(Dispatchers.IO) {
            serverDao.getServerByHostname(hostname)?.id ?: serverDao.insert(hostname)
        }
        apiClient.update(baseUrl = hostname)
    }

    suspend fun setupUser(serverId: Long, userId: UUID, accessToken: String) {
        appPreferences.currentUserId = withContext(Dispatchers.IO) {
            userDao.upsert(serverId, userId, accessToken)
        }
        // KinoLel (Funktionstest 16.10.2026, F2): Serveradresse hier erneut setzen. LibraryService ruft beim Start
        // loadSavedServerUser() auf und setzte sie vor der ersten Anmeldung auf null -> Player blieb schwarz
        // (MissingBaseUrlException), bis die App neu gestartet wurde.
        withContext(Dispatchers.IO) { serverDao.getServer(serverId) }?.let { server -> configureApiClientServer(server) }
        configureApiClientUser(userId, accessToken)
    }

    suspend fun loadSavedServer(): ServerEntity? {
        val server = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null
            serverDao.getServer(serverId)
        }
        configureApiClientServer(server)
        return server
    }

    suspend fun loadSavedUser(): UserEntity? = withContext(Dispatchers.IO) {
        val userId = appPreferences.currentUserId ?: return@withContext null
        userDao.getUser(userId)
    }

    suspend fun loadSavedServerUser(): ServerUser? {
        val serverUser = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null
            val userId = appPreferences.currentUserId ?: return@withContext null
            userDao.getServerUser(serverId, userId)
        }

        // KinoLel (F2): ohne gespeicherten Benutzer die gespeicherte Serveradresse behalten statt sie auf null zu setzen
        if (serverUser == null) {
            loadSavedServer()
            resetApiClientUser()
            return null
        }
        configureApiClientServer(serverUser.server)

        if (serverUser.user.accessToken != null) {
            configureApiClientUser(serverUser.user.userId, serverUser.user.accessToken)
        } else {
            resetApiClientUser()
        }

        return serverUser
    }

    suspend fun loadPreviouslyUsedServers(): List<ServerEntity> = withContext(Dispatchers.IO) {
        serverDao.getAllServers().filterNot { server ->
            server.id == appPreferences.currentServerId
        }
    }

    private fun configureApiClientServer(server: ServerEntity?) {
        apiClient.update(baseUrl = server?.hostname)
    }

    private fun configureApiClientUser(@Suppress("UNUSED_PARAMETER") userId: UUID, accessToken: String) {
        apiClient.update(
            accessToken = accessToken,
            // KinoLel (Funktionstest 16.10.2026, F1): KEINE Benutzer-ID mehr an die Geraete-ID haengen. Die Weboberflaeche
            // holt ihre Geraete-ID beim Start (vor der Anmeldung, also ohne Benutzer-ID) - der native Player meldete sich
            // dadurch als zweites Geraet an. Server-Befehle (Pause/Stopp, Streams-Grenze) landeten in der Web-Sitzung,
            // der Film lief aber in der anderen. Mit gleicher ID ist es eine Sitzung.
            deviceInfo = baseDeviceInfo,
        )
    }

    private fun resetApiClientUser() {
        apiClient.update(
            accessToken = null,
            deviceInfo = baseDeviceInfo,
        )
    }

    fun getApiClient(server: Long, user: Long): ApiClient {
        val serverUser = userDao.getServerUser(server, user) ?: error("Invalid server user combination (server=$server, user=$user)")

        return jellyfin.createApi(
            baseUrl = serverUser.server.hostname,
            accessToken = serverUser.user.accessToken,
            deviceInfo = baseDeviceInfo, // KinoLel (F1): gleiche Geraete-ID wie Weboberflaeche und Player
        )
    }
}
