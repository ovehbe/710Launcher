package com.meowgi.launcher710.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.RawContacts
import com.meowgi.launcher710.model.LaunchableItem
import android.Manifest
import androidx.core.content.ContextCompat

private const val MAX_CONTACT_RESULTS = 40

/**
 * Contact search for extended search. Call from a background thread; keeps main thread free.
 * Matches by name (CONTENT_FILTER_URI) and by number (digits). Batches all DB work.
 */
object ContactSearchHelper {

    private val NON_DIGITS = Regex("[^0-9]")

    /**
     * One worker for all searches. Every keystroke used to start its own thread, so fast typing
     * ran a pile of overlapping provider queries that competed with the UI thread for CPU.
     */
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "contact-search").apply { isDaemon = true }
    }

    /** Bumped per request; queued or finished work for an older generation is dropped. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    /** (contactId, raw number, digits only) for every phone row; built once per search session. */
    private class PhoneRow(val contactId: Long, val number: String, val digits: String)

    @Volatile private var phoneIndex: List<PhoneRow>? = null
    @Volatile private var accountIdsCache: Pair<String, Set<Long>?>? = null

    /** Drops cached contact data so a new search session sees fresh contacts. Main thread. */
    fun invalidateCache() {
        generation.incrementAndGet()
        executor.execute {
            phoneIndex = null
            accountIdsCache = null
        }
    }

    /** Built lazily on first use; concurrent callers at worst build it twice. */
    private fun phoneIndex(context: Context): List<PhoneRow> {
        phoneIndex?.let { return it }
        val rows = ArrayList<PhoneRow>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Phone.CONTACT_ID)
                val numIdx = cursor.getColumnIndex(Phone.NUMBER)
                if (idIdx >= 0 && numIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val num = cursor.getString(numIdx)?.takeIf { it.isNotBlank() } ?: continue
                        rows += PhoneRow(cursor.getLong(idIdx), num, NON_DIGITS.replace(num, ""))
                    }
                }
            }
        } catch (_: SecurityException) { }
        phoneIndex = rows
        return rows
    }

    private fun accountContactIds(context: Context, source: String, type: String, name: String): Set<Long>? {
        accountIdsCache?.takeIf { it.first == source }?.let { return it.second }
        return getContactIdsForAccount(context, type, name).also { accountIdsCache = source to it }
    }

    /**
     * Returns contacts matching text and/or digit query. Filter out nameless. Safe to call from any thread.
     * @param enabled When false, returns empty list.
     * @param sourceFilter "all", "favorites", or "accountType:accountName".
     */
    fun search(
        context: Context,
        textQuery: String,
        digitQuery: String?,
        defaultIcon: Drawable,
        enabled: Boolean = true,
        sourceFilter: String = "all"
    ): List<LaunchableItem.Contact> {
        if (!enabled) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val rawTextQuery = textQuery.trim()
        val normalizedQuery = SearchNormalizer.normalize(rawTextQuery)
        val digits = digitQuery?.filter { it in '0'..'9' }?.takeIf { it.isNotEmpty() }
        if (normalizedQuery.isEmpty() && digits.isNullOrEmpty()) return emptyList()

        val source = sourceFilter.trim().ifEmpty { "all" }
        val accountContactIds: Set<Long>? = when {
            source == "favorites" -> null
            source != "all" && source.contains(":") -> {
                val parts = source.split(":", limit = 2)
                if (parts.size == 2) accountContactIds(context, source, parts[0].trim(), parts[1].trim()) else null
            }
            else -> null
        }

        val matchedIds = mutableSetOf<Long>()
        val idToName = mutableMapOf<Long, String>()

        if (rawTextQuery.isNotEmpty()) {
            try {
                val filterUri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(rawTextQuery))
                val nameSelection = if (source == "favorites") ContactsContract.Contacts.STARRED + "=1" else null
                context.contentResolver.query(
                    filterUri,
                    arrayOf(Contacts._ID, Contacts.DISPLAY_NAME),
                    nameSelection,
                    null,
                    Contacts.DISPLAY_NAME
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(Contacts._ID)
                    val nameIdx = cursor.getColumnIndex(Contacts.DISPLAY_NAME)
                    if (idIdx >= 0 && nameIdx >= 0) {
                        while (cursor.moveToNext() && matchedIds.size < MAX_CONTACT_RESULTS) {
                            val id = cursor.getLong(idIdx)
                            if (accountContactIds != null && id !in accountContactIds) continue
                            val name = cursor.getString(nameIdx)?.trim() ?: ""
                            if (name.isBlank()) continue
                            matchedIds.add(id)
                            idToName[id] = name
                        }
                    }
                }
            } catch (_: SecurityException) { }
        }

        if (digits != null && matchedIds.size < MAX_CONTACT_RESULTS) {
            for (row in phoneIndex(context)) {
                if (matchedIds.size >= MAX_CONTACT_RESULTS) break
                if (accountContactIds != null && row.contactId !in accountContactIds) continue
                if (row.digits.contains(digits)) matchedIds.add(row.contactId)
            }
        }

        val idsToUse = if (source == "favorites") {
            val starred = mutableSetOf<Long>()
            try {
                val idList = matchedIds.take(MAX_CONTACT_RESULTS).joinToString(",")
                val sel = ContactsContract.Contacts._ID + " IN (" + idList + ") AND " + ContactsContract.Contacts.STARRED + "=1"
                context.contentResolver.query(
                    Contacts.CONTENT_URI,
                    arrayOf(Contacts._ID),
                    sel,
                    null,
                    null
                )?.use { c ->
                    val idx = c.getColumnIndex(Contacts._ID)
                    if (idx >= 0) while (c.moveToNext()) starred.add(c.getLong(idx))
                }
            } catch (_: SecurityException) { }
            matchedIds.filter { it in starred }
        } else matchedIds

        if (idsToUse.isEmpty()) return emptyList()

        val ids = idsToUse.take(MAX_CONTACT_RESULTS)
        val needNames = ids.filter { it !in idToName }
        if (needNames.isNotEmpty()) {
            val chunk = needNames.take(100)
            val sel = ContactsContract.Contacts._ID + " IN (" + chunk.joinToString(",") { "?" } + ")"
            try {
                context.contentResolver.query(
                    Contacts.CONTENT_URI,
                    arrayOf(Contacts._ID, Contacts.DISPLAY_NAME),
                    sel,
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(Contacts._ID)
                    val nameIdx = c.getColumnIndex(Contacts.DISPLAY_NAME)
                    if (idIdx >= 0 && nameIdx >= 0) {
                        while (c.moveToNext()) {
                            val name = c.getString(nameIdx)?.trim()
                            if (!name.isNullOrBlank()) idToName[c.getLong(idIdx)] = name
                        }
                    }
                }
            } catch (_: SecurityException) { }
        }

        val idSet = ids.toHashSet()
        val idToNumbers = HashMap<Long, MutableList<String>>()
        for (row in phoneIndex(context)) {
            if (row.contactId in idSet) idToNumbers.getOrPut(row.contactId) { mutableListOf() }.add(row.number)
        }

        // Client-side relevance: only keep contacts that actually match the query (provider can return loose results).
        fun norm(s: String) = SearchNormalizer.normalize(s)

        val scored = mutableListOf<Pair<Int, LaunchableItem.Contact>>()
        for (id in ids) {
            val name = idToName[id] ?: continue
            if (name.isBlank()) continue
            val numbers = idToNumbers[id] ?: continue
            if (numbers.isEmpty()) continue

            val nameNorm = norm(name.toString())
            val nameMatches = when {
                normalizedQuery.isEmpty() -> true
                nameNorm == normalizedQuery -> true
                nameNorm.startsWith(normalizedQuery) -> true
                else -> false
            }
            val numberMatches = digits == null || numbers.any { NON_DIGITS.replace(it, "").contains(digits) }
            if (!nameMatches && !numberMatches) continue

            val contact = LaunchableItem.Contact(
                displayName = name,
                phoneNumbers = numbers,
                icon = defaultIcon
            )
            val rank = when {
                normalizedQuery.isNotEmpty() && nameNorm == normalizedQuery -> 0
                normalizedQuery.isNotEmpty() && nameNorm.startsWith(normalizedQuery) -> 1
                else -> 2
            }
            scored.add(rank to contact)
        }

        return scored
            .sortedWith(compareBy({ it.first }, { it.second.displayName.toString().lowercase() }))
            .take(MAX_CONTACT_RESULTS)
            .map { it.second }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runs contact search on the background worker and calls onResult on the main thread — only
     * for the most recent request. Calling again (or [cancel]) supersedes anything still queued
     * or running, so results can never arrive out of order. Only call from main thread.
     */
    fun searchAsync(
        context: Context,
        textQuery: String,
        digitQuery: String?,
        defaultIcon: Drawable,
        enabled: Boolean = true,
        sourceFilter: String = "all",
        onResult: (List<LaunchableItem.Contact>) -> Unit
    ) {
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) return@execute
            val list = search(context, textQuery, digitQuery, defaultIcon, enabled, sourceFilter)
            if (gen != generation.get()) return@execute
            mainHandler.post { if (gen == generation.get()) onResult(list) }
        }
    }

    /** Supersedes any pending [searchAsync] request without starting a new one. Main thread. */
    fun cancel() {
        generation.incrementAndGet()
    }

    private fun getContactIdsForAccount(context: Context, accountType: String, accountName: String): Set<Long>? {
        val ids = mutableSetOf<Long>()
        return try {
            val sel = RawContacts.ACCOUNT_TYPE + "=? AND " + RawContacts.ACCOUNT_NAME + "=?"
            val cur = context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.CONTACT_ID),
                sel,
                arrayOf(accountType, accountName),
                null
            )
            try {
                if (cur != null) {
                    val idx = cur.getColumnIndex(RawContacts.CONTACT_ID)
                    if (idx >= 0) while (cur.moveToNext()) ids.add(cur.getLong(idx))
                }
            } finally { cur?.close() }
            ids
        } catch (_: SecurityException) { null }
    }

    /** Returns (label, value) for contact source setting: "all", "favorites", or "accountType:accountName". */
    fun getContactSourceOptions(context: Context): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return listOf("All" to "all", "Favorites only" to "favorites")
        }
        val list = mutableListOf<Pair<String, String>>()
        list.add("All" to "all")
        list.add("Favorites only" to "favorites")
        try {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val typeIdx = cursor.getColumnIndex(RawContacts.ACCOUNT_TYPE)
                val nameIdx = cursor.getColumnIndex(RawContacts.ACCOUNT_NAME)
                if (typeIdx < 0 || nameIdx < 0) return list
                val seen = mutableSetOf<Pair<String, String>>()
                while (cursor.moveToNext()) {
                    val type = cursor.getString(typeIdx) ?: ""
                    val name = cursor.getString(nameIdx) ?: ""
                    val key = type to name
                    if (key in seen) continue
                    seen.add(key)
                    val label = when {
                        type.contains("google", ignoreCase = true) -> "Google"
                        type.contains("sim", ignoreCase = true) || type.contains("vnd.sec", ignoreCase = true) -> "SIM"
                        type.isBlank() && name.isBlank() -> "Device"
                        else -> type.substringAfterLast('.').ifEmpty { type }
                    }
                    list.add("$label${if (name.isNotBlank()) " ($name)" else ""}" to "$type:$name")
                }
            }
        } catch (_: SecurityException) { }
        return list
    }
}
