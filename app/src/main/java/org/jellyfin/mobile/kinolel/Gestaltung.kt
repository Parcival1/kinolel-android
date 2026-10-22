package org.jellyfin.mobile.kinolel

import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.snackbar.Snackbar
import org.jellyfin.mobile.R

/**
 * KinoLel (Lel 22.10.2026: „in derselben Designfarbe etc. wie die App“): Hinweisleisten (Snackbar, z. B. Akku-Hinweis)
 * im Design der Dialoge – dunkle Karte mit feinem Rand und runden Ecken, Schrift Inter, Knopf in Lavendel.
 */
fun Snackbar.kinolelGestalten(): Snackbar {
    val kontext = view.context
    view.background = ContextCompat.getDrawable(kontext, R.drawable.kinolel_hinweis_hintergrund)
    view.backgroundTintList = null
    val abstand = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, kontext.resources.displayMetrics).toInt()
    (view.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(abstand, abstand, abstand, abstand)
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        setTextColor(Color.WHITE)
        typeface = ResourcesCompat.getFont(kontext, R.font.inter_regular)
        maxLines = 5
    }
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
        typeface = ResourcesCompat.getFont(kontext, R.font.inter_semibold)
        isAllCaps = false
    }
    setActionTextColor(ContextCompat.getColor(kontext, R.color.kinolel_lavendel))
    return this
}
