package com.excellanto.mycrmsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.DarkBlue
import com.excellanto.mycrmsync.ui.theme.TextSec

enum class AppTab(val label: String) {
    Dashboard("Dashboard"),
    Contacts("Contacts"),
    Calls("Calls"),
}

@Composable
fun AppBottomNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Surface(color = CardWhite, tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AppBottomNavItem(
                tab = AppTab.Dashboard,
                selected = selected == AppTab.Dashboard,
                icon = Icons.Default.Dashboard,
                onClick = { onSelect(AppTab.Dashboard) }
            )
            AppBottomNavItem(
                tab = AppTab.Contacts,
                selected = selected == AppTab.Contacts,
                icon = Icons.Default.Person,
                onClick = { onSelect(AppTab.Contacts) }
            )
            AppBottomNavItem(
                tab = AppTab.Calls,
                selected = selected == AppTab.Calls,
                icon = Icons.Default.Call,
                onClick = { onSelect(AppTab.Calls) }
            )
        }
    }
}

@Composable
private fun AppBottomNavItem(
    tab: AppTab,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .background(Color.Transparent),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.size(6.dp))
        if (selected) {
            Box(
                modifier = Modifier
                    .size(52.dp, 34.dp)
                    .background(DarkBlue, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = tab.label, tint = Color.White)
            }
        } else {
            Box(
                modifier = Modifier.size(52.dp, 34.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = tab.label, tint = TextSec)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(tab.label, color = TextSec)
        Spacer(Modifier.size(6.dp))
    }
}
