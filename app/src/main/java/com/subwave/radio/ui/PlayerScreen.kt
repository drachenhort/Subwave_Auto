package com.subwave.radio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    var serverInput by remember {
        mutableStateOf(com.subwave.radio.data.ServerPrefs.getLastServer(viewModel.contextRef) ?: "")
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = viewModel.nowPlaying.artworkUri,
                contentDescription = "Artist artwork",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = viewModel.nowPlaying.title, style = MaterialTheme.typography.titleLarge)
            Text(text = viewModel.nowPlaying.subtitle, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = viewModel.connectionState) {
                is ConnectionState.Failed -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                ConnectionState.Connecting -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else -> {}
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = serverInput,
                onValueChange = { serverInput = it },
                label = { Text("Server address") },
                placeholder = { Text("e.g. stream.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.playFromUserInput(serverInput) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.stop() },
                enabled = viewModel.connectionState != ConnectionState.Idle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop")
            }
        }
    }
}
