package com.pdfmaker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfmaker.app.core.BuildOptions
import com.pdfmaker.app.core.MarginOption
import com.pdfmaker.app.core.PageSizeOption
import com.pdfmaker.app.core.QualityOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OptionsSheet(
    options: BuildOptions,
    onChange: ((BuildOptions) -> BuildOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("PDF settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "These apply to images. Pages taken from existing PDFs are copied " +
                    "through untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionLabel("Page size")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageSizeOption.entries.forEach { size ->
                    FilterChip(
                        selected = options.pageSize == size,
                        onClick = { onChange { it.copy(pageSize = size) } },
                        label = { Text(size.label) }
                    )
                }
            }

            SectionLabel("Margin")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarginOption.entries.forEach { margin ->
                    FilterChip(
                        selected = options.margin == margin,
                        onClick = { onChange { it.copy(margin = margin) } },
                        label = { Text(margin.label) }
                    )
                }
            }

            SectionLabel("Image quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityOption.entries.forEach { quality ->
                    FilterChip(
                        selected = options.quality == quality,
                        onClick = { onChange { it.copy(quality = quality) } },
                        label = { Text(quality.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-rotate pages", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Use landscape pages for wide images.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = options.autoRotate,
                    enabled = options.pageSize != PageSizeOption.FIT_IMAGE,
                    onCheckedChange = { checked -> onChange { it.copy(autoRotate = checked) } }
                )
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(8.dp))
}
