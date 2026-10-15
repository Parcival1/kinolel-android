package org.jellyfin.mobile.ui.screens.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.koin.compose.koinInject

/**
 * KinoLel: fester Server statt Serverauswahl.
 *
 * Verbindet sofort mit [BuildConfig.SERVER_URL] (marke.properties). Ist der Server nicht erreichbar, erscheint ein Hinweis
 * mit „Erneut versuchen“; zusätzlich wird alle 15 Sekunden von selbst neu versucht.
 */
@Composable
fun FesterServer(
    showExternalConnectionError: Boolean,
    connectionHelper: ConnectionHelper = koinInject(),
    onConnected: suspend (String) -> Unit,
) {
    var versuch by remember { mutableIntStateOf(0) }
    var nichtErreichbar by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf(false) }

    LaunchedEffect(versuch) {
        // Nach einem Abbruch in der Weboberfläche kurz warten, damit es keine schnelle Schleife gibt
        if (showExternalConnectionError && versuch == 0) delay(ABBRUCH_PAUSE_MS)
        val state = connectionHelper.checkServerUrl(BuildConfig.SERVER_URL)
        if (state is CheckUrlState.Success) {
            nichtErreichbar = false
            onConnected(state.address)
        } else {
            nichtErreichbar = true
            delay(AUTOMATISCH_NEU_MS)
            versuch++
        }
    }

    // Hinweis „Verbindung wird hergestellt“ nur, wenn es länger dauert
    LaunchedEffect(Unit) {
        delay(HINWEIS_NACH_MS)
        hinweis = true
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(176.dp)) {
            CircularProgressIndicator(
                modifier = Modifier.size(176.dp),
                color = KINOLEL_LAVENDEL,
                strokeWidth = 4.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.kinolel_logo),
                    contentDescription = null,
                    modifier = Modifier.width(72.dp).height(46.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Image(
                    painter = painterResource(R.drawable.kinolel_schriftzug),
                    contentDescription = BuildConfig.APP_NAME,
                    modifier = Modifier.height(21.dp),
                )
            }
        }
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .padding(top = 28.dp, start = 24.dp, end = 24.dp),
        ) {
            when {
                nichtErreichbar -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.kinolel_nicht_erreichbar, BuildConfig.APP_NAME),
                        color = KINOLEL_TEXT,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { versuch++ },
                        colors = ButtonDefaults.buttonColors(backgroundColor = KINOLEL_LAVENDEL, contentColor = KINOLEL_GRUND),
                    ) {
                        Text(text = stringResource(R.string.kinolel_nochmal))
                    }
                }
                hinweis -> Text(
                    text = stringResource(R.string.kinolel_verbinde, BuildConfig.APP_NAME),
                    color = KINOLEL_TEXT,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val ABBRUCH_PAUSE_MS = 2_000L
private const val AUTOMATISCH_NEU_MS = 15_000L
private const val HINWEIS_NACH_MS = 3_000L

internal val KINOLEL_LAVENDEL = Color(0xFF9AA4FF)
internal val KINOLEL_GRUND = Color(0xFF0B0F18)
internal val KINOLEL_TEXT = Color(0xBFFFFFFF)
