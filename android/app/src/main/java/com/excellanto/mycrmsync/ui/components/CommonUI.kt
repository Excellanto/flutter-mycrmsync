package com.excellanto.mycrmsync.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.excellanto.mycrmsync.ui.theme.DarkBlue
import com.excellanto.mycrmsync.ui.theme.ErrorRed

@Composable
fun AppLoadingBox() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DarkBlue)
    }
}

@Composable
fun AppErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, color = ErrorRed, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}
