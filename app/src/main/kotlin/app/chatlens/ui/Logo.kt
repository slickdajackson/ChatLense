package app.chatlens.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.chatlens.R

/**
 * Marke von ChatLens: Sprechblase mit den drei KI-Sternen (ohne Lupe). Die Vektordatei ic_logo_mark.xml wird von tools/make_logo.py
 * aus der gewaehlten Variante erzeugt (siehe PLAN.md Abschnitt 22). Nur Bild, keine Schrift.
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(R.drawable.ic_logo_mark), contentDescription = description, modifier = modifier)
}
