package com.kododake.aabrowser.ui.compose.screens.startpage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.R

data class RecentlyClosedTabUi(val url: String, val title: String)

private const val COLUMNS = 3

@Composable
fun StartPageRecentlyClosedTabs(
    tabs: List<RecentlyClosedTabUi>,
    onTabClick: (RecentlyClosedTabUi) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    Column(
        modifier = modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.start_page_recently_closed_tabs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FocusablePill(
                onClick = onClearAll,
                modifier = Modifier,
                background = Color.Transparent
            ) {
                Text(
                    text = stringResource(R.string.start_page_clear_all) + "  ✕",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.chunked(COLUMNS).forEach { rowTabs ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowTabs.forEach { tab ->
                        FocusablePill(
                            onClick = { onTabClick(tab) },
                            modifier = Modifier.weight(1f),
                            background = Color(0xCC4A4748)
                        ) {
                            Text(
                                text = tab.title.ifBlank { tab.url },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                    repeat(COLUMNS - rowTabs.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FocusablePill(
    onClick: () -> Unit,
    modifier: Modifier,
    background: Color,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = background,
        modifier = modifier
            .heightIn(min = 40.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent),
                shape = shape
            )
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.CenterStart) { content() }
    }
}
