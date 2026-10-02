package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.data.AuthRepository
import com.excellanto.mycrmsync.ui.components.AppLogo
import com.excellanto.mycrmsync.ui.components.AuthFormMessage
import com.excellanto.mycrmsync.ui.components.AuthMessageType
import com.excellanto.mycrmsync.ui.components.AuthPrimaryButtonColors
import com.excellanto.mycrmsync.ui.theme.DarkBlue
import com.excellanto.mycrmsync.ui.theme.InputBg
import com.excellanto.mycrmsync.ui.theme.TextGray
import com.excellanto.mycrmsync.ui.theme.TextSec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConnectAccountScreen(onOtpSent: (email: String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusType by remember { mutableStateOf(AuthMessageType.Info) }
    val scope = rememberCoroutineScope()

    fun setStatus(message: String, type: AuthMessageType) {
        statusMessage = message
        statusType = type
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF8FAFF),
                        Color(0xFFEEF4FF),
                    ),
                ),
            )
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "MyCrmSync",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = DarkBlue,
            letterSpacing = (-0.5).sp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        AppLogo(size = 180.dp)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Welcome back",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = DarkBlue,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your credentials to access your workspace.",
            fontSize = 16.sp,
            color = TextSec,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "EMAIL ADDRESS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSec,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = email,
                    onValueChange = {
                        email = it
                        statusMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    placeholder = { Text("name@company.com", color = TextGray) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = DarkBlue,
                        unfocusedTextColor = DarkBlue,
                        cursorColor = DarkBlue,
                    ),
                    singleLine = true,
                    enabled = !isLoading,
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        val trimmed = email.trim()
                        if (trimmed.isBlank()) return@Button
                        scope.launch {
                            isLoading = true
                            statusMessage = null
                            AuthRepository.sendEmailOtp(trimmed)
                                .onSuccess { message ->
                                    setStatus(message, AuthMessageType.Success)
                                    delay(800)
                                    onOtpSent(trimmed)
                                }
                                .onFailure { e ->
                                    setStatus(
                                        e.message ?: "Could not send verification code.",
                                        AuthMessageType.Error,
                                    )
                                }
                            isLoading = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = AuthPrimaryButtonColors.colors(),
                    enabled = email.isNotBlank() && !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "Login with Email",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }

                statusMessage?.let { message ->
                    Spacer(modifier = Modifier.height(16.dp))
                    AuthFormMessage(message = message, type = statusType)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Surface(
            color = Color(0xFFE0E9FF),
            shape = RoundedCornerShape(50.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = DarkBlue.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SECURE ENTERPRISE CONNECTION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkBlue.copy(alpha = 0.7f),
                    letterSpacing = 0.5.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = buildAnnotatedString {
                append("By continuing, you agree to the MyCrmSync ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = DarkBlue)) {
                    append("Terms of Service")
                }
                append(" and ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = DarkBlue)) {
                    append("Privacy Policy")
                }
                append(".")
            },
            fontSize = 12.sp,
            color = TextSec,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "ARCHITECTURE V2.4.0   •   ENCRYPTED WORKSPACE",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = TextGray.copy(alpha = 0.6f),
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
