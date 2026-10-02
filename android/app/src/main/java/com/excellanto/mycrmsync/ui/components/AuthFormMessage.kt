package com.excellanto.mycrmsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.ui.theme.DarkBlue
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.SuccessGreen
enum class AuthMessageType { Success, Error, Info }

@Composable
fun AuthFormMessage(
    message: String,
    type: AuthMessageType,
    modifier: Modifier = Modifier,
) {
    val (background, textColor) = when (type) {
        AuthMessageType.Success -> Color(0xFFD1FAE5) to SuccessGreen
        AuthMessageType.Error -> Color(0xFFFEE2E2) to ErrorRed
        AuthMessageType.Info -> Color(0xFFE0E9FF) to DarkBlue
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = background,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

/** Dark primary button colors — avoids Material dynamic theme washing out disabled state. */
object AuthPrimaryButtonColors {
    @Composable
    fun colors() = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = DarkBlue,
        contentColor = Color.White,
        disabledContainerColor = DarkBlue.copy(alpha = 0.72f),
        disabledContentColor = Color.White.copy(alpha = 0.92f),
    )
}
