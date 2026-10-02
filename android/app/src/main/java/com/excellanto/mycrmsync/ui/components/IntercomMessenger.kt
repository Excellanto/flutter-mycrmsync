package com.excellanto.mycrmsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import io.intercom.android.sdk.Intercom

@Composable
fun IntercomMessengerButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(PrimaryBlue.copy(alpha = 0.15f))
            .border(2.dp, PrimaryBlue, CircleShape)
            .clickable {
                Intercom.client().displayMessenger()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.ChatBubble,
            contentDescription = "Chat with Support",
            tint = PrimaryBlue,
            modifier = Modifier.size(28.dp)
        )
    }
}
