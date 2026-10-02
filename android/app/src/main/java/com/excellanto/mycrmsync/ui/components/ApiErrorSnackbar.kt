package com.excellanto.mycrmsync.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.paging.LoadState
import com.excellanto.mycrmsync.ui.theme.ErrorRed

/**
 * Shows transient API / action messages in a bottom snackbar and clears them via [onConsumed].
 */
@Composable
fun ApiSnackbarEffect(
    message: String?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = text,
            duration = SnackbarDuration.Long,
        )
        onConsumed()
    }
}

/**
 * Shows paging [LoadState.Error] messages in a bottom snackbar when refresh/append fails.
 */
@Composable
fun PagingErrorSnackbarEffect(
    refreshState: LoadState,
    appendState: LoadState,
    snackbarHostState: SnackbarHostState,
    refreshFallback: String = "Failed to load data",
    appendFallback: String = "Failed to load more",
) {
    LaunchedEffect(refreshState) {
        val err = refreshState as? LoadState.Error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = err.error.localizedMessage?.trim().orEmpty().ifBlank { refreshFallback },
            duration = SnackbarDuration.Long,
        )
    }
    LaunchedEffect(appendState) {
        val err = appendState as? LoadState.Error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = err.error.localizedMessage?.trim().orEmpty().ifBlank { appendFallback },
            duration = SnackbarDuration.Long,
        )
    }
}

@Composable
fun rememberApiSnackbarHostState(): SnackbarHostState = remember { SnackbarHostState() }

@Composable
fun ApiSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                containerColor = ErrorRed,
                contentColor = Color.White,
                actionColor = Color.White,
            )
        },
    )
}
