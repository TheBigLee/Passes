package ch.bigli.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class PassColorSwatch(val name: String, val color: Long)

/** Curated background-color choices, echoing the app's own sample-pass palette. Foreground text
 * color is never picked here - [legibleTextColor] derives it automatically from whichever
 * background is chosen. */
private val PASS_COLOR_SWATCHES = listOf(
    PassColorSwatch("Blue", 0xFF1A73E8L),
    PassColorSwatch("Red", 0xFFD32F2FL),
    PassColorSwatch("Green", 0xFF2E7D32L),
    PassColorSwatch("Purple", 0xFF7B1FA2L),
    PassColorSwatch("Orange", 0xFFEF6C00L),
    PassColorSwatch("Teal", 0xFF00897BL),
    PassColorSwatch("Grey", 0xFF455A64L),
    PassColorSwatch("Pink", 0xFFD81B60L),
)

/** A tappable circle inside a 48dp touch target (Material's minimum), with a border indicating
 * selection and a screen-reader-friendly name - [color] null renders a hollow ring representing
 * "app default" rather than an arbitrary extra color choice. */
@Composable
private fun ColorSwatch(name: String, color: Color?, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = name }
            .padding(6.dp)
            .clip(CircleShape)
            .then(if (color != null) Modifier.background(color) else Modifier)
            .border(
                width = if (isSelected) 3.dp else if (color == null) 1.dp else 0.dp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    color == null -> MaterialTheme.colorScheme.outline
                    else -> Color.Transparent
                },
                shape = CircleShape,
            ),
    )
}

/** A row of curated background-color swatches (plus a "default" option) for manual pass creation. */
@Composable
fun ColorSwatchPicker(selected: Long?, onSelect: (Long?) -> Unit) {
    Column {
        Text("Color", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.padding(top = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ColorSwatch("Default color", color = null, isSelected = selected == null) { onSelect(null) }
            PASS_COLOR_SWATCHES.forEach { swatch ->
                ColorSwatch(swatch.name, Color(swatch.color), isSelected = selected == swatch.color) { onSelect(swatch.color) }
            }
        }
    }
}
