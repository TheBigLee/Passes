package ch.bigli.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField

/**
 * Field layout for every pass type except BOARDING (which uses [BoardingFieldsLayout]).
 * Each PRIMARY field gets its own full-width, left-aligned line (event name, then product,
 * etc.) - then SECONDARY fields flow left-to-right below that, then AUXILIARY fields render
 * as a row (first left-aligned, last right-aligned when there are exactly 2 - e.g. attendee
 * name / entry date). Nothing is capped or dropped, matching how Google Wallet renders an
 * event ticket.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenericFieldsLayout(pass: Pass, fg: Color) {
    val primary = pass.fields.filter { it.position == FieldPosition.PRIMARY }
    val secondary = pass.fields.filter { it.position == FieldPosition.SECONDARY }
    val auxiliary = pass.fields.filter { it.position == FieldPosition.AUXILIARY }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        primary.forEach { f ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                Text(f.value, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (secondary.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                secondary.forEachIndexed { index, f ->
                    // The first field has no leading padding, unlike GenericField's default -
                    // it must sit flush left to line up with the PRIMARY line(s) above it.
                    val modifier = if (index == 0) {
                        Modifier.padding(top = 4.dp, end = 8.dp, bottom = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    }
                    GenericField(f, fg, Alignment.Start, modifier = modifier)
                }
            }
        }
        if (auxiliary.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            // The second field's own text stays left-aligned (matching every other field's
            // label/value justification) even though the field itself sits on the right of
            // the row.
            FieldsRow(auxiliary, fg, lastFieldTextAlignment = Alignment.Start)
        }
    }
}

/**
 * Renders a group of fields as one row. Exactly 2 fields is the common case - e.g.
 * attendee name / entry date, or passenger / status - and renders with the first flush
 * left and the second flush right (no horizontal inset, unlike [GenericField]'s default
 * padding, since these must sit at the row's true edges to line up with the flush-left
 * PRIMARY line above). Any other count flows left-to-right instead, since "first left /
 * last right" only reads as intentional pairing for exactly 2 fields.
 *
 * [lastFieldTextAlignment] controls only the second field's own label/value text
 * justification within its column, independent of its position in the row (which is
 * always pinned right by [Arrangement.SpaceBetween]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldsRow(
    fields: List<PassField>,
    fg: Color,
    lastFieldTextAlignment: Alignment.Horizontal = Alignment.End,
) {
    if (fields.size == 2) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GenericField(fields[0], fg, Alignment.Start, modifier = Modifier.padding(vertical = 4.dp))
            GenericField(fields[1], fg, lastFieldTextAlignment, modifier = Modifier.padding(vertical = 4.dp))
        }
    } else {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            fields.forEach { GenericField(it, fg, Alignment.Start) }
        }
    }
}

@Composable
private fun GenericField(
    field: PassField,
    fg: Color,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
) {
    Column(modifier, horizontalAlignment = alignment) {
        Text(field.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(field.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Top-right header slot for BOARDING passes (e.g. "Gate A/B", "Seat 9F"), rendered next to
 * the strip/logo area rather than inside the scrollable fields column. No-op if the pass has
 * no HEADER fields.
 */
@Composable
fun BoardingHeaderRow(pass: Pass, fg: Color) {
    val header = pass.fields.filter { it.position == FieldPosition.HEADER }
    if (header.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        header.forEach { f ->
            Column(
                Modifier.padding(start = 12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
                Text(f.value, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Field layout for BOARDING passes, matching the SWISS/Google Wallet reference: a big
 * two-up PRIMARY row (origin/destination) with a plane icon between pointing from
 * departure toward destination, then AUXILIARY fields (capped at 4 - real boarding
 * passes reliably carry exactly flight/date/boarding/class), then SECONDARY fields
 * (capped at 2, first left-aligned/second right-aligned - e.g. passenger/status).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoardingFieldsLayout(pass: Pass, fg: Color) {
    val primary = pass.fields.filter { it.position == FieldPosition.PRIMARY }.take(2)
    val auxiliary = pass.fields.filter { it.position == FieldPosition.AUXILIARY }.take(4)
    val secondary = pass.fields.filter { it.position == FieldPosition.SECONDARY }.take(2)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A pass with zero PRIMARY fields intentionally renders no primary row at all.
        if (primary.size == 2) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardingPrimaryField(primary[0], fg)
                Icon(
                    Icons.Filled.Flight,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .size(52.dp)
                        .graphicsLayer(rotationZ = 90f),
                )
                BoardingPrimaryField(primary[1], fg)
            }
        } else if (primary.size == 1) {
            BoardingPrimaryField(primary[0], fg)
            Spacer(Modifier.size(16.dp))
        }
        if (auxiliary.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                auxiliary.forEach { GenericField(it, fg, Alignment.Start) }
            }
        }
        if (secondary.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            FieldsRow(secondary, fg)
        }
    }
}

@Composable
private fun BoardingPrimaryField(field: PassField, fg: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(field.label, color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(field.value, color = fg, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}
