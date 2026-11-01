package com.meowgi.launcher710.util

/**
 * Turns the active notifications into one badge number per package.
 *
 * Ongoing notifications (music players, VPN, downloads) never badge. A group summary counts only
 * when it has no children of its own in the list, so a grouped conversation isn't counted twice.
 * Apps that report [Entry.number] (an inbox saying "5 unread" in one notification) win over the
 * plain notification count when that is larger.
 */
object NotificationBadgeCounter {

    data class Entry(
        val packageName: String,
        val groupKey: String?,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        val number: Int
    )

    fun count(entries: List<Entry>): Map<String, Int> {
        val shown = entries.filter { !it.isOngoing }
        val groupsWithChildren = shown.filter { !it.isGroupSummary }
            .mapNotNullTo(HashSet()) { it.groupKey?.let { g -> it.packageName to g } }
        val result = HashMap<String, Int>()
        for ((pkg, list) in shown.groupBy { it.packageName }) {
            val counted = list.filter { !it.isGroupSummary || (it.packageName to it.groupKey) !in groupsWithChildren }
            val total = maxOf(counted.size, counted.sumOf { it.number.coerceAtLeast(0) })
            if (total > 0) result[pkg] = total
        }
        return result
    }
}
