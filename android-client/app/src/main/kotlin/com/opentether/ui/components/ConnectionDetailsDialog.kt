package com.opentether.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentether.diagnostics.AndroidDiagnostics
import com.opentether.runtime.TunnelRuntimeState
import com.opentether.ui.buildDiagnosticSummary

@Composable
fun ConnectionDetailsDialog(
    runtime: TunnelRuntimeState,
    diagnostics: AndroidDiagnostics?,
    appVersion: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val summary = remember(runtime, diagnostics, appVersion) {
        buildDiagnosticSummary(runtime, diagnostics, appVersion)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection details") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenTether diagnostics", summary))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
