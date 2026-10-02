package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmTag
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.LightIndigo
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextGray
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(
    availableTags: List<CrmTag>,
    isSubmitting: Boolean,
    isLoadingPrefill: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (AddContactForm) -> Unit,
    onDelete: (String) -> Unit = {},
    initialContact: CrmContact? = null,
    initialForm: AddContactForm? = null,
    initialFormKey: Int = 0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditMode = initialContact != null
    val formSeed = initialContact?.id ?: "scan-$initialFormKey"

    var showDeleteConfirm by remember { mutableStateOf(false) }

    var firstName by remember(formSeed) {
        mutableStateOf(initialContact?.firstName ?: initialForm?.firstName.orEmpty())
    }
    var lastName by remember(formSeed) {
        mutableStateOf(initialContact?.lastName ?: initialForm?.lastName.orEmpty())
    }
    var email by remember(formSeed) {
        mutableStateOf(initialContact?.email ?: initialForm?.email.orEmpty())
    }
    var phone by remember(formSeed) {
        mutableStateOf(initialContact?.phone ?: initialForm?.phone.orEmpty())
    }
    var companyName by remember(formSeed) {
        mutableStateOf(initialContact?.companyName ?: initialForm?.companyName.orEmpty())
    }
    var address by remember(formSeed) {
        mutableStateOf(initialContact?.address ?: initialForm?.address.orEmpty())
    }
    var city by remember(formSeed) {
        mutableStateOf(initialContact?.city ?: initialForm?.city.orEmpty())
    }
    var state by remember(formSeed) {
        mutableStateOf(initialContact?.state ?: initialForm?.state.orEmpty())
    }
    var postalCode by remember(formSeed) {
        mutableStateOf(initialContact?.postalCode ?: initialForm?.postalCode.orEmpty())
    }
    var country by remember(formSeed) {
        mutableStateOf(initialContact?.country ?: initialForm?.country.orEmpty())
    }
    var selectedTags by remember(formSeed) {
        mutableStateOf(initialContact?.tags ?: initialForm?.tags.orEmpty())
    }
    var showTagDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting && !isLoadingPrefill) onDismiss() },
        sheetState = sheetState,
        containerColor = CardWhite,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (isEditMode) "Edit Contact" else "Add Contact",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                )
                IconButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting && !isLoadingPrefill,
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSec)
                }
            }

            Text(
                text =
                    when {
                        isLoadingPrefill -> "Reading your business card…"
                        isEditMode ->
                            "Update contact details. First name and phone or email are required."
                        initialForm != null ->
                            "Review scanned details and edit anything before saving."
                        else ->
                            "Create a new CRM contact. First name and phone or email are required."
                    },
                fontSize = 14.sp,
                color = TextSec,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (isLoadingPrefill) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PrimaryBlue)
                        Spacer(Modifier.height(16.dp))
                        Text("Parsing business card…", color = TextSec, fontSize = 14.sp)
                    }
                }
            } else {
                ContactFormField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = "First name *",
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ContactFormField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = "Last name",
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ContactFormField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "Phone",
                    keyboardType = KeyboardType.Phone,
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ContactFormField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    keyboardType = KeyboardType.Email,
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ContactFormField(
                    value = companyName,
                    onValueChange = { companyName = it },
                    label = "Company",
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ContactFormField(
                    value = address,
                    onValueChange = { address = it },
                    label = "Address",
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ContactFormField(
                        value = city,
                        onValueChange = { city = it },
                        label = "City",
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmitting,
                    )
                    ContactFormField(
                        value = state,
                        onValueChange = { state = it },
                        label = "State",
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmitting,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ContactFormField(
                        value = postalCode,
                        onValueChange = { postalCode = it },
                        label = "Postal code",
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmitting,
                    )
                    ContactFormField(
                        value = country,
                        onValueChange = { country = it },
                        label = "Country",
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmitting,
                    )
                }

                Spacer(Modifier.height(16.dp))
                TagsSection(
                    selectedTags = selectedTags,
                    enabled = !isSubmitting,
                    onEditTags = { showTagDialog = true },
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        onSubmit(
                            AddContactForm(
                                contactId = initialContact?.id,
                                firstName = firstName,
                                lastName = lastName,
                                email = email,
                                phone = phone,
                                companyName = companyName,
                                address = address,
                                city = city,
                                state = state,
                                postalCode = postalCode,
                                country = country,
                                tags = selectedTags,
                            ),
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            if (isEditMode) "Save Changes" else "Save Contact",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Cancel", color = TextSec)
                }

                if (isEditMode) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Delete Contact", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && initialContact != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isSubmitting) showDeleteConfirm = false
            },
            title = { Text("Delete contact?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "${initialContact.fullName} will be permanently removed from your CRM.",
                    color = TextSec,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(initialContact.id)
                    },
                    enabled = !isSubmitting,
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    enabled = !isSubmitting,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showTagDialog) {
        TagSelectionDialog(
            title = "Select Tags",
            availableTags = availableTags,
            currentTags = selectedTags,
            confirmLabel = "Done",
            onSave = { tags ->
                selectedTags = tags
                showTagDialog = false
            },
            onDismiss = { showTagDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    selectedTags: List<String>,
    enabled: Boolean,
    onEditTags: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LightIndigo)
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Tags",
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
                fontSize = 15.sp,
            )
            OutlinedButton(
                onClick = onEditTags,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.Label,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Edit tags", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (selectedTags.isEmpty()) {
            Text(
                text = "No tags selected. Tap Edit tags to add.",
                fontSize = 13.sp,
                color = TextSec,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedTags.forEach { tag ->
                    SuggestionChip(
                        onClick = { if (enabled) onEditTags() },
                        label = { Text(tag, fontSize = 13.sp) },
                        colors =
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = CardWhite,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextSec,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors =
                TextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    disabledTextColor = TextSec,
                    focusedContainerColor = LightIndigo.copy(alpha = 0.5f),
                    unfocusedContainerColor = LightIndigo.copy(alpha = 0.35f),
                    disabledContainerColor = LightIndigo.copy(alpha = 0.2f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = TextGray,
                    unfocusedPlaceholderColor = TextGray,
                ),
        )
    }
}
