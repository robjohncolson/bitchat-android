package com.bitchat.android.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.profile.AppProfile

/**
 * One-time setup choice shown after onboarding completes on a FRESH install: the full [AppProfile.POWER]
 * experience, or the simplified [AppProfile.SIMPLE] ("Family") one. The choice seeds the app's defaults
 * (see ProfileSetupCoordinator). Styling here is deliberately plain — the SIMPLE surface itself is the
 * LINE-style reskin (see SimpleModeScreen/LineTheme); this one-time picker only has to be clear and
 * unmissable.
 */
@Composable
fun ProfilePickScreen(
    modifier: Modifier = Modifier,
    onPicked: (AppProfile) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.simple_pick_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.simple_pick_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        ProfileCard(
            title = stringResource(R.string.simple_pick_simple_title),
            subtitle = stringResource(R.string.simple_pick_simple_body),
            onClick = { onPicked(AppProfile.SIMPLE) }
        )
        Spacer(Modifier.height(16.dp))
        ProfileCard(
            title = stringResource(R.string.simple_pick_power_title),
            subtitle = stringResource(R.string.simple_pick_power_body),
            onClick = { onPicked(AppProfile.POWER) }
        )
    }
}

@Composable
private fun ProfileCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
