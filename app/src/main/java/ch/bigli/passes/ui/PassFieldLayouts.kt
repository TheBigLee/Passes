package ch.bigli.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField

/**
 * Field layout for every pass type except BOARDING (which uses [BoardingFieldsLayout]).
 * Stacks PRIMARY fields full-width and large, then flows SECONDARY/AUXILIARY fields below -
 * nothing is capped or dropped, matching how Google Wallet renders an event ticket.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenericFieldsLayout(pass: Pass, fg: Color) {
    val primary = pass.fields.filter { it.position == FieldPosition.PRIMARY }
    val secondary = pass.fields.filter { it.position == FieldPosition.SECONDARY }
    val auxiliary = pass.fields.filter { it.position == FieldPosition.AUXILIARY }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        primary.forEach { f ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                Text(f.value, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (secondary.isNotEmpty() || auxiliary.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
        }
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            (secondary + auxiliary).forEach { f -> GenericField(f, fg) }
        }
    }
}

@Composable
private fun GenericField(field: PassField, fg: Color) {
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(field.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(field.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
