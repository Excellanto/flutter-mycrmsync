package com.excellanto.mycrmsync.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.data.AuthRepository
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import com.excellanto.mycrmsync.ui.components.AppLogo
import com.excellanto.mycrmsync.ui.components.AuthFormMessage
import com.excellanto.mycrmsync.ui.components.AuthMessageType
import com.excellanto.mycrmsync.ui.components.AuthPrimaryButtonColors
import com.excellanto.mycrmsync.ui.theme.DarkBlue
import com.excellanto.mycrmsync.ui.theme.InputBg
import com.excellanto.mycrmsync.ui.theme.TextGray
import com.excellanto.mycrmsync.ui.theme.TextSec
import kotlinx.coroutines.launch

@Composable
fun VerifyOtpScreen(
    email: String,
    onBack: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val prefsManager = remember { PrefsManager(context) }
    val scope = rememberCoroutineScope()
    var digits by remember { mutableStateOf(List(4) { "" }) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusType by remember { mutableStateOf(AuthMessageType.Error) }
    val focusRequesters = remember { List(4) { FocusRequester() } }

    BackHandler(onBack = onBack)

    fun otpCode(): String = digits.joinToString("")

    fun setStatus(message: String, type: AuthMessageType) {
        statusMessage = message
        statusType = type
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF8FAFF), Color(0xFFEEF4FF)),
                ),
            )
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = !isLoading) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = DarkBlue,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MyCrmSync",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DarkBlue,
                letterSpacing = (-0.5).sp,
            )

            Spacer(modifier = Modifier.height(24.dp))

            AppLogo(size = 180.dp)

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Enter verification code",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = DarkBlue,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "We sent a 4-digit code to\n$email",
                fontSize = 15.sp,
                color = TextSec,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )

            Text(
                text = "Code expires in 10 minutes.",
                fontSize = 13.sp,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(36.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "ONE-TIME CODE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSec,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        digits.forEachIndexed { index, digit ->
                            OtpDigitBox(
                                value = digit,
                                focusRequester = focusRequesters[index],
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }.take(1)
                                    val updated = digits.toMutableList()
                                    if (filtered.isEmpty() && newValue.isEmpty() && digit.isNotEmpty()) {
                                        updated[index] = ""
                                        digits = updated
                                        if (index > 0) focusRequesters[index - 1].requestFocus()
                                    } else if (filtered.isNotEmpty()) {
                                        updated[index] = filtered
                                        digits = updated
                                        if (index < 3) focusRequesters[index + 1].requestFocus()
                                    }
                                },
                                onPaste = { pasted ->
                                    val onlyDigits = pasted.filter { it.isDigit() }.take(4)
                                    if (onlyDigits.isNotEmpty()) {
                                        digits = List(4) { i -> onlyDigits.getOrNull(i)?.toString() ?: "" }
                                        val nextIndex = onlyDigits.length.coerceAtMost(3)
                                        focusRequesters[nextIndex].requestFocus()
                                    }
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            val code = otpCode()
                            if (code.length < 4) {
                                setStatus("Enter the 4-digit code from your email.", AuthMessageType.Error)
                                return@Button
                            }
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                AuthRepository.verifyEmailOtp(email, code)
                                    .onSuccess { data ->
                                        prefsManager.saveAuthSession(data)
                                        CallLogSyncScheduler.schedulePeriodicIfLoggedIn(
                                            context.applicationContext,
                                        )
                                        CallLogSyncScheduler.enqueueNow(context.applicationContext)
                                        onVerified()
                                    }
                                    .onFailure { e ->
                                        setStatus(
                                            e.message ?: "Invalid login code.",
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
                        enabled = !isLoading && otpCode().length == 4,
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Validate OTP",
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

            Spacer(modifier = Modifier.height(28.dp))

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

    LaunchedEffect(Unit) {
        focusRequesters[0].requestFocus()
    }
}

@Composable
private fun OtpDigitBox(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onPaste: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { newValue ->
            if (newValue.length > 1) {
                onPaste(newValue)
            } else {
                onValueChange(newValue)
            }
        },
        modifier = Modifier
            .size(56.dp)
            .focusRequester(focusRequester)
            .border(1.dp, DarkBlue.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .background(InputBg, RoundedCornerShape(12.dp)),
        textStyle = TextStyle(
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = DarkBlue,
            textAlign = TextAlign.Center,
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(DarkBlue),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                inner()
            }
        },
    )
}
