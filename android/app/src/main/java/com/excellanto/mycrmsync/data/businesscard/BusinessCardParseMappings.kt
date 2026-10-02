package com.excellanto.mycrmsync.data.businesscard

import com.excellanto.mycrmsync.data.model.BusinessCardParseData
import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm

fun BusinessCardParseData.toAddContactForm(): AddContactForm =
    AddContactForm(
        firstName = firstName.orEmpty().trim(),
        lastName = lastName.orEmpty().trim(),
        email = email.orEmpty().trim(),
        phone = phone.orEmpty().trim(),
        companyName = companyName.orEmpty().trim(),
        address = address.orEmpty().trim(),
        city = city.orEmpty().trim(),
        state = state.orEmpty().trim(),
        postalCode = postalCode.orEmpty().trim(),
        country = country.orEmpty().trim(),
    )
