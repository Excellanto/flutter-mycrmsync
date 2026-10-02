package com.excellanto.mycrmsync.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmContactCallStats
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.LightBg
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.SuccessGreen
import com.excellanto.mycrmsync.ui.theme.TextGray
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec

private val TagBorderColor = Color(0xFFCBD5E1)

@Composable
fun ContactListCard(
    contact: CrmContact,
    callStats: CrmContactCallStats? = contact.calls,
    onCallClick: (String) -> Unit,
    onNotesClick: (String, String) -> Unit = { _, _ -> },
    onEditClick: () -> Unit = {},
    onTagClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val displayTags =
        remember(contact.id, contact.type, contact.tags) {
            buildList {
                contact.type?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                contact.tags.orEmpty().forEach { tag ->
                    val trimmed = tag.trim()
                    if (trimmed.isNotBlank() && none { it.equals(trimmed, ignoreCase = true) }) {
                        add(trimmed)
                    }
                }
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 8.dp)),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(LightBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = contact.initials,
                            color = TextSec,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        Text(
                            text = contact.fullName,
                            color = TextMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 68.dp),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ContactCardTagRows(
                            tags = displayTags,
                            onTagClick = onTagClick,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = (-10).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit contact",
                            tint = TextGray,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { onNotesClick(contact.id, contact.fullName) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(end = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_contact_notes),
                                contentDescription = "Notes",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(22.dp),
                            )
                            val noteCount = callStats?.totalNotes ?: 0
                            if (noteCount > 0) {
                                Surface(
                                    color = PrimaryBlue,
                                    shape = CircleShape,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-5).dp)
                                            .size(15.dp),
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = if (noteCount > 99) "99+" else noteCount.toString(),
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                            style =
                                                LocalTextStyle.current.copy(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 8.sp,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = LightBg, thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContactCallStatsRow(
                    stats = callStats,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactCardActionIcon(
                        iconRes = R.drawable.ic_contact_sms,
                        contentDescription = "Text message",
                        onClick = { contact.phone?.let { launchContactSms(context, it) } },
                    )
                    ContactCardActionIcon(
                        iconRes = R.drawable.ic_contact_email,
                        contentDescription = "Email",
                        onClick = { contact.email?.let { launchContactEmail(context, it) } },
                    )
                    ContactCardActionIcon(
                        iconRes = R.drawable.ic_contact_whatsapp,
                        contentDescription = "WhatsApp",
                        onClick = { contact.phone?.let { launchContactWhatsapp(context, it) } },
                    )
                    ContactCardActionIcon(
                        iconRes = R.drawable.ic_contact_call,
                        contentDescription = "Call",
                        onClick = { contact.phone?.let { onCallClick(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactCallStatsRow(
    stats: CrmContactCallStats?,
    modifier: Modifier = Modifier,
) {
    if (stats == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactCallStatChip(
            icon = Icons.AutoMirrored.Filled.CallMade,
            iconTint = PrimaryBlue,
            iconBackground = PrimaryBlue.copy(alpha = 0.12f),
            value = stats.totalDialed.toString(),
        )
        ContactCallStatChip(
            icon = Icons.Default.AccessTime,
            iconTint = TextSec,
            iconBackground = TextSec.copy(alpha = 0.12f),
            value = formatContactTalkTime(stats.totalTalkTime),
        )
        ContactCallStatChip(
            icon = Icons.AutoMirrored.Filled.CallReceived,
            iconTint = SuccessGreen,
            iconBackground = SuccessGreen.copy(alpha = 0.12f),
            value = stats.totalReceived.toString(),
        )
    }
}

@Composable
private fun ContactCallStatChip(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = value,
            color = TextMain,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun formatContactTalkTime(seconds: Int): String {
    if (seconds <= 0) return "0m"
    val minutes = seconds / 60
    val remainder = seconds % 60
    return when {
        minutes > 0 && remainder > 0 -> "${minutes}m ${remainder}s"
        minutes > 0 -> "${minutes}m"
        else -> "${remainder}s"
    }
}

@Composable
private fun ContactCardTagRows(
    tags: List<String>,
    onTagClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) {
        ContactTagBadge(
            label = "NO TAG",
            modifier = Modifier.clickable { onTagClick() },
        )
        return
    }

    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onTagClick() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tags.forEach { tag ->
            ContactTagBadge(label = tag.uppercase())
        }
    }
}

@Composable
private fun ContactTagBadge(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 9.sp,
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, TagBorderColor),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = TextSec,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContactCardActionIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun launchContactSms(context: Context, phoneNumber: String) {
    val intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
        }
    context.startActivity(intent)
}

private fun launchContactEmail(context: Context, email: String) {
    val intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
        }
    context.startActivity(intent)
}

private fun launchContactWhatsapp(context: Context, phoneNumber: String) {
    runCatching {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber")
            }
        context.startActivity(intent)
    }
}
