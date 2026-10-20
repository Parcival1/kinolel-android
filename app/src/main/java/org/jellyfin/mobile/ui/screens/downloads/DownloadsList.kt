package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ListItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.kinolel.DownloadFortschritt
import org.jellyfin.mobile.kinolel.DownloadQualitaet
import org.jellyfin.mobile.kinolel.OfflineSpeicher
import org.koin.compose.koinInject

@Composable
fun DownloadsList(
    downloads: List<DownloadFiles>,
    onOpen: (DownloadEntity) -> Unit,
    onDownload: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (DownloadEntity) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
    onToggleSelection: (DownloadEntity) -> Unit = {},
) {
    val selectionMode = selection.isNotEmpty()

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(
            downloads,
            key = { it.download.id },
        ) { downloadFiles ->
            DownloadItem(
                downloadFiles = downloadFiles,
                onOpen = { onOpen(downloadFiles.download) },
                onDownload = { onDownload(downloadFiles.download) },
                onDelete = { onDelete(downloadFiles.download) },
                onToggleSelection = { onToggleSelection(downloadFiles.download) },
                isSelected = selection.contains(downloadFiles.download.id),
                selectionMode = selectionMode,
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DownloadItem(
    downloadFiles: DownloadFiles,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
) {
    val (download, files) = downloadFiles
    val context = LocalContext.current
    val storageManager: StorageManager = koinInject()

    // KinoLel: Live-Fortschritt und Qualität
    val staende by DownloadFortschritt.staende.collectAsState()
    val stand = staende[download.id]
    val offlineSpeicher = remember(context) { OfflineSpeicher(context) }
    val qualitaet = remember(download.itemId) { DownloadQualitaet.von(offlineSpeicher.lesen(download.itemId).qualitaet) }

    val isVerified by produceState(initialValue = false, downloadFiles) {
        value = withContext(Dispatchers.IO) {
            storageManager.verify(downloadFiles)
        }
    }

    ListItem(
        modifier = modifier
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        !isVerified -> onDownload()
                        else -> onOpen()
                    }
                },
                onLongClick = { onToggleSelection() },
            ),
        text = {
            val name = remember(download, context) { download.getDisplayName(context).orEmpty() }
            Text(
                text = name,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        },
        icon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                val uri = remember(files) {
                    files.find { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri
                }

                AsyncImage(
                    model = uri,
                    placeholder = painterResource(R.drawable.ic_local_movies_white_64),
                    fallback = painterResource(R.drawable.ic_local_movies_white_64),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(
                        width = 64.dp,
                        height = 64.dp,
                    )
                )
            }
        },
        secondaryText = {
            when {
                download.status == DownloadStatus.DOWNLOADING && stand != null -> Column {
                    if (stand.prozent >= 0) {
                        LinearProgressIndicator(progress = stand.prozent / 100f)
                    } else {
                        LinearProgressIndicator()
                    }
                    Text(
                        text = buildString {
                            append(Formatter.formatShortFileSize(context, stand.geladen))
                            if (stand.gesamt > 0) {
                                append(" / ").append(if (qualitaet.umgewandelt) "~" else "")
                                append(Formatter.formatShortFileSize(context, stand.gesamt))
                                append(" · ").append(stand.prozent).append(" %")
                            }
                        },
                        maxLines = 1,
                    )
                }
                download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.QUEUED -> Column {
                    LinearProgressIndicator()
                    Text(text = stringResource(R.string.kinolel_dl_wartet), maxLines = 1)
                }
                download.status == DownloadStatus.ERROR -> Text(
                    text = stringResource(R.string.kinolel_dl_fehler),
                    color = Color.Yellow,
                    maxLines = 2,
                )
                isVerified -> Text(
                    text = qualitaetsName(qualitaet) + " · " + Formatter.formatShortFileSize(context, files.sumOf { it.size }),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                else -> Text(
                    text = stringResource(R.string.download_incomplete),
                    color = Color.Yellow,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        },
        trailing = {
            if (!selectionMode) {
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = stringResource(R.string.kinolel_dl_loeschen))
                }
            }
        },
        singleLineSecondaryText = true,
    )
}

@Composable
private fun qualitaetsName(q: DownloadQualitaet): String = stringResource(
    when (q) {
        DownloadQualitaet.ORIGINAL -> R.string.kinolel_dl_q_original
        DownloadQualitaet.HOCH -> R.string.kinolel_dl_q_hoch
        DownloadQualitaet.MITTEL -> R.string.kinolel_dl_q_mittel
        DownloadQualitaet.KLEIN -> R.string.kinolel_dl_q_klein
    },
)
