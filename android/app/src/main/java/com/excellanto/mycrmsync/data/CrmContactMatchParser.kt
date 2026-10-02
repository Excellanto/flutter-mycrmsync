package com.excellanto.mycrmsync.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class WhatsAppCrmMatch(
    val id: String,
    val name: String,
    val phone: String?,
    val company: String?,
)

object CrmContactMatchParser {
    fun parse(element: JsonElement): List<WhatsAppCrmMatch> {
        if (element.isJsonObject) {
            val fromEnvelope = parseEnvelope(element.asJsonObject)
            if (fromEnvelope.isNotEmpty()) return fromEnvelope
        }
        val array = findContacts(element) ?: return emptyList()
        return array.mapNotNull { parseItem(it) }
    }

    /**
     * `POST api/crm/contacts/match` returns the best hit as `contact` and the
     * rest as `alternatives`, each alternative shaped as `{ contact, score }`.
     */
    private fun parseEnvelope(root: JsonObject): List<WhatsAppCrmMatch> {
        val success = root.get("success")
        if (success != null && success.isJsonPrimitive && success.asJsonPrimitive.isBoolean && !success.asBoolean) {
            return emptyList()
        }
        val results = LinkedHashMap<String, WhatsAppCrmMatch>()
        parseItem(root.get("contact"))?.let { results[it.id] = it }
        val alternatives = root.get("alternatives")
        if (alternatives != null && alternatives.isJsonArray) {
            for (item in alternatives.asJsonArray) {
                parseItem(item)?.let { results.putIfAbsent(it.id, it) }
            }
        }
        return results.values.toList()
    }

    private fun parseItem(element: JsonElement?): WhatsAppCrmMatch? {
        val obj = contactObject(element) ?: return null
        val id =
            firstString(obj, "id", "contactId", "contactid", "contact_id")
                ?: return null
        val first = firstString(obj, "firstName", "first_name")
        val last = firstString(obj, "lastName", "last_name")
        val combined = listOfNotNull(first, last).joinToString(" ").ifBlank { null }
        val name =
            firstString(obj, "fullName", "full_name", "name", "contactName", "contact_name")
                ?: combined
                ?: "Unknown"
        return WhatsAppCrmMatch(
            id = id,
            name = name,
            phone = firstString(obj, "phone", "phoneNumber", "phone_number"),
            company = firstString(obj, "companyName", "company_name", "company"),
        )
    }

    private fun contactObject(element: JsonElement?): JsonObject? {
        if (element == null || element.isJsonNull || !element.isJsonObject) return null
        val obj = element.asJsonObject
        val nested = obj.get("contact")
        if (nested != null && nested.isJsonObject) return nested.asJsonObject
        return obj
    }

    private fun findContacts(element: JsonElement): JsonArray? {
        if (element.isJsonArray) return element.asJsonArray
        if (!element.isJsonObject) return null
        val root = element.asJsonObject
        arrayAt(root, "contacts", "matches", "items")?.let { return it }
        val data = root.get("data") ?: return null
        if (data.isJsonArray) return data.asJsonArray
        if (!data.isJsonObject) return null
        return arrayAt(data.asJsonObject, "contacts", "matches", "items")
    }

    private fun arrayAt(obj: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonArray) return value.asJsonArray
        }
        return null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = obj.get(key)
            if (value == null || value.isJsonNull || !value.isJsonPrimitive) continue
            val primitive = value.asJsonPrimitive
            val text =
                when {
                    primitive.isString -> primitive.asString
                    primitive.isNumber -> primitive.asNumber.toString()
                    else -> null
                }?.trim() ?: continue
            if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) return text
        }
        return null
    }
}
