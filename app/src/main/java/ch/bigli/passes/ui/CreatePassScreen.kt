package ch.bigli.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.TransitType
import java.time.Instant

private val MANUAL_PASS_KINDS = listOf(PassType.EVENT, PassType.BOARDING, PassType.LOYALTY, PassType.GENERIC)

private data class PassColorSwatch(val name: String, val color: Long)

/** Curated background-color choices, echoing the app's own sample-pass palette. Foreground text
 * color is never picked here - [ch.bigli.passes.ui.legibleTextColor] derives it automatically
 * from whichever background is chosen. */
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

@Composable
private fun ColorSwatchPicker(selected: Long?, onSelect: (Long?) -> Unit) {
    Column {
        Text("Color", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ColorSwatch("Default color", color = null, isSelected = selected == null) { onSelect(null) }
            PASS_COLOR_SWATCHES.forEach { swatch ->
                ColorSwatch(swatch.name, Color(swatch.color), isSelected = selected == swatch.color) { onSelect(swatch.color) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePassScreen(
    prefill: Barcode?,
    onCreate: (
        type: PassType,
        organization: String,
        fields: List<PassField>,
        relevantDate: Instant?,
        transitType: TransitType?,
        bgColor: Long?,
        barcodeFormat: BarcodeFormat,
        barcodeValue: String,
    ) -> Unit,
    onBack: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(PassType.EVENT) }
    var organization by rememberSaveable { mutableStateOf("") }
    var bgColor by rememberSaveable { mutableStateOf<Long?>(null) }
    var eventDraft by remember { mutableStateOf(EventDraft()) }
    var boardingDraft by remember { mutableStateOf(BoardingDraft()) }
    var loyaltyDraft by remember { mutableStateOf(LoyaltyDraft()) }
    var genericDraft by remember { mutableStateOf(GenericDraft()) }

    var value by rememberSaveable { mutableStateOf(prefill?.message ?: "") }
    var format by remember { mutableStateOf(prefill?.format ?: BarcodeFormat.QR) }
    var expanded by remember { mutableStateOf(false) }

    val kindIsValid = if (kind == PassType.BOARDING) boardingDraft.isValid else true
    val canSubmit = organization.isNotBlank() && value.isNotBlank() && kindIsValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New pass") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MANUAL_PASS_KINDS.forEachIndexed { index, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = MANUAL_PASS_KINDS.size),
                    ) { Text(k.name) }
                }
            }
            OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text("Organization") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColorSwatchPicker(bgColor) { bgColor = it }
            when (kind) {
                PassType.EVENT -> EventFields(eventDraft) { eventDraft = it }
                PassType.BOARDING -> BoardingFields(boardingDraft) { boardingDraft = it }
                PassType.LOYALTY -> LoyaltyFields(loyaltyDraft) { loyaltyDraft = it }
                else -> GenericFieldsForm(genericDraft) { genericDraft = it }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Barcode value") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = format.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Format") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BarcodeFormat.entries.forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt.name) },
                            onClick = { format = fmt; expanded = false },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    // Explicit type args on every branch - without them, Kotlin can't always
                    // infer a common Triple<List<PassField>, Instant?, TransitType?> type
                    // across a when-expression mixing concrete values and bare nulls.
                    val (fields, relevantDate, transitType) = when (kind) {
                        PassType.EVENT -> Triple<List<PassField>, Instant?, TransitType?>(eventDraft.toPassFields(), eventDraft.toRelevantDate(), null)
                        PassType.BOARDING -> Triple<List<PassField>, Instant?, TransitType?>(boardingDraft.toPassFields(), boardingDraft.toRelevantDate(), boardingDraft.transitType)
                        PassType.LOYALTY -> Triple<List<PassField>, Instant?, TransitType?>(loyaltyDraft.toPassFields(), null, null)
                        else -> Triple<List<PassField>, Instant?, TransitType?>(genericDraft.toPassFields(), genericDraft.toRelevantDate(), null)
                    }
                    onCreate(kind, organization, fields, relevantDate, transitType, bgColor, format, value)
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create pass") }
        }
    }
}
