package org.jellyfin.mobile.ui.screens.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.ui.utils.CenterRow
import org.koin.compose.koinInject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadStatus

@Composable
fun ConnectScreen(
    mainViewModel: MainViewModel,
    showExternalConnectionError: Boolean,
    oben: Boolean = true,
    activityEventHandler: ActivityEventHandler = koinInject(),
    downloadDao: DownloadDao = koinInject(),
) {
    // KinoLel: Anzahl fertiger Downloads (Offline-Knopf)
    val fertigeDownloads by remember(downloadDao) {
        downloadDao.getAllDownloads().map { liste -> liste.count { it.status == DownloadStatus.DOWNLOADED } }
    }.collectAsState(initial = 0)
    // KinoLel: Startbild mit Ladekreis und festem Server (keine Serverauswahl)
    Surface(color = KINOLEL_GRUND) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FesterServer(
                showExternalConnectionError = showExternalConnectionError,
                oben = oben,
                fertigeDownloads = fertigeDownloads,
                onDownloads = { activityEventHandler.emit(ActivityEvent.OpenDownloads) },
                onConnected = { hostname ->
                    mainViewModel.switchServer(hostname)
                },
            )
            // KinoLel: Knopf zu den Downloads sitzt jetzt in FesterServer (offline groß, sonst dezent)
        }
    }
}

@Stable
@Composable
fun LogoHeader() {
    CenterRow(
        modifier = Modifier.padding(vertical = 25.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            modifier = Modifier
                .height(72.dp),
            contentDescription = null,
        )
    }
}

@Stable
@Composable
fun StyledTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(text = text)
    }
}
