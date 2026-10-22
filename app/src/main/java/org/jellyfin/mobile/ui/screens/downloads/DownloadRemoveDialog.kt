package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.ui.utils.KinoLelDialogGrund
import org.jellyfin.mobile.ui.utils.KinoLelDialogRand

@Composable
fun DownloadRemoveDialog(
    downloads: List<DownloadEntity>,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var keepLocalFiles by remember { mutableStateOf(false) }

    // KinoLel (Lel 22.10.2026): im Design der Weboberfläche – dunkle Karte mit feinem Rand, Hauptknopf in Lavendel
    val form = RoundedCornerShape(18.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, KinoLelDialogRand, form),
        shape = form,
        backgroundColor = KinoLelDialogGrund,
        contentColor = Color.White,
        title = {
            Text(
                text = pluralStringResource(R.plurals.download_remove_title, downloads.size),
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
            )
        },
        text = {
            Column {
                val name = remember(downloads, context) {
                    downloads
                        .firstOrNull()
                        .takeIf { downloads.size == 1 }
                        ?.getDisplayName(context)
                        .orEmpty()
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.download_remove_description,
                        downloads.size,
                        downloads.size,
                        name,
                    )
                )

                // KinoLel: Downloads liegen im App-Speicher – „Dateien behalten“ ergibt dort keinen Sinn, immer löschen
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(keepLocalFiles) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF9AA4FF), contentColor = Color(0xFF0B0F18)),
            ) {
                Text(text = stringResource(R.string.yes), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.download_cancel), color = Color(0xFF9AA4FF), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
