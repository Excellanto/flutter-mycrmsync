package com.excellanto.mycrmsync.data

/** Filter for `POST api/crm/call-logs/get` — current user only or all tenant users. */
enum class CallLogListScope {
    ME,
    ALL,
}
