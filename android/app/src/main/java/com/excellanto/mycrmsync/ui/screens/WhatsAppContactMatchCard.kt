package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.WhatsAppCrmMatch
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.LightIndigo
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec

@Composable
fun WhatsAppContactMatchCard(
    direction: String,
    displayName: String,
    isLoading: Boolean,
    matches: List<WhatsAppCrmMatch>,
    errorMessage: String?,
    linkingId: String?,
    selectedId: String?,
    onSelect: (WhatsAppCrmMatch) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (directionLabel, directionIcon) = directionPresentation(direction)
    Card(
        modifier = modifier.shadow(12.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName.ifBlank { stringResource(R.string.whatsapp_match_unknown_party) },
                    modifier = Modifier.weight(1f),
                    color = TextMain,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, enabled = linkingId == null, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.whatsapp_match_dismiss),
                        tint = TextSec,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = directionIcon,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = directionLabel,
                    modifier = Modifier.padding(start = 6.dp),
                    color = PrimaryBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.whatsapp_match_prompt),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = TextSec,
                fontSize = 14.sp,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(28.dp))
                            Text(
                                text = stringResource(R.string.whatsapp_match_searching),
                                modifier = Modifier.padding(top = 12.dp),
                                color = TextSec,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(16.dp),
                        color = TextMain,
                        fontSize = 14.sp,
                    )
                }
                matches.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.whatsapp_match_empty),
                        modifier = Modifier.padding(16.dp),
                        color = TextMain,
                        fontSize = 14.sp,
                    )
                }
                else -> {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        matches.forEach { match ->
                            MatchRow(
                                match = match,
                                enabled = linkingId == null,
                                selected = selectedId == match.id,
                                linking = linkingId == match.id,
                                onClick = { onSelect(match) },
                            )
                        }
                    }
                }
            }
            if (!isLoading && errorMessage == null && matches.isNotEmpty()) {
                Button(
                    onClick = onSave,
                    enabled = selectedId != null && linkingId == null,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(stringResource(R.string.whatsapp_match_save))
                }
            }
        }
    }
}

@Composable
private fun MatchRow(
    match: WhatsAppCrmMatch,
    enabled: Boolean,
    selected: Boolean,
    linking: Boolean,
    onClick: () -> Unit,
) {
    val subtitle =
        listOfNotNull(
            match.phone?.trim()?.ifBlank { null },
            match.company?.trim()?.ifBlank { null },
        ).joinToString(" · ")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .background(if (selected) LightIndigo else CardWhite)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(LightIndigo),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(match.name),
                color = PrimaryBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.name,
                color = TextMain,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = TextSec,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (linking) {
                Text(
                    text = stringResource(R.string.whatsapp_match_linking),
                    color = PrimaryBlue,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun directionPresentation(direction: String): Pair<String, ImageVector> {
    return when (direction.trim().uppercase()) {
        "INCOMING" -> stringResource(R.string.whatsapp_match_incoming) to Icons.AutoMirrored.Filled.CallReceived
        "OUTGOING" -> stringResource(R.string.whatsapp_match_outgoing) to Icons.AutoMirrored.Filled.CallMade
        "MISSED" -> stringResource(R.string.whatsapp_match_missed) to Icons.AutoMirrored.Filled.CallMissed
        else -> stringResource(R.string.whatsapp_match_generic) to Icons.AutoMirrored.Filled.CallReceived
    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).joinToString("") { it.first().uppercase() }
}
