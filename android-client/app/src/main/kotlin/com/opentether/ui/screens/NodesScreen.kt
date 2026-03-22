package com.opentether.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentether.NetworkNode
import com.opentether.ui.components.EmptyState
import com.opentether.ui.components.NodeStatusPill
import com.opentether.ui.components.SectionCard

@Composable
fun NodesScreen(
    nodes: List<NetworkNode>,
    onNodeSelected: (NetworkNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = "Nodes",
                subtitle = "Infrastructure and observed peers discovered from the live tunnel state",
            ) {
                if (nodes.isEmpty()) {
                    EmptyState(
                        title = "No nodes available",
                        message = "The node graph fills in when the app has runtime or packet data to display.",
                    )
                }
            }
        }

        items(nodes, key = { it.id }) { node ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
            ) {
                SectionCard(
                    title = node.title,
                    subtitle = node.address,
                    modifier = Modifier.clickable { onNodeSelected(node) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = node.subtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = node.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NodeStatusPill(status = node.status)
                    }
                }
            }
        }
    }
}

@Composable
fun NodeDetailScreen(
    node: NetworkNode?,
    modifier: Modifier = Modifier,
) {
    if (node == null) {
        EmptyState(
            title = "Node not found",
            message = "The selected node is no longer in the current runtime snapshot.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = node.title,
                subtitle = node.address,
            ) {
                NodeStatusPill(status = node.status)
                Text(
                    text = node.subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = node.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
