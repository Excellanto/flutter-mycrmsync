package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmTag
import com.excellanto.mycrmsync.ui.components.ApiSnackbarEffect
import com.excellanto.mycrmsync.ui.components.ApiSnackbarHost
import com.excellanto.mycrmsync.ui.components.AppLoadingBox
import com.excellanto.mycrmsync.ui.components.ContactListCard
import com.excellanto.mycrmsync.ui.components.PagingErrorSnackbarEffect
import com.excellanto.mycrmsync.ui.components.rememberApiSnackbarHostState
import com.excellanto.mycrmsync.ui.theme.*
import com.excellanto.mycrmsync.ui.businesscard.rememberBusinessCardScanHandler
import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm
import com.excellanto.mycrmsync.ui.viewmodel.AddContactUiState
import com.excellanto.mycrmsync.ui.viewmodel.ContactViewModel
import com.excellanto.mycrmsync.ui.viewmodel.TagSelectionDialogCloseEvent
import kotlinx.coroutines.flow.collectLatest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactViewModel = viewModel(),
    onCallClick: (String) -> Unit = {},
    onNavigateToNotes: (String, String) -> Unit = { _, _ -> },
    onMenuClick: () -> Unit = {},
) {
    val contacts = viewModel.contacts.collectAsLazyPagingItems()
    val allTags by viewModel.tags.collectAsStateWithLifecycle()
    val totalContacts by viewModel.totalContacts.collectAsStateWithLifecycle()
    val tagOverrides by viewModel.tagOverrides.collectAsStateWithLifecycle()
    val contactOverrides by viewModel.contactOverrides.collectAsStateWithLifecycle()
    val tagSnackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val addContactState by viewModel.addContactState.collectAsStateWithLifecycle()
    val isAddingContact = addContactState is AddContactUiState.Loading

    LaunchedEffect(Unit) {
        viewModel.requestPagingRefresh.collectLatest {
            contacts.refresh()
        }
    }

    var searchQuery by remember { mutableStateOf("") }

    var showTagDialog by remember { mutableStateOf(false) }
    var showContactForm by remember { mutableStateOf(false) }
    var contactFormTarget by remember { mutableStateOf<CrmContact?>(null) }
    var contactFormPrefill by remember { mutableStateOf<AddContactForm?>(null) }
    var contactFormPrefillKey by remember { mutableIntStateOf(0) }
    var isScanPrefillLoading by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<CrmContact?>(null) }
    var isTagUpdateInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.contactFormClose.collectLatest {
            contacts.refresh()
            showContactForm = false
            contactFormTarget = null
            contactFormPrefill = null
            isScanPrefillLoading = false
            viewModel.resetAddContactState()
        }
    }

    val startBusinessCardScan =
        rememberBusinessCardScanHandler(
            onScanStarted = {
                contactFormTarget = null
                contactFormPrefill = null
                contactFormPrefillKey++
                showContactForm = true
                isScanPrefillLoading = true
            },
            onParsed = { form ->
                contactFormPrefill = form
                contactFormPrefillKey++
                isScanPrefillLoading = false
            },
            onError = { message ->
                viewModel.showSnackbar(message)
            },
        )

    LaunchedEffect(Unit) {
        viewModel.tagSelectionDialogClose.collectLatest { event ->
            when (event) {
                TagSelectionDialogCloseEvent.Aborted,
                TagSelectionDialogCloseEvent.Succeeded,
                -> {
                    showTagDialog = false
                    editingContact = null
                    isTagUpdateInProgress = false
                }
                TagSelectionDialogCloseEvent.Failed -> {
                    isTagUpdateInProgress = false
                }
            }
        }
    }

    val snackbarHostState = rememberApiSnackbarHostState()
    ApiSnackbarEffect(
        message = tagSnackbarMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.consumeSnackbarMessage() },
    )
    PagingErrorSnackbarEffect(
        refreshState = contacts.loadState.refresh,
        appendState = contacts.loadState.append,
        snackbarHostState = snackbarHostState,
        refreshFallback = "Failed to load contacts",
        appendFallback = "Failed to load more contacts",
    )

    Scaffold(
        containerColor = LightBg,
        snackbarHost = { ApiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Contacts", fontWeight = FontWeight.Bold, color = TextMain) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightBg),
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { fabMenuExpanded = true },
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Contact actions")
                }
                DropdownMenu(
                    expanded = fabMenuExpanded,
                    onDismissRequest = { fabMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Add Contact") },
                        onClick = {
                            fabMenuExpanded = false
                            contactFormTarget = null
                            contactFormPrefill = null
                            showContactForm = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Scan Business Card") },
                        onClick = {
                            fabMenuExpanded = false
                            startBusinessCardScan()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = "Total Contacts: $totalContacts",
                    color = TextSec,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.onSearch(it)
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardWhite),
                        placeholder = {
                            Text("Search contacts, companies...", color = TextSec, fontSize = 14.sp)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextSec)
                        },
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = CardWhite,
                                unfocusedContainerColor = CardWhite,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LightIndigo),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = DarkBlue)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilterDropdown(
                        label = "Tags",
                        options = listOf("All Tags") + allTags.map { it.name },
                        onSelected = { viewModel.onTagSelected(it) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterDropdown(
                        label = "Contact Reached",
                        options = listOf("All Contacts", "Zero Calls Made", "At least 1 Call Received"),
                        onSelected = { viewModel.onContactReachedSelected(it) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(
                        count = contacts.itemCount,
                        key = contacts.itemKey { it.id },
                        contentType = contacts.itemContentType { "contact" },
                    ) { index ->
                        val contact = contacts[index]
                        contact?.let { raw ->
                            LaunchedEffect(raw.id, raw.tags) {
                                viewModel.reconcileTagOverrideIfSynced(raw)
                            }
                            LaunchedEffect(raw.id, raw.firstName, raw.lastName, raw.phone, raw.email, raw.companyName) {
                                viewModel.reconcileContactOverrideIfSynced(raw)
                            }
                            val displayContact =
                                remember(raw.id, raw, tagOverrides[raw.id], contactOverrides[raw.id]) {
                                    val overridden = contactOverrides[raw.id]
                                    val base =
                                        if (overridden != null) {
                                            overridden.copy(calls = raw.calls)
                                        } else {
                                            raw
                                        }
                                    tagOverrides[raw.id]?.let { tags -> base.copy(tags = tags) } ?: base
                                }
                            ContactListCard(
                                contact = displayContact,
                                onCallClick = onCallClick,
                                onNotesClick = { id, name ->
                                    val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
                                    onNavigateToNotes(id, encodedName)
                                },
                                onEditClick = {
                                    contactFormTarget = displayContact
                                    showContactForm = true
                                },
                                onTagClick = {
                                    editingContact = raw
                                    showTagDialog = true
                                },
                            )
                        }
                    }

                    contacts.apply {
                        when (loadState.refresh) {
                            is LoadState.Loading -> {
                                if (itemCount == 0) {
                                    item { AppLoadingBox() }
                                }
                            }
                            is LoadState.Error -> {
                                if (itemCount == 0) {
                                    item {
                                        ContactsEmptyState(onRetry = { retry() })
                                    }
                                }
                            }
                            else -> {}
                        }
                        when (loadState.append) {
                            is LoadState.Loading -> {
                                item { AppLoadingBox() }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    if (showContactForm) {
        AddContactSheet(
            availableTags = allTags,
            initialContact = contactFormTarget,
            initialForm = contactFormPrefill,
            initialFormKey = contactFormPrefillKey,
            isSubmitting = isAddingContact,
            isLoadingPrefill = isScanPrefillLoading,
            onDismiss = {
                if (!isAddingContact) {
                    showContactForm = false
                    contactFormTarget = null
                    contactFormPrefill = null
                    isScanPrefillLoading = false
                    viewModel.resetAddContactState()
                }
            },
            onSubmit = { form -> viewModel.saveContact(form) },
            onDelete = { contactId -> viewModel.deleteContact(contactId) },
        )
    }

    if (showTagDialog && editingContact != null) {
        val ec = editingContact!!
        TagSelectionDialog(
            availableTags = allTags,
            currentTags = tagOverrides[ec.id] ?: ec.tags.orEmpty(),
            isUpdating = isTagUpdateInProgress,
            onSave = { selectedTags ->
                if (isTagUpdateInProgress) return@TagSelectionDialog
                isTagUpdateInProgress = true
                viewModel.updateContactTags(ec, selectedTags)
            },
            onDismiss = {
                if (isTagUpdateInProgress) return@TagSelectionDialog
                showTagDialog = false
                editingContact = null
            },
        )
    }
}

@Composable
fun TagSelectionDialog(
    availableTags: List<CrmTag>,
    currentTags: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    isUpdating: Boolean = false,
    title: String = "Change Tags",
    confirmLabel: String = "Save",
) {
    var selectedTags by remember(currentTags) { mutableStateOf(currentTags) }
    var newTagInput by remember { mutableStateOf("") }

    fun isSelected(name: String): Boolean =
        selectedTags.any { it.equals(name, ignoreCase = true) }

    fun toggleTag(name: String) {
        selectedTags =
            if (isSelected(name)) {
                selectedTags.filterNot { it.equals(name, ignoreCase = true) }
            } else {
                selectedTags + name
            }
    }

    fun addNewTag() {
        val trimmed = newTagInput.trim()
        if (trimmed.isBlank()) return
        if (!isSelected(trimmed)) {
            selectedTags = selectedTags + trimmed
        }
        newTagInput = ""
    }

    val displayTags =
        remember(availableTags, selectedTags) {
            val fromApi = availableTags.map { it.name }
            val customSelected =
                selectedTags.filter { selected ->
                    fromApi.none { it.equals(selected, ignoreCase = true) }
                }
            fromApi + customSelected
        }

    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Add new tag") },
                        singleLine = true,
                        enabled = !isUpdating,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addNewTag() }),
                    )
                    TextButton(
                        onClick = { addNewTag() },
                        enabled = !isUpdating && newTagInput.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (displayTags.isEmpty()) {
                    Text(
                        text = "No tags yet. Add one above.",
                        fontSize = 14.sp,
                        color = TextSec,
                    )
                } else {
                    displayTags.forEach { tagName ->
                        val checked = isSelected(tagName)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isUpdating) { toggleTag(tagName) }
                                    .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (!isUpdating) toggleTag(tagName)
                                },
                                enabled = !isUpdating,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = tagName, fontSize = 16.sp)
                        }
                    }
                }

                if (isUpdating) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryBlue,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Updating tags…",
                            fontSize = 13.sp,
                            color = TextMain,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selectedTags) },
                enabled = !isUpdating,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUpdating) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun FilterDropdown(
    label: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf(label) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.dp, LightBg, RoundedCornerShape(8.dp))
                    .background(CardWhite, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selectedOption,
                color = TextSec,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSec)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.45f),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selectedOption = option
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ContactsEmptyState(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.People, contentDescription = null, tint = TextSec)
        Spacer(modifier = Modifier.height(8.dp))
        Text("No contacts found", color = TextMain, fontWeight = FontWeight.Bold)
        Text("Check your connection and try again.", color = TextSec, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text("Retry", color = PrimaryBlue)
        }
    }
}
