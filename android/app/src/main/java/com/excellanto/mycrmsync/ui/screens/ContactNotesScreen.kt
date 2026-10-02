package com.excellanto.mycrmsync.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.text.util.Linkify
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.excellanto.mycrmsync.data.CallNoteText
import com.excellanto.mycrmsync.data.TimelineAttachment
import com.excellanto.mycrmsync.data.TimelineAttachmentKind
import com.excellanto.mycrmsync.data.TimelineNoteParser
import com.excellanto.mycrmsync.data.model.CrmNote
import com.excellanto.mycrmsync.ui.components.ApiSnackbarEffect
import com.excellanto.mycrmsync.ui.components.ApiSnackbarHost
import com.excellanto.mycrmsync.ui.components.rememberApiSnackbarHostState
import com.excellanto.mycrmsync.ui.theme.*
import com.excellanto.mycrmsync.ui.viewmodel.ContactNotesViewModel
import com.excellanto.mycrmsync.ui.viewmodel.NoteAttachmentPreview
import com.excellanto.mycrmsync.ui.viewmodel.ProcessedVoiceNote
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactNotesScreen(
    contactId: String,
    contactName: String,
    onBack: () -> Unit,
    viewModel: ContactNotesViewModel = viewModel()
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isProcessingVoiceNote by viewModel.isProcessingVoiceNote.collectAsStateWithLifecycle()
    val isSavingNote by viewModel.isSavingNote.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val hasRecordedAudio by viewModel.hasRecordedAudio.collectAsStateWithLifecycle()
    val amplitudes by viewModel.amplitudes.collectAsStateWithLifecycle()
    val processedVoiceNote by viewModel.processedVoiceNote.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val hasEditChanges by viewModel.hasEditChanges.collectAsStateWithLifecycle()
    val isUploadingAttachment by viewModel.isUploadingAttachment.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = rememberApiSnackbarHostState()

    ApiSnackbarEffect(
        message = error,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.consumeError() },
    )

    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var notePendingDelete by remember { mutableStateOf<CrmNote?>(null) }
    val listState = rememberLazyListState()
    var wasSavingNote by remember { mutableStateOf(false) }
    var showVoiceNoteOverlay by remember { mutableStateOf(false) }
    val imageAttachments = draft.attachments.filter { it.isImage }
    val fileAttachments = draft.attachments.filter { !it.isImage }
    val canSaveNewNote =
        (draft.typedText.isNotBlank() || draft.voiceNoteIds.isNotEmpty() || draft.attachments.isNotEmpty()) &&
            !isProcessingVoiceNote &&
            !isSavingNote &&
            !isUploadingAttachment
    val canSaveNote =
        if (editingNoteId != null) {
            hasEditChanges && !isProcessingVoiceNote && !isSavingNote && !isUploadingAttachment
        } else {
            canSaveNewNote
        }

    LaunchedEffect(isProcessingVoiceNote) {
        if (isProcessingVoiceNote) {
            delay(32)
            showVoiceNoteOverlay = true
        } else {
            showVoiceNoteOverlay = false
        }
    }
    
    val context = LocalContext.current
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.uploadContactFile(contactId, it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.uploadContactFile(contactId, it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        }
    }

    LaunchedEffect(contactId) {
        viewModel.initDraft(contactId)
        viewModel.fetchNotes(contactId)
    }

    LaunchedEffect(isSavingNote) {
        if (wasSavingNote && !isSavingNote && error == null && notes.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
        wasSavingNote = isSavingNote
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = LightBg,
        snackbarHost = { ApiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(contactName, fontWeight = FontWeight.Bold, color = TextMain) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchNotes(contactId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightBg)
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "note_form") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(LightBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = TextSec, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            TextField(
                                value = draft.typedText,
                                onValueChange = { viewModel.updateTypedText(it) },
                                placeholder = { 
                                    Text(
                                        "Type a note or record a voice update...", 
                                        color = TextSec, 
                                        fontSize = 15.sp 
                                    ) 
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = TextMain,
                                    unfocusedTextColor = TextMain,
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            if (isUploadingAttachment) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = PrimaryIndigo,
                                    )
                                    Text("Uploading attachment…", color = TextSec, fontSize = 13.sp)
                                }
                            }

                            if (fileAttachments.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    fileAttachments.forEach { attachment ->
                                        AttachmentChip(
                                            attachment = attachment,
                                            onRemove = { viewModel.removeAttachment(attachment.voiceNoteId) },
                                        )
                                    }
                                }
                            }

                            // Image previews in input area
                            if (imageAttachments.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    imageAttachments.forEach { attachment ->
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        ) {
                                            AsyncImage(
                                                model = attachment.displayModel,
                                                contentDescription = attachment.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                            IconButton(
                                                onClick = { viewModel.removeAttachment(attachment.voiceNoteId) },
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(24.dp)
                                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (isRecording || hasRecordedAudio) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .background(InputBg, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (hasRecordedAudio && !isProcessingVoiceNote) {
                                        IconButton(onClick = { viewModel.playRecording() }) {
                                            Icon(
                                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                                contentDescription = "Play/Pause", 
                                                tint = PrimaryIndigo, 
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    RecordingWaveform(amplitudes = amplitudes, modifier = Modifier.weight(1f))
                                    
                                    if (hasRecordedAudio && !isProcessingVoiceNote) {
                                        IconButton(onClick = { viewModel.deleteRecording() }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Recording", tint = ErrorRed, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }

                            processedVoiceNote?.let { voiceNote ->
                                Spacer(modifier = Modifier.height(12.dp))
                                ProcessedVoiceNoteCard(
                                    voiceNote = voiceNote,
                                    isPlaying = isPlaying,
                                    onPlayPause = { viewModel.playProcessedVoiceNote() },
                                    onDismiss = { viewModel.clearProcessedVoiceNote() },
                                    onSummaryChange = { viewModel.updateProcessedVoiceNoteSummary(it) },
                                )
                            }
                        }
                    }
                    }

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        ),
                                    )
                                },
                                enabled = !isUploadingAttachment && !isProcessingVoiceNote && !isSavingNote,
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = TextSec)
                            }
                            IconButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                enabled = !isUploadingAttachment && !isProcessingVoiceNote && !isSavingNote,
                            ) {
                                Icon(Icons.Default.Image, contentDescription = "Photo", tint = TextSec)
                            }
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Voice / Stop / Upload Button
                            val infiniteTransition = rememberInfiniteTransition(label = "recording")
                            val recordingAlpha by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.4f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "alpha"
                            )

                            Button(
                                onClick = { 
                                    when {
                                        isRecording -> viewModel.stopRecording()
                                        hasRecordedAudio -> viewModel.uploadAndProcessRecording(contactId, draft.typedText)
                                        else -> {
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                viewModel.startRecording()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isRecording -> ErrorRed.copy(alpha = recordingAlpha)
                                        hasRecordedAudio -> SuccessGreen // Green for upload
                                        else -> LightIndigo
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(52.dp),
                                enabled = !isProcessingVoiceNote || isRecording
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        when {
                                            isRecording -> Icons.Default.Stop
                                            hasRecordedAudio -> Icons.Default.AutoAwesome
                                            else -> Icons.Default.Mic
                                        },
                                        contentDescription = null,
                                        tint = if (isRecording || hasRecordedAudio) Color.White else PrimaryIndigo,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isRecording -> "Stop Rec"
                                            hasRecordedAudio -> "Upload to AI"
                                            else -> "Voice Note"
                                        },
                                        color = if (isRecording || hasRecordedAudio) Color.White else PrimaryIndigo,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        softWrap = false,
                                        maxLines = 1
                                    )
                                }
                            }
                            
                            // Save Note Button
                            Button(
                                onClick = {
                                    val currentEditingId = editingNoteId
                                    if (currentEditingId != null) {
                                        viewModel.updateNote(contactId, currentEditingId)
                                        editingNoteId = null
                                        viewModel.clearDraft()
                                    } else {
                                        viewModel.saveNote(contactId)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkBlue,
                                    disabledContainerColor = DarkBlue.copy(alpha = 0.5f),
                                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = canSaveNote,
                                modifier = Modifier.height(52.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Text(
                                    "Save Note",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    
                    if (editingNoteId != null) {
                        TextButton(
                            onClick = {
                                editingNoteId = null
                                viewModel.clearDraft()
                            },
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Text("Cancel Editing", color = ErrorRed, fontSize = 12.sp)
                        }
                    }
                    }
                }
            }
            }

            item(key = "timeline_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Timeline", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text("Filter: All Notes ▾", color = TextSec, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isLoading && notes.isEmpty()) {
                item(key = "timeline_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = PrimaryIndigo)
                    }
                }
            } else if (notes.isEmpty()) {
                item(key = "timeline_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No Notes added for this Contact",
                            color = TextSec,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                itemsIndexed(notes, key = { _, note -> note.id }) { _, note ->
                    val userName = note.userName?.takeIf { it.isNotBlank() }
                        ?: users[note.userId]?.name
                        ?: "System"
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        TimelineItem(
                            note = note,
                            userName = userName,
                            onEdit = {
                                editingNoteId = note.id
                                viewModel.loadNoteForEdit(note)
                            },
                            onDelete = {
                                notePendingDelete = note
                            },
                        )
                    }
                }
            }
        }
    }

        if (isSavingNote || showVoiceNoteOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        }

        notePendingDelete?.let { note ->
            AlertDialog(
                onDismissRequest = { notePendingDelete = null },
                title = { Text("Delete note?") },
                text = {
                    Text(
                        "This note will be permanently removed from this contact.",
                        color = TextSec,
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            notePendingDelete = null
                            viewModel.deleteNote(contactId, note.id)
                        },
                    ) {
                        Text("Delete", color = ErrorRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { notePendingDelete = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: NoteAttachmentPreview,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LightIndigo,
        border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                tint = PrimaryIndigo,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.widthIn(max = 140.dp)) {
                Text(
                    attachment.name,
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    attachment.fileType.uppercase(Locale.ENGLISH),
                    color = TextSec,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextSec, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun ProcessedVoiceNoteCard(
    voiceNote: ProcessedVoiceNote,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    onSummaryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(40.dp)
                        .background(LightIndigo, CircleShape),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play voice note",
                        tint = PrimaryIndigo,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text(
                        "Voice Note",
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                        fontSize = 14.sp,
                    )
                    val durationLabel =
                        voiceNote.durationSec?.let { sec ->
                            val m = sec / 60
                            val s = sec % 60
                            if (m > 0) "${m}:${s.toString().padStart(2, '0')}" else "${s}s"
                        }
                    Text(
                        listOfNotNull(voiceNote.fileName, durationLabel)
                            .joinToString(" · "),
                        color = TextSec,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSec, modifier = Modifier.size(18.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardWhite, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Summary", fontWeight = FontWeight.Bold, color = PrimaryIndigo, fontSize = 12.sp)
            EditableExpandableNoteText(
                text = voiceNote.summary,
                onTextChange = onSummaryChange,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardWhite, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Transcription", fontWeight = FontWeight.Bold, color = PrimaryIndigo, fontSize = 12.sp)
            ExpandableNoteText(text = voiceNote.transcription)
        }
    }
}

private const val COLLAPSED_TEXT_MAX_LINES = 4

@Composable
private fun EditableExpandableNoteText(
    text: String,
    onTextChange: (String) -> Unit,
    collapsedMaxLines: Int = COLLAPSED_TEXT_MAX_LINES,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val showFullText = expanded || isFocused
    val noteTextStyle = TextStyle(color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val isExpandable =
            remember(text, maxWidthPx) {
                if (text.isBlank()) {
                    false
                } else {
                    textMeasurer.measure(
                        text = text,
                        style = noteTextStyle,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxWidthPx),
                    ).lineCount > collapsedMaxLines
                }
            }

        Column {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = if (showFullText) Int.MAX_VALUE else collapsedMaxLines,
                interactionSource = interactionSource,
                textStyle = noteTextStyle,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PrimaryIndigo,
                ),
            )
            if (isExpandable && !isFocused) {
                ExpandCollapseToggle(expanded = expanded, onToggle = { expanded = !expanded })
            }
        }
    }
}

@Composable
private fun ExpandCollapseToggle(expanded: Boolean, onToggle: () -> Unit) {
    Text(
        text = if (expanded) "Show less ▴" else "Show more ▾",
        color = PrimaryIndigo,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(onClick = onToggle),
    )
}

@Composable
private fun ExpandableNoteText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = COLLAPSED_TEXT_MAX_LINES,
    forceExpanded: Boolean = false,
) {
    var expanded by remember(text, forceExpanded) { mutableStateOf(forceExpanded) }
    var isExpandable by remember(text) { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = text,
            color = TextMain,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = if (expanded || forceExpanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                if (!expanded && !forceExpanded) {
                    isExpandable = layoutResult.hasVisualOverflow
                }
            },
        )
        if (isExpandable && !forceExpanded) {
            ExpandCollapseToggle(expanded = expanded, onToggle = { expanded = !expanded })
        }
    }
}

@Composable
fun RecordingWaveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        amplitudes.forEach { amplitude ->
            val barHeight = (amplitude * 30).dp.coerceAtLeast(4.dp)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PrimaryIndigo)
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
}

@Composable
fun TimelineItem(
    note: CrmNote,
    userName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var showFullscreenImage by remember { mutableStateOf<String?>(null) }

    val parsedNote = remember(note.id, note.body, note.attachments, note.summary, note.transcription) {
        TimelineNoteParser.parse(note)
    }
    val voiceAttachments = remember(note.id, note.body, note.attachments, note.voiceNoteIds) {
        TimelineNoteParser.voicePlaybackAttachments(note)
    }
    val imageAttachments = parsedNote.attachments.filter { it.kind == TimelineAttachmentKind.IMAGE }
    val documentAttachments = parsedNote.attachments.filter { it.kind == TimelineAttachmentKind.DOCUMENT }

    val formattedDate = remember(note.createdAt, note.dateAdded) {
        try {
            val dateStr = note.createdAt ?: note.dateAdded ?: ""
            val zonedDateTime = ZonedDateTime.parse(dateStr)
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)
            zonedDateTime.format(formatter)
        } catch (e: Exception) {
            "Unknown Date"
        }
    }

    val callKind = remember(parsedNote.introText) { CallNoteText.kindOf(parsedNote.introText) }
    val displayIntro = remember(parsedNote.introText) { CallNoteText.present(parsedNote.introText) }
    val icon = remember(parsedNote, callKind) {
        when (callKind) {
            CallNoteText.Kind.MISSED -> Icons.AutoMirrored.Filled.CallMissed
            CallNoteText.Kind.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
            CallNoteText.Kind.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
            null ->
                when (parsedNote.primaryIconKind) {
                    TimelineAttachmentKind.AUDIO -> Icons.Default.Mic
                    TimelineAttachmentKind.IMAGE -> Icons.Default.Image
                    TimelineAttachmentKind.DOCUMENT -> Icons.Default.AttachFile
                    null ->
                        if (parsedNote.hasVoiceContent) Icons.Default.Mic else Icons.AutoMirrored.Filled.Chat
                }
        }
    }
    val iconTint =
        when (callKind) {
            CallNoteText.Kind.MISSED -> ErrorRed
            CallNoteText.Kind.INCOMING -> SuccessGreen
            CallNoteText.Kind.OUTGOING -> PrimaryBlue
            null -> TextSec
        }
    val iconBg = if (callKind == null) LightIndigo else iconTint.copy(alpha = 0.12f)

    val hasExpandableContent =
        displayIntro.length > 180 ||
            (parsedNote.summary?.length ?: 0) > 180 ||
            (parsedNote.transcription?.length ?: 0) > 180 ||
            imageAttachments.size > 1

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier.width(40.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(LightIndigo)
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formattedDate, color = TextSec, fontSize = 12.sp)
                    Text("  •  ", color = TextSec, fontSize = 12.sp)
                    Text("Added by $userName", fontWeight = FontWeight.Bold, color = TextMain, fontSize = 12.sp)
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSec, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = ErrorRed) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                border = BorderStroke(1.dp, Color(0xFFF1F5F9))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (displayIntro.isNotBlank()) {
                        ExpandableNoteText(
                            text = displayIntro,
                            forceExpanded = isExpanded,
                        )
                    }

                    parsedNote.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        TimelineNoteSection(
                            label = "Summary",
                            icon = Icons.Default.AutoAwesome,
                            text = summary,
                            forceExpanded = isExpanded,
                        )
                    }

                    parsedNote.transcription?.takeIf { it.isNotBlank() }?.let { transcription ->
                        TimelineNoteSection(
                            label = "Transcription",
                            icon = Icons.Default.Subject,
                            text = transcription,
                            forceExpanded = isExpanded,
                        )
                    }

                    if (voiceAttachments.isNotEmpty()) {
                        voiceAttachments.forEach { attachment ->
                            TimelineAudioAttachment(attachment = attachment)
                        }
                    } else if (parsedNote.hasVoiceContent) {
                        TimelineVoiceNoteUnavailableCard()
                    }

                    if (imageAttachments.isNotEmpty()) {
                        TimelineImageAttachments(
                            attachments = imageAttachments,
                            expanded = isExpanded,
                            onImageClick = { showFullscreenImage = it },
                        )
                    }

                    documentAttachments.forEach { attachment ->
                        TimelineDocumentAttachment(attachment = attachment)
                    }

                    if (hasExpandableContent) {
                        ExpandCollapseToggle(
                            expanded = isExpanded,
                            onToggle = { isExpanded = !isExpanded },
                        )
                    }
                }
            }
        }
    }

    if (showFullscreenImage != null) {
        FullscreenImageDialog(url = showFullscreenImage!!, onDismiss = { showFullscreenImage = null })
    }
}

@Composable
private fun TimelineNoteSection(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    forceExpanded: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBg, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = PrimaryIndigo, modifier = Modifier.size(16.dp))
            Text(label, fontWeight = FontWeight.Bold, color = PrimaryIndigo, fontSize = 13.sp)
        }
        ExpandableNoteText(
            text = text,
            forceExpanded = forceExpanded,
        )
    }
}

@Composable
private fun TimelineImageAttachments(
    attachments: List<TimelineAttachment>,
    expanded: Boolean,
    onImageClick: (String) -> Unit,
) {
    val displayImages = if (expanded) attachments else attachments.take(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        displayImages.forEach { attachment ->
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .heightIn(max = if (expanded) 400.dp else 120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onImageClick(attachment.url) }
            ) {
                AsyncImage(
                    model = attachment.url,
                    contentDescription = attachment.name,
                    modifier = Modifier.wrapContentWidth().heightIn(max = if (expanded) 400.dp else 120.dp),
                    contentScale = if (expanded) ContentScale.Fit else ContentScale.Crop,
                )
            }
        }
        if (!expanded && attachments.size > 1) {
            Text(
                "+${attachments.size - 1} more images",
                fontSize = 11.sp,
                color = TextSec,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TimelineVoiceNoteUnavailableCard() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = LightIndigo,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = PrimaryIndigo, modifier = Modifier.size(20.dp))
            Text(
                "Voice note — playback URL not available",
                color = TextSec,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TimelineAudioAttachment(attachment: TimelineAttachment) {
    val context = LocalContext.current
    var isPlaying by remember(attachment.url, attachment.shortUrl) { mutableStateOf(false) }
    val mediaPlayer = remember(attachment.url, attachment.shortUrl) { MediaPlayer() }
    val playbackCandidates =
        remember(attachment.url, attachment.shortUrl) {
            listOfNotNull(
                attachment.url.trim().takeIf { it.isNotBlank() },
                attachment.shortUrl?.trim()?.takeIf { it.isNotBlank() },
            ).distinct()
        }
    val openUrl = attachment.shortUrl ?: attachment.url

    DisposableEffect(attachment.url, attachment.shortUrl) {
        onDispose {
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
        }
    }

    fun openExternally() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
        }
    }

    fun tryPlay(index: Int) {
        if (index >= playbackCandidates.size) {
            isPlaying = false
            openExternally()
            return
        }
        val url = playbackCandidates[index]
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
            mediaPlayer.setOnPreparedListener { player ->
                player.start()
                isPlaying = true
            }
            mediaPlayer.setOnCompletionListener { isPlaying = false }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                isPlaying = false
                tryPlay(index + 1)
                true
            }
        }.onFailure {
            tryPlay(index + 1)
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = LightIndigo,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        runCatching { mediaPlayer.pause() }
                        isPlaying = false
                    } else {
                        tryPlay(0)
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause voice note" else "Play voice note",
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { openExternally() },
            ) {
                Text("Play voice note", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    attachment.fileType?.uppercase(Locale.ENGLISH) ?: "AUDIO",
                    color = TextSec,
                    fontSize = 11.sp,
                )
            }
            Icon(Icons.Default.Mic, contentDescription = null, tint = PrimaryIndigo, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TimelineDocumentAttachment(attachment: TimelineAttachment) {
    val context = LocalContext.current
    val fileType = attachment.fileType?.lowercase().orEmpty()
    val label =
        when (fileType) {
            "pdf" -> "View PDF"
            "doc", "docx" -> "View Word document"
            else ->
                when {
                    attachment.name.endsWith(".pdf", ignoreCase = true) -> "View PDF"
                    attachment.name.endsWith(".doc", ignoreCase = true) ||
                        attachment.name.endsWith(".docx", ignoreCase = true) -> "View Word document"
                    else -> "View document"
                }
        }
    val openUrl = attachment.shortUrl ?: attachment.url

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = LightIndigo,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (fileType == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description,
                contentDescription = null,
                tint = PrimaryIndigo,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    attachment.name,
                    color = PrimaryIndigo,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.Underline,
                )
            }
            Icon(Icons.Default.OpenInNew, contentDescription = "Open document", tint = TextSec, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun FullscreenImageDialog(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = url,
                contentDescription = "Fullscreen Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: Float = 14f,
    maxLines: Int = Int.MAX_VALUE,
    onOverflow: (Boolean) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                textSize = fontSize
                if (color != Color.Unspecified) {
                    setTextColor(color.toArgb())
                }
                autoLinkMask = Linkify.WEB_URLS
                linksClickable = true
            }
        },
        update = {
            it.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            it.maxLines = maxLines
            it.ellipsize = android.text.TextUtils.TruncateAt.END
            
            it.post {
                val layout = it.layout
                if (layout != null) {
                    val lines = layout.lineCount
                    if (lines > 0) {
                        val overflow = layout.getEllipsisCount(lines - 1) > 0 || lines > maxLines
                        onOverflow(overflow)
                    }
                }
            }
        }
    )
}
