package com.example.mididrums

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Zona táctil/visual sobre la imagen de la batería: a qué nota MIDI
 * corresponde, y dónde está ubicada (en coordenadas relativas 0f..1f
 * respecto al ancho/alto de la imagen, para que funcione en cualquier
 * tamaño de pantalla sin recalcular píxeles).
 *
 * centerX/centerY: centro de la zona, como fracción del ancho/alto.
 * radius: radio del resplandor, como fracción del ancho de la imagen.
 */
data class DrumZone(
    val note: Int,
    val label: String,
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

/**
 * Disposición por defecto de zonas sobre una imagen de batería acústica
 * vista desde arriba, en composición estándar:
 * hi-hat arriba-izquierda, dos toms arriba-centro, crash arriba-centro,
 * ride arriba-derecha, caja centro-izquierda abajo, bombo centro abajo,
 * tom de piso derecha abajo.
 *
 * Estas coordenadas son aproximadas y pensadas para una imagen cuadrada
 * centrada; si la imagen final tiene otro encuadre, ajusta los valores
 * aquí comparando contra tu imagen real.
 */
object DefaultDrumZones {
    val zones = listOf(
        DrumZone(note = 42, label = "Hi-Hat cerrado", centerX = 0.18f, centerY = 0.28f, radius = 0.14f),
        DrumZone(note = 46, label = "Hi-Hat abierto", centerX = 0.18f, centerY = 0.28f, radius = 0.14f),
        DrumZone(note = 44, label = "Hi-Hat pedal", centerX = 0.18f, centerY = 0.28f, radius = 0.14f),
        DrumZone(note = 49, label = "Crash", centerX = 0.42f, centerY = 0.16f, radius = 0.14f),
        DrumZone(note = 51, label = "Ride", centerX = 0.82f, centerY = 0.30f, radius = 0.15f),
        DrumZone(note = 48, label = "Tom agudo", centerX = 0.40f, centerY = 0.36f, radius = 0.13f),
        DrumZone(note = 45, label = "Tom medio", centerX = 0.58f, centerY = 0.36f, radius = 0.13f),
        DrumZone(note = 38, label = "Caja", centerX = 0.30f, centerY = 0.62f, radius = 0.15f),
        DrumZone(note = 36, label = "Bombo", centerX = 0.50f, centerY = 0.55f, radius = 0.17f),
        DrumZone(note = 41, label = "Tom de piso", centerX = 0.75f, centerY = 0.65f, radius = 0.16f)
    )
}

/**
 * Carga una imagen desde assets/. Devuelve null si no existe (por ejemplo,
 * si el usuario todavía no agregó su imagen de batería), para que el resto
 * de la UI pueda mostrar un estado de reemplazo en vez de fallar.
 */
@Composable
private fun rememberAssetImage(fileName: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(fileName) {
        bitmap = try {
            context.assets.open(fileName).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    return bitmap
}

/**
 * Batería visual: muestra la imagen de fondo (desde assets) y, encima,
 * un resplandor que aparece brevemente sobre la pieza correspondiente
 * cada vez que su nota MIDI suena durante la reproducción.
 *
 * activeNote: la última nota disparada (la actualiza DrumEngine.onNoteTriggered).
 * Cada vez que cambia, la zona correspondiente hace un breve flash y se apaga
 * con una animación de fade-out.
 */
@Composable
fun DrumKitVisual(
    activeNote: Int?,
    zones: List<DrumZone> = DefaultDrumZones.zones,
    modifier: Modifier = Modifier
) {
    val image = rememberAssetImage("drumkit.png") ?: rememberAssetImage("drumkit.jpg")

    // Una animación de intensidad por nota única, para que cada pieza se
    // ilumine de forma independiente sin pisarse entre golpes rápidos de
    // piezas distintas.
    val intensities = remember(zones) {
        zones.associateBy { it.note }.mapValues { Animatable(0f) }
    }

    LaunchedEffect(activeNote) {
        val note = activeNote ?: return@LaunchedEffect
        val anim = intensities[note] ?: return@LaunchedEffect
        launch {
            anim.snapTo(1f)
            anim.animateTo(0f, animationSpec = tween(durationMillis = 220))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        if (image == null) {
            // Estado de reemplazo mientras no exista app/src/main/assets/drumkit.png
            return@Box
        }

        Image(
            bitmap = image,
            contentDescription = "Batería",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val h = size.height
                    zones.forEach { zone ->
                        val anim = intensities[zone.note] ?: return@forEach
                        val alpha = anim.value
                        if (alpha > 0.01f) {
                            val center = Offset(zone.centerX * w, zone.centerY * h)
                            val radiusPx = zone.radius * w
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF4FC3F7).copy(alpha = alpha * 0.85f),
                                        Color(0xFF4FC3F7).copy(alpha = 0f)
                                    ),
                                    center = center,
                                    radius = radiusPx
                                ),
                                radius = radiusPx,
                                center = center
                            )
                        }
                    }
                },
            contentScale = ContentScale.Fit
        )
    }
}
