package org.jellyfin.mobile.ui.utils

import androidx.compose.material.contentColorFor
import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * KinoLel (Lel 22.10.2026): Im Dialog „Download entfernen“ waren Titel und Text dunkelgrau auf Dunkelblau.
 * Ursache: primaryVariant hatte dieselbe Farbe wie surface – contentColorFor(surface) lieferte dann onPrimary (dunkel).
 */
class AppThemeTest : FunSpec({
    test("Text auf Dialogen und Flächen ist hell") {
        val farben = kinolelFarben()
        farben.contentColorFor(farben.surface) shouldBe Color.White
        farben.contentColorFor(farben.background) shouldBe Color.White
    }
})
