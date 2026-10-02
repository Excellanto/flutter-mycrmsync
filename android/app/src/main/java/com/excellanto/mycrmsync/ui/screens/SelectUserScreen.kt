package com.excellanto.mycrmsync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.data.GhlRole
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.api.GhlApiService
import com.excellanto.mycrmsync.data.model.GhlUser
import com.excellanto.mycrmsync.ui.theme.*

sealed class SelectUserUiState {
    object Loading : SelectUserUiState()
    data class Success(val users: List<GhlUser>) : SelectUserUiState()
    object Empty : SelectUserUiState()
    data class Error(val message: String) : SelectUserUiState()
}

@Composable
fun SelectUserScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val prefsManager = remember { PrefsManager(context) }
    val apiService = remember { GhlApiService.create() }

    var uiState by remember { mutableStateOf<SelectUserUiState>(SelectUserUiState.Loading) }
    var selectedUserId by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        val token = prefsManager.getApiKey()
        val locationId = prefsManager.getLocationId()
        if (token != null && locationId != null) {
            try {
                val response = apiService.getUsers("Bearer $token", locationId)
                if (response.users.isEmpty()) {
                    uiState = SelectUserUiState.Empty
                } else {
                    uiState = SelectUserUiState.Success(response.users)
                    selectedUserId = response.users.firstOrNull()?.id
                }
            } catch (e: Exception) {
                uiState = SelectUserUiState.Error(e.localizedMessage ?: "Failed to load users")
            }
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
                }
                Text(
                    text = "Select Account MyCrmSync",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
            }
        },
        bottomBar = {
            if (uiState is SelectUserUiState.Success) {
                val successState = uiState as SelectUserUiState.Success
                Button(
                    onClick = {
                        val id = selectedUserId ?: return@Button
                        val picked = successState.users.firstOrNull { it.id == id }
                        prefsManager.saveUserId(id)
                        prefsManager.saveUserRole(picked?.effectiveType())
                        prefsManager.saveUserName(picked?.name)
                        onContinue()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkBlue,
                        contentColor = CardWhite
                    ),
                    enabled = selectedUserId != null
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Continue to Dashboard", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, 
                            contentDescription = null, 
                            modifier = Modifier.size(16.dp),
                            tint = CardWhite
                        )
                    }
                }
            }
        },
        containerColor = LightBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = "Select User Account",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose the user profile you want to continue with",
                fontSize = 16.sp,
                color = TextSec
            )
            Spacer(modifier = Modifier.height(32.dp))

            when (val state = uiState) {
                is SelectUserUiState.Loading -> LoadingState()
                is SelectUserUiState.Empty -> EmptyState()
                is SelectUserUiState.Error -> SelectUserErrorState(state.message)
                is SelectUserUiState.Success -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        items(state.users) { user ->
                            UserItem(
                                user = user,
                                isSelected = selectedUserId == user.id,
                                onClick = { selectedUserId = user.id }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserItem(user: GhlUser, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor by animateColorAsState(if (isSelected) DarkBlue else Color.Transparent)
    val bgColor = if (isSelected) LightIndigo else CardWhite

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(LightBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = TextSec, modifier = Modifier.size(32.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    user.effectiveType()?.let { roleLabel ->
                        Surface(
                            color = if (GhlRole.isAdmin(roleLabel)) DarkBlue else TextGray,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = roleLabel.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CardWhite
                            )
                        }
                    }
                }
                Text(
                    text = user.email,
                    fontSize = 14.sp,
                    color = TextSec
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = DarkBlue)
            )
        }
    }
}

@Composable
fun LoadingState() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(LightIndigo)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(20.dp)
                                .background(LightIndigo, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(180.dp)
                                .height(16.dp)
                                .background(LightIndigo, RoundedCornerShape(4.dp))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(LightIndigo)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = InputBg)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(LightBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = null,
                        tint = TextSec,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "No users found",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "We couldn't find any user profiles associated with this account. Contact support for help.",
                    fontSize = 14.sp,
                    color = TextSec,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun SelectUserErrorState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = ErrorRed, textAlign = TextAlign.Center)
    }
}
