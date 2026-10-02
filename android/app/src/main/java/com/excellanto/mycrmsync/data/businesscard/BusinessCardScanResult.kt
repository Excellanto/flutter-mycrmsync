package com.excellanto.mycrmsync.data.businesscard

import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm

/**
 * Structured fields extracted from business-card OCR text.
 * Unmapped fields are left blank for manual entry in [AddContactForm].
 */
data class ParsedBusinessCard(
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
) {
    fun toAddContactForm(): AddContactForm =
        AddContactForm(
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
        )
}
