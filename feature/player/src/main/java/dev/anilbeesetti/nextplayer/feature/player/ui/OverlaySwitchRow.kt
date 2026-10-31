package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.components.NextSwitch

@Composable
internal fun OverlaySwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(shape = RoundedCornerShape(4.dp))
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
            )
            .fillMaxWidth()
            .padding(8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(weight = 1f),
        )
        NextSwitch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}
