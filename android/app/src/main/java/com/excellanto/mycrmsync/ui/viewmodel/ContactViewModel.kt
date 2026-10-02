package com.excellanto.mycrmsync.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.google.gson.JsonParser
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.model.CrmContactMutationResponse
import com.excellanto.mycrmsync.data.model.CrmDeleteContactRequest
import com.excellanto.mycrmsync.data.model.CrmUpdateContactRequest
import com.excellanto.mycrmsync.data.model.CrmAddContactRequest
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmContactTagsRequest
import com.excellanto.mycrmsync.data.model.CrmTag
import com.excellanto.mycrmsync.data.paging.ContactPagingSource
import com.excellanto.mycrmsync.data.paging.ContactReachedFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed class AddContactUiState {
    data object Idle : AddContactUiState()
    data object Loading : AddContactUiState()
    data object Success : AddContactUiState()
}

data class AddContactForm(
    val contactId: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val companyName: String = "",
    val address: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
    val tags: List<String> = emptyList(),
)

/** One-shot signals the screen reacts to during a tag update. */
enum class TagSelectionDialogCloseEvent {
    /** Update could not start (e.g. missing API key) — close so the modal is not stuck. */
    Aborted,
    /** Remove/add API call failed — keep the modal open but unlock it so the user can retry. */
    Failed,
    /** Tags saved successfully — close the modal. */
    Succeeded,
}

class ContactViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = CrmApiService.create()
    private val prefsManager = PrefsManager(application)

    private companion object {
        const val TAG = "ContactViewModel"
        const val CONTACTS_PAGE_SIZE = 20
    }

    private val _tags = MutableStateFlow<List<CrmTag>>(emptyList())
    val tags: StateFlow<List<CrmTag>> = _tags.asStateFlow()

    private val _totalContacts = MutableStateFlow(0)
    val totalContacts: StateFlow<Int> = _totalContacts.asStateFlow()

    private val selectedTag = MutableStateFlow<String?>(null)
    private val selectedContactReached = MutableStateFlow<ContactReachedFilter?>(null)
    private val searchQuery = MutableStateFlow<String?>(null)
    private val refreshSignal = MutableStateFlow(System.currentTimeMillis())

    /** Local tag list shown on contact cards until the CRM search index matches the API. */
    private val _tagOverrides = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tagOverrides: StateFlow<Map<String, List<String>>> = _tagOverrides.asStateFlow()

    /** Optimistic contact details shown on cards until paging reloads from the API. */
    private val _contactOverrides = MutableStateFlow<Map<String, CrmContact>>(emptyMap())
    val contactOverrides: StateFlow<Map<String, CrmContact>> = _contactOverrides.asStateFlow()

    /** Triggers [LazyPagingItems.refresh] in the UI (same Pager, invalidates PagingSource). */
    private val _requestPagingRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestPagingRefresh = _requestPagingRefresh.asSharedFlow()

    private val _tagSelectionDialogClose = MutableSharedFlow<TagSelectionDialogCloseEvent>(extraBufferCapacity = 1)
    val tagSelectionDialogClose = _tagSelectionDialogClose.asSharedFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _addContactState = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val addContactState: StateFlow<AddContactUiState> = _addContactState.asStateFlow()

    /** One-shot signal to close the contact form and refresh the list. */
    private val _contactFormClose = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactFormClose = _contactFormClose.asSharedFlow()

    fun consumeSnackbarMessage() {
        _snackbarMessage.value = null
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun resetAddContactState() {
        _addContactState.value = AddContactUiState.Idle
    }

    init {
        fetchTags()
    }

    private fun fetchTags() {
        viewModelScope.launch {
            try {
                val authorization = prefsManager.getBearerAuthorization() ?: return@launch
                val userId = prefsManager.getAuthUserId() ?: return@launch
                val response = apiService.getTags(authorization, userId)
                _tags.value = response.tags
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching CRM tags: ${e.toUserMessage()}", e)
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val contacts: Flow<PagingData<CrmContact>> = combine(
        selectedTag,
        selectedContactReached,
        searchQuery.debounce(500),
        refreshSignal
    ) { tag, contactReached, query, _ ->
        FilterParams(tag, contactReached, query)
    }.flatMapLatest { params ->
        _totalContacts.value = 0
        val authorization = prefsManager.getBearerAuthorization().orEmpty()
        val userId = prefsManager.getAuthUserId().orEmpty()
        
        Pager(
            config = PagingConfig(
                pageSize = CONTACTS_PAGE_SIZE,
                initialLoadSize = CONTACTS_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { 
                ContactPagingSource(
                    apiService, 
                    authorization,
                    userId,
                    pageSize = CONTACTS_PAGE_SIZE,
                    query = params.query,
                    tag = params.tag,
                    contactReached = params.contactReached,
                    onTotalLoaded = { total -> _totalContacts.value = total },
                ) 
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun onTagSelected(tag: String?) {
        selectedTag.value = if (tag == "All Tags") null else tag
    }

    fun onContactReachedSelected(option: String?) {
        selectedContactReached.value =
            when (option) {
                "Zero Calls Made" -> ContactReachedFilter.ZERO_CALLS_MADE
                "At least 1 Call Received" -> ContactReachedFilter.AT_LEAST_ONE_CALL_RECEIVED
                else -> null
            }
    }

    fun onSearch(query: String) {
        searchQuery.value = query.ifBlank { null }
    }

    fun refreshContacts() {
        refreshSignal.value = System.currentTimeMillis()
    }

    /**
     * When [contact] from paging matches the optimistic override (case-insensitive), drop the
     * override so the list uses server data again.
     */
    fun reconcileTagOverrideIfSynced(contact: CrmContact) {
        val overrideTags = _tagOverrides.value[contact.id] ?: return
        val fromNetLc = contact.tags.orEmpty().map { it.lowercase() }.toSet()
        val overrideLc = overrideTags.map { it.lowercase() }.toSet()
        if (fromNetLc == overrideLc) {
            _tagOverrides.update { it - contact.id }
        }
    }

    /**
     * When [contact] from paging matches the optimistic override, drop the override so the
     * card uses server data again.
     */
    fun reconcileContactOverrideIfSynced(contact: CrmContact) {
        val override = _contactOverrides.value[contact.id] ?: return
        if (contact.matchesContactOverride(override)) {
            _contactOverrides.update { it - contact.id }
        }
    }

    fun saveContact(form: AddContactForm) {
        if (form.contactId.isNullOrBlank()) {
            addContact(form)
        } else {
            updateContact(form)
        }
    }

    fun addContact(form: AddContactForm) {
        viewModelScope.launch {
            _addContactState.value = AddContactUiState.Loading
            try {
                val authorization = prefsManager.getBearerAuthorization()
                    ?: throw IllegalStateException("Not logged in. Please sign in again.")
                val userId = prefsManager.getRegisterUserId()
                    ?: throw IllegalStateException("CRM user id not found. Please sign in again.")

                val validated = validateContactForm(form)

                val response = apiService.addContact(
                    authorization = authorization,
                    request = CrmAddContactRequest(
                        userId = userId,
                        firstName = validated.firstName,
                        lastName = validated.lastName,
                        email = validated.email,
                        phone = validated.phone,
                        companyName = validated.companyName,
                        address = validated.address,
                        city = validated.city,
                        state = validated.state,
                        postalCode = validated.postalCode,
                        country = validated.country,
                        tags = validated.tags,
                    ),
                )

                response.requireSuccessfulMutation("Failed to add contact.")
                onContactSaved(response.displayMessage("Contact added successfully."))
            } catch (e: Exception) {
                Log.e(TAG, "Error adding CRM contact: ${e.toUserMessage()}", e)
                _addContactState.value = AddContactUiState.Idle
                _snackbarMessage.value = e.toUserMessage()
            }
        }
    }

    fun updateContact(form: AddContactForm) {
        val contactId = form.contactId?.trim().orEmpty()
        viewModelScope.launch {
            _addContactState.value = AddContactUiState.Loading
            try {
                if (contactId.isBlank()) {
                    throw IllegalArgumentException("Contact id is required.")
                }

                val authorization = prefsManager.getBearerAuthorization()
                    ?: throw IllegalStateException("Not logged in. Please sign in again.")
                val userId = prefsManager.getRegisterUserId()
                    ?: throw IllegalStateException("CRM user id not found. Please sign in again.")

                val validated = validateContactForm(form)

                Log.d(TAG, "updateContact: id=$contactId fields=$validated")

                val response = apiService.updateContact(
                    authorization = authorization,
                    request = CrmUpdateContactRequest(
                        userId = userId,
                        contactId = contactId,
                        firstName = validated.firstName,
                        lastName = validated.lastName,
                        email = validated.email,
                        phone = validated.phone,
                        companyName = validated.companyName,
                        address = validated.address,
                        city = validated.city,
                        state = validated.state,
                        postalCode = validated.postalCode,
                        country = validated.country,
                        tags = validated.tags,
                    ),
                )

                response.requireSuccessfulMutation("Failed to update contact.")
                Log.d(
                    TAG,
                    "updateContact success: success=${response.success} status=${response.status} message=${response.message}",
                )
                val updatedContact =
                    response.resolvedContact(contactId) {
                        contactFromValidated(contactId, validated)
                    }
                _contactOverrides.update { it + (contactId to updatedContact) }
                _tagOverrides.update { it - contactId }
                onContactSaved(response.displayMessage("Contact updated successfully."))
            } catch (e: Exception) {
                Log.e(TAG, "Error updating CRM contact (id=$contactId): ${e.toUserMessage()}", e)
                _addContactState.value = AddContactUiState.Idle
                _snackbarMessage.value = e.toUserMessage()
            }
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            _addContactState.value = AddContactUiState.Loading
            try {
                val trimmedId = contactId.trim()
                if (trimmedId.isBlank()) {
                    throw IllegalArgumentException("Contact id is required.")
                }

                val authorization = prefsManager.getBearerAuthorization()
                    ?: throw IllegalStateException("Not logged in. Please sign in again.")
                val userId = prefsManager.getRegisterUserId()
                    ?: throw IllegalStateException("CRM user id not found. Please sign in again.")

                val response = apiService.deleteContact(
                    authorization = authorization,
                    request = CrmDeleteContactRequest(
                        userId = userId,
                        contactId = trimmedId,
                    ),
                )

                response.requireSuccessfulMutation("Failed to delete contact.")
                _tagOverrides.update { it - trimmedId }
                _contactOverrides.update { it - trimmedId }
                onContactSaved(response.displayMessage("Contact deleted successfully."))
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting CRM contact (id=$contactId): ${e.toUserMessage()}", e)
                _addContactState.value = AddContactUiState.Idle
                _snackbarMessage.value = e.toUserMessage()
            }
        }
    }

    private data class ValidatedContactForm(
        val firstName: String,
        val lastName: String?,
        val email: String?,
        val phone: String?,
        val companyName: String?,
        val address: String?,
        val city: String?,
        val state: String?,
        val postalCode: String?,
        val country: String?,
        val tags: List<String>,
    )

    private fun validateContactForm(form: AddContactForm): ValidatedContactForm {
        val firstName = form.firstName.trim()
        if (firstName.isBlank()) {
            throw IllegalArgumentException("First name is required.")
        }

        val phone = form.phone.trim()
        val email = form.email.trim()
        if (phone.isBlank() && email.isBlank()) {
            throw IllegalArgumentException("Phone or email is required.")
        }

        val tags =
            form.tags
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }

        return ValidatedContactForm(
            firstName = firstName,
            lastName = form.lastName.trim().ifBlank { null },
            email = email.ifBlank { null },
            phone = phone.ifBlank { null },
            companyName = form.companyName.trim().ifBlank { null },
            address = form.address.trim().ifBlank { null },
            city = form.city.trim().ifBlank { null },
            state = form.state.trim().ifBlank { null },
            postalCode = form.postalCode.trim().ifBlank { null },
            country = form.country.trim().ifBlank { null },
            tags = tags,
        )
    }

    private suspend fun onContactSaved(message: String) {
        refreshContacts()
        fetchTags()
        _requestPagingRefresh.tryEmit(Unit)
        _addContactState.value = AddContactUiState.Success
        _snackbarMessage.value = message
        _contactFormClose.tryEmit(Unit)
    }

    private fun CrmContactMutationResponse.requireSuccessfulMutation(fallback: String) {
        if (success == false || status == false) {
            throw IllegalStateException(displayMessage(fallback))
        }
    }

    private fun CrmContactMutationResponse.resolvedContact(
        contactId: String,
        fallback: () -> CrmContact,
    ): CrmContact =
        contact?.takeIf { it.id.isNotBlank() }
            ?: data?.contact?.takeIf { it.id.isNotBlank() }
            ?: fallback().copy(id = contactId)

    private fun contactFromValidated(
        contactId: String,
        validated: ValidatedContactForm,
    ): CrmContact =
        CrmContact(
            id = contactId,
            firstName = validated.firstName,
            lastName = validated.lastName,
            companyName = validated.companyName,
            email = validated.email,
            phone = validated.phone,
            address = validated.address,
            city = validated.city,
            state = validated.state,
            postalCode = validated.postalCode,
            country = validated.country,
            tags = validated.tags,
        )

    private fun CrmContact.matchesContactOverride(override: CrmContact): Boolean {
        fun String?.norm() = this?.trim().orEmpty().lowercase()
        fun tagsNorm(tags: List<String>?) =
            tags.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        return firstName.norm() == override.firstName.norm() &&
            lastName.norm() == override.lastName.norm() &&
            email.norm() == override.email.norm() &&
            phone.norm() == override.phone.norm() &&
            companyName.norm() == override.companyName.norm() &&
            address.norm() == override.address.norm() &&
            city.norm() == override.city.norm() &&
            state.norm() == override.state.norm() &&
            postalCode.norm() == override.postalCode.norm() &&
            country.norm() == override.country.norm() &&
            tagsNorm(tags) == tagsNorm(override.tags)
    }

    fun updateContactTags(contact: CrmContact, tags: List<String>) {
        val contactId = contact.id
        val displayName = contact.fullName

        viewModelScope.launch {
            try {
                if (contactId.isBlank()) {
                    Log.e(TAG, "updateContactTags aborted: blank contact id (name=$displayName)")
                    _tagSelectionDialogClose.tryEmit(TagSelectionDialogCloseEvent.Failed)
                    return@launch
                }

                val authorization = prefsManager.getBearerAuthorization() ?: run {
                    Log.w(TAG, "updateContactTags aborted: missing CRM access token (contactId=$contactId)")
                    _tagSelectionDialogClose.tryEmit(TagSelectionDialogCloseEvent.Aborted)
                    return@launch
                }
                val userId = prefsManager.getAuthUserId() ?: run {
                    Log.w(TAG, "updateContactTags aborted: missing CRM user id (contactId=$contactId)")
                    _tagSelectionDialogClose.tryEmit(TagSelectionDialogCloseEvent.Aborted)
                    return@launch
                }

                val confirmedTags =
                    tags
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }

                Log.d(
                    TAG,
                    "updateContactTags: id=$contactId name=$displayName -> $confirmedTags",
                )

                val response = apiService.addOrUpdateContactTags(
                    authorization = authorization,
                    request = CrmContactTagsRequest(
                        userId = userId,
                        contactId = contactId,
                        tags = confirmedTags,
                    ),
                )

                val serverTags = response.tags.ifEmpty { confirmedTags }
                _tagOverrides.update { it + (contactId to serverTags) }
                _requestPagingRefresh.tryEmit(Unit)

                delay(400)
                _requestPagingRefresh.tryEmit(Unit)
                refreshContacts()
                _tagSelectionDialogClose.tryEmit(TagSelectionDialogCloseEvent.Succeeded)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating CRM tags (id=$contactId): ${e.toUserMessage()}", e)
                _tagOverrides.update { it - contactId }
                _snackbarMessage.value = e.toUserMessage()
                _tagSelectionDialogClose.tryEmit(TagSelectionDialogCloseEvent.Failed)
            }
        }
    }

    private data class FilterParams(
        val tag: String?,
        val contactReached: ContactReachedFilter?,
        val query: String?,
    )

    private fun Exception.toUserMessage(): String {
        if (this !is HttpException) {
            return localizedMessage ?: "CRM request failed"
        }
        val body = response()?.errorBody()?.string().orEmpty()
        val message = runCatching {
            JsonParser.parseString(body)
                .asJsonObject
                .get("message")
                ?.asString
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return message ?: "CRM request failed (HTTP ${code()})."
    }
}
