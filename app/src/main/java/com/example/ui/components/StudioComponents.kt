package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ParamMode

@Composable
fun StudioSectionCard(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(16.dp)) { content() }
  }
}

@Composable
fun SectionTitleRow(
  title: String,
  icon: ImageVector,
  subtitle: String? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(9.dp).size(19.dp),
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    if (trailing != null) {
      Spacer(modifier = Modifier.width(8.dp))
      trailing()
    }
  }
}

@Composable
fun StatusPill(
  text: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    color = containerColor,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun ParamModeHeader(
  title: String,
  defaultSummary: String,
  mode: ParamMode,
  onModeSelected: (ParamMode) -> Unit,
) {
  val segmentedColors =
    SegmentedButtonDefaults.colors(
      activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
      activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      inactiveContainerColor = MaterialTheme.colorScheme.surface,
      inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
      inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "Default: $defaultSummary",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      SingleChoiceSegmentedButtonRow {
        SegmentedButton(
          selected = mode == ParamMode.DEFAULT,
          onClick = { onModeSelected(ParamMode.DEFAULT) },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          colors = segmentedColors,
        ) {
          Text("Default", style = MaterialTheme.typography.labelMedium)
        }
        SegmentedButton(
          selected = mode == ParamMode.MANUAL,
          onClick = { onModeSelected(ParamMode.MANUAL) },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          colors = segmentedColors,
        ) {
          Text("Manual", style = MaterialTheme.typography.labelMedium)
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}
