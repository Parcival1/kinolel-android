package org.jellyfin.mobile.kinolel

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.requestPermission
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * KinoLel (Lel 23.11.2026): „Kannst du zu der Erinnerung für auslaufende Titel auch eine KinoLel App Benachrichtigung
 * machen?“ – Handy-Benachrichtigung, bevor ein Wunsch auf Jellyfin2 gelöscht wird (das Fenster beim Öffnen zeigt kinolel.js).
 *
 * Quelle: Jellyfin-Anzeige-Einstellungen `kinolel-bald` (Client `kinolel`) des angemeldeten Kontos, vom Server alle 15 Min.
 * geschrieben (CT 104 jellyfin2_erinnerung.py --app): `CustomPrefs.titel` = JSON-Liste {id, titel, tag, hu, tage}, nur
 * Titel mit Löschtag in höchstens 7 Tagen. Geprüft wird bei jedem App-Start und im Hintergrund etwa alle 6 Std. (WorkManager).
 *
 * Je Titel und Löschtag höchstens eine Benachrichtigung pro Gerät; Sprache nach Bibliothek (`hu`), nicht nach der App.
 * Gemeinsam mit dem Fenster (Lel 23.11.: „Wenn man es aber einmal weggeklickt hat soll es nicht mehr für diese Titel kommen,
 * außer es wurde danach nochmal angeschaut und der countdown resettet.“): Titel, die in `kinolel-bald-gesehen` stehen, nicht
 * melden; Antippen (ein Titel) bzw. Wegwischen trägt sie dort ein. Antippen bei mehreren öffnet die App, dort zeigt das
 * Fenster die Liste und merkt sie beim Wegklicken selbst.
 * Android 13+: nach der Erlaubnis wird erst gefragt, wenn wirklich etwas anzuzeigen ist – einmal je neuem Titel.
 */
object LoeschHinweis : KoinComponent {
    const val SCHLUESSEL = "kinolel_bald_schluessel"
    private const val VORLIEBEN = "kinolel_loeschhinweis"
    private const val GEZEIGT = "gezeigt" // „id:tag“, für die es auf diesem Gerät schon eine Benachrichtigung gab
    private const val OFFEN = "offen" // „id:tag“, die ohne Erlaubnis noch nicht gezeigt werden konnten
    private const val GEFRAGT = "gefragt" // „id:tag“, für die schon nach der Erlaubnis gefragt wurde
    private const val ABGEWIESEN = "abgewiesen" // Anmeldung, die der Server abgewiesen hat (fail2ban: nicht wiederholen)
    private const val KANAL = "kinolel_bald_geloescht"
    private const val ARBEIT = "kinolel-loeschhinweis"
    private const val LISTE = "kinolel-bald"
    private const val GESEHEN = "kinolel-bald-gesehen" // CustomPrefs „id:tag“ = Datum (leer = nicht gesehen), wie kinolel.js
    private const val CLIENT = "kinolel"
    private const val STUNDEN = 6L
    private const val NICHT_ANGEMELDET = 401
    private val zone = ZoneId.of("Europe/Berlin")
    private val kennung = Regex("[0-9a-fA-F]{32}")
    private val sperre = Mutex()

    /** `id` unverändert wie vom Server – der Schlüssel muss zu dem von kinolel.js passen */
    data class Eintrag(val id: String, val titel: String, val tag: String, val hu: Boolean, val tage: Int) {
        val schluessel get() = "$id:$tag"
    }

    private class Zugang(val api: ApiClient, val benutzer: UUID, val anmeldung: String)

    /** App-Start (angemeldet): Hintergrundprüfung einplanen, gleich prüfen und – falls nötig – nach der Erlaubnis fragen */
    suspend fun beimStart(activity: AppCompatActivity) {
        einplanen(activity.applicationContext)
        pruefen(activity.applicationContext)
        fragenFallsNoetig(activity)
    }

    private fun einplanen(context: Context) {
        val anfrage = PeriodicWorkRequestBuilder<LoeschHinweisWorker>(STUNDEN, TimeUnit.HOURS)
            .setInitialDelay(STUNDEN, TimeUnit.HOURS) // gleich nach dem Start prüft schon beimStart
            .setConstraints(mitNetz())
            .build()
        // UPDATE statt KEEP: Zeitplan bleibt, der System-Auftrag wird aber neu angelegt, falls er verloren ging
        // (z. B. „Beenden erzwingen“ löscht ihn, WorkManager erkennt das nicht auf jedem Gerät)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ARBEIT, ExistingPeriodicWorkPolicy.UPDATE, anfrage)
    }

    private fun mitNetz() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Angemeldetes Konto der App; null = nicht angemeldet oder vom Server schon einmal abgewiesen */
    private fun zugang(context: Context): Zugang? {
        val appPreferences = get<AppPreferences>()
        val serverId = appPreferences.currentServerId ?: return null
        val benutzer = appPreferences.currentUserId ?: return null
        val serverUser = get<UserDao>().getServerUser(serverId, benutzer) ?: return null
        val anmeldung = serverUser.user.accessToken?.hashCode()?.toString() ?: return null
        if (vorlieben(context).getString(ABGEWIESEN, null) == anmeldung) return null // erst nach neuer Anmeldung wieder
        return Zugang(get<ApiClientController>().getApiClient(serverId, benutzer), serverUser.user.userId, anmeldung)
    }

    private suspend fun einstellungen(zugang: Zugang, id: String) =
        zugang.api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = id, userId = zugang.benutzer, client = CLIENT).content

    /** Fehler beim Abruf: false = später erneut versuchen */
    private fun fehler(context: Context, zugang: Zugang, e: Exception): Boolean {
        if (e is CancellationException) throw e
        if (e is InvalidStatusException) {
            if (e.status == NICHT_ANGEMELDET) vorlieben(context).edit { putString(ABGEWIESEN, zugang.anmeldung) }
            Timber.i("KinoLel-Löschhinweis: Server antwortet %d", e.status)
            return e.status == NICHT_ANGEMELDET
        }
        Timber.i("KinoLel-Löschhinweis: keine Verbindung (%s)", e.javaClass.simpleName)
        return false
    }

    /** Liste vom Server holen und neue Einträge melden. Nicht angemeldet / offline: still nichts tun. */
    suspend fun pruefen(context: Context) = sperre.withLock {
        withContext(Dispatchers.IO) { pruefenGesperrt(context.applicationContext) }
    }

    private suspend fun pruefenGesperrt(context: Context) {
        val zugang = zugang(context) ?: return
        val (eintraege, gesehen) = try {
            lesen(einstellungen(zugang, LISTE).customPrefs["titel"]) to
                einstellungen(zugang, GESEHEN).customPrefs.filterValues { !it.isNullOrEmpty() }.keys
        } catch (e: Exception) {
            fehler(context, zugang, e)
            return
        }

        val vorlieben = vorlieben(context)
        val heute = LocalDate.now(zone).toString()
        fun aktuell(name: String) = vorlieben.getStringSet(name, null).orEmpty()
            .filterTo(HashSet()) { it.substringAfterLast(':') >= heute } // nach dem Löschtag vergessen
        val gezeigt = aktuell(GEZEIGT)
        val faellig = eintraege
            .filter { it.tag >= heute && it.schluessel !in gezeigt && it.schluessel !in gesehen }
            .distinctBy { it.schluessel }
        val offen = HashSet<String>()
        if (faellig.isNotEmpty() && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            val gruppen = faellig.groupBy { it.hu }
            val serverKennung = if (gruppen.values.any { it.size == 1 }) {
                runCatching { zugang.api.systemApi.getPublicSystemInfo().content.id }.getOrNull()?.takeIf(kennung::matches)
            } else {
                null
            }
            gruppen.forEach { (hu, liste) -> zeigen(context, liste, hu, serverKennung) }
            faellig.mapTo(gezeigt) { it.schluessel }
        } else {
            faellig.mapTo(offen) { it.schluessel }
        }
        Timber.i(
            "KinoLel-Löschhinweis: %d Einträge, %d schon gesehen, %d neu gemeldet, %d warten auf die Erlaubnis",
            eintraege.size,
            eintraege.count { it.schluessel in gesehen },
            faellig.size - offen.size,
            offen.size,
        )
        vorlieben.edit {
            putStringSet(GEZEIGT, gezeigt)
            putStringSet(OFFEN, offen)
            putStringSet(GEFRAGT, aktuell(GEFRAGT))
        }
    }

    /** Antippen/Wegwischen: Titel in `kinolel-bald-gesehen` eintragen – mit Netz, notfalls später (WorkManager) */
    fun gesehenMelden(context: Context, schluessel: Collection<String>) {
        if (schluessel.isEmpty()) return
        val anfrage = OneTimeWorkRequestBuilder<LoeschHinweisGesehenWorker>()
            .setInputData(workDataOf(SCHLUESSEL to schluessel.toTypedArray()))
            .setConstraints(mitNetz())
            .build()
        WorkManager.getInstance(context).enqueue(anfrage)
    }

    /** Antippen einer Benachrichtigung mit einem Titel (MainActivity) */
    fun ausIntent(context: Context, intent: Intent?) {
        val schluessel = intent?.getStringArrayListExtra(SCHLUESSEL) ?: return
        intent.removeExtra(SCHLUESSEL)
        gesehenMelden(context, schluessel)
    }

    /** true = erledigt (oder nicht mehr möglich), false = später erneut */
    suspend fun gesehenEintragen(context: Context, schluessel: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        val zugang = zugang(context.applicationContext) ?: return@withContext true
        try {
            val alt = einstellungen(zugang, GESEHEN)
            val heute = LocalDate.now(zone).toString()
            val neu = alt.customPrefs.toMutableMap()
            schluessel.forEach { if (neu[it].isNullOrEmpty()) neu[it] = heute }
            if (neu != alt.customPrefs) {
                zugang.api.displayPreferencesApi.updateDisplayPreferences(
                    displayPreferencesId = GESEHEN,
                    userId = zugang.benutzer,
                    client = CLIENT,
                    data = alt.copy(customPrefs = neu),
                )
            }
            Timber.i("KinoLel-Löschhinweis: %d als gesehen eingetragen", schluessel.size)
            true
        } catch (e: Exception) {
            fehler(context.applicationContext, zugang, e)
        }
    }

    /** Android 13+: nach der Erlaubnis fragen, wenn Titel nur deshalb noch nicht gemeldet sind – je Titel einmal */
    fun fragenFallsNoetig(activity: AppCompatActivity) {
        if (!AndroidVersion.isAtLeastT || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val erlaubnis = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, erlaubnis) == PackageManager.PERMISSION_GRANTED) return
        val vorlieben = vorlieben(activity)
        val gefragt = vorlieben.getStringSet(GEFRAGT, null).orEmpty()
        val neu = vorlieben.getStringSet(OFFEN, null).orEmpty() - gefragt
        if (neu.isEmpty()) return
        vorlieben.edit { putStringSet(GEFRAGT, gefragt + neu) }
        Timber.i("KinoLel-Löschhinweis: Erlaubnis für Benachrichtigungen erfragen (%d Titel)", neu.size)
        activity.requestPermission(erlaubnis) { ergebnis ->
            if (ergebnis[erlaubnis] == PackageManager.PERMISSION_GRANTED) {
                activity.lifecycleScope.launch { pruefen(activity.applicationContext) }
            }
        }
    }

    /** `CustomPrefs.titel` lesen; unbrauchbare Einträge überspringen */
    fun lesen(text: String?): List<Eintrag> {
        if (text.isNullOrBlank()) return emptyList()
        val liste = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return liste.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun wert(name: String) = runCatching { o[name]?.jsonPrimitive }.getOrNull()
            val id = wert("id")?.contentOrNull?.takeIf(kennung::matches)
            val titel = wert("titel")?.contentOrNull?.takeIf { it.isNotBlank() }
            val tag = wert("tag")?.contentOrNull?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
            if (id == null || titel == null || tag == null) return@mapNotNull null
            val hu = wert("hu")?.booleanOrNull ?: false
            Eintrag(id, titel, tag, hu, wert("tage")?.intOrNull?.takeIf { it > 0 } ?: if (hu) 14 else 35)
        }
    }

    /** Löschtag: DE „01.12.2026“, HU „2026. december 1.“ */
    fun datum(tag: String, hu: Boolean): String {
        val d = LocalDate.parse(tag)
        return if (hu) {
            "${d.year}. ${monateHu[d.monthValue - 1]} ${d.dayOfMonth}."
        } else {
            "%02d.%02d.%d".format(Locale.ROOT, d.dayOfMonth, d.monthValue, d.year)
        }
    }

    /** Frist mit Artikel (HU): DE „5 Wochen“ / „10 Tagen“, HU „az 5 hetes“ / „a 2 hetes“ / „a 10 napos“ */
    fun frist(tage: Int, hu: Boolean): String {
        val wochen = tage % 7 == 0
        val zahl = if (wochen) tage / 7 else tage
        return when {
            hu -> "${if (zahl == 1 || zahl == 5 || zahl in 50..59) "az" else "a"} $zahl ${if (wochen) "hetes" else "napos"}"
            wochen -> if (zahl == 1) "1 Woche" else "$zahl Wochen"
            else -> if (zahl == 1) "1 Tag" else "$zahl Tagen"
        }
    }

    @SuppressLint("MissingPermission") // nur nach areNotificationsEnabled() (Android 13+: Erlaubnis erteilt)
    private fun zeigen(context: Context, liste: List<Eintrag>, hu: Boolean, serverKennung: String?) {
        kanalAnlegen(context)
        val texte = inSprache(context, if (hu) "hu" else "de")
        val schluessel = ArrayList(liste.map { it.schluessel })
        val einzeln = liste.singleOrNull()
        val titel: String
        val text: String
        val ziel: Intent
        if (einzeln != null) {
            titel = texte.getString(R.string.kinolel_bald_ein_titel, einzeln.titel)
            text = texte.getString(R.string.kinolel_bald_ein_text, datum(einzeln.tag, hu), frist(einzeln.tage, hu))
            // Detailseite in der App (TitelLink nimmt die Adresse wie „Auf KinoLel abspielen“ aus KinoLel Wünsche)
            val adresse = "${BuildConfig.SERVER_URL}/web/#/details?id=${einzeln.id}" + serverKennung?.let { "&serverId=$it" }.orEmpty()
            ziel = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(adresse))
                .putStringArrayListExtra(SCHLUESSEL, schluessel)
        } else {
            // mehrere: App öffnen, dort zeigt kinolel.js das Fenster mit der Liste (und merkt sie beim Wegklicken)
            titel = texte.getString(R.string.kinolel_bald_mehrere_titel, liste.size)
            text = texte.getString(R.string.kinolel_bald_mehrere_text, liste.joinToString(", ") { it.titel })
            ziel = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
        }
        ziel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nummer = schluessel.joinToString(",").hashCode()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val weggewischt = Intent(context, LoeschHinweisWeggewischt::class.java).putStringArrayListExtra(SCHLUESSEL, schluessel)
        val benachrichtigung = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(context, nummer, ziel, flags))
            .setDeleteIntent(PendingIntent.getBroadcast(context, nummer, weggewischt, flags))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(LISTE, nummer, benachrichtigung)
    }

    /** Kanal „Bald gelöscht“ / „Hamarosan törlődik“ (Name in der App-Sprache) */
    private fun kanalAnlegen(context: Context) {
        if (!AndroidVersion.isAtLeastO) return
        val name = inSprache(context, Sprache.aktuell(context)).getString(R.string.kinolel_bald_kanal)
        context.getSystemService<NotificationManager>()
            ?.createNotificationChannel(NotificationChannel(KANAL, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun inSprache(context: Context, sprache: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(sprache)) },
    )

    private fun vorlieben(context: Context): SharedPreferences =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)

    private val monateHu = listOf(
        "január", "február", "március", "április", "május", "június",
        "július", "augusztus", "szeptember", "október", "november", "december",
    )
}

/** Hintergrundprüfung (etwa alle 6 Std., nur mit Netz) */
class LoeschHinweisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        LoeschHinweis.pruefen(applicationContext)
        return Result.success()
    }
}

/** Titel in `kinolel-bald-gesehen` eintragen (nach Antippen/Wegwischen) */
class LoeschHinweisGesehenWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val schluessel = inputData.getStringArray(LoeschHinweis.SCHLUESSEL)?.toList().orEmpty()
        return when {
            LoeschHinweis.gesehenEintragen(applicationContext, schluessel) -> Result.success()
            runAttemptCount < MAX_VERSUCHE -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_VERSUCHE = 5
    }
}

/** Benachrichtigung weggewischt („Alle löschen“ eingeschlossen) */
class LoeschHinweisWeggewischt : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LoeschHinweis.gesehenMelden(context, intent.getStringArrayListExtra(LoeschHinweis.SCHLUESSEL).orEmpty())
    }
}
