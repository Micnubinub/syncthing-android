package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.micnubinub.syncthing.R

enum class UsageReportingChoice {
    ACCEPT,
    DENY,
    OPEN_WEBSITE,
}

@Composable
fun UsageReportingDialog(
    report: String,
    onChoice: (UsageReportingChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.usage_reporting_dialog_title),
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.usage_reporting_dialog_description),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = report,
                )
                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = { onChoice(UsageReportingChoice.OPEN_WEBSITE) }) {
                    Text(stringResource(R.string.open_website))
                }
            }

        },
        confirmButton = {
            TextButton(onClick = { onChoice(UsageReportingChoice.ACCEPT) }) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(UsageReportingChoice.DENY) }) {
                Text(stringResource(R.string.no))
            }
        }
    )
}
