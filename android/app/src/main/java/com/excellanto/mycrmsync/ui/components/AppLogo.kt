package com.excellanto.mycrmsync.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.excellanto.mycrmsync.R

@Composable
fun AppLogo(size: Dp = 260.dp) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "App logo",
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f)),
        contentScale = ContentScale.Fit
    )
}
