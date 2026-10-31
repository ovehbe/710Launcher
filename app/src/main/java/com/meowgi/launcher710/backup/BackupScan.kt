package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Generic discovery over dumped prefs, so nothing has to keep a list of keys:
 * file paths the prefs point at (assets to carry along) and packages they reference
 * (to report apps missing on this device). String values that hold JSON are walked leaf by leaf
 * because org.json writes "/" as "\/", so a raw substring search would miss them.
 */
object BackupScan {

    private val COMPONENT = Regex("""(?<![\w./])([A-Za-z]\w*(?:\.\w+)+)/[\w$.]+""")
    private val PACKAGE = Regex("""[A-Za-z]\w*(?:\.\w+)+""")

    /** Every string reachable from [value]: itself, set elements, and leaves of embedded JSON. */
    fun stringLeaves(value: Any?): List<String> {
        val out = ArrayList<String>()
        when (value) {
            is String -> collectString(value, out)
            is Set<*> -> value.forEach { if (it is String) collectString(it, out) }
        }
        return out
    }

    private fun collectString(s: String, out: MutableList<String>) {
        out += s
        parseJson(s)?.let { collectJson(it, out) }
    }

    private fun collectJson(v: Any?, out: MutableList<String>) {
        when (v) {
            is JSONObject -> v.keys().forEach { collectJson(v.opt(it), out) }
            is JSONArray -> for (i in 0 until v.length()) collectJson(v.opt(i), out)
            is String -> collectString(v, out)
        }
    }

    private fun parseJson(s: String): Any? {
        val t = s.trimStart()
        if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
        return try {
            JSONTokener(s).nextValue().takeIf { it is JSONObject || it is JSONArray }
        } catch (e: JSONException) {
            null
        }
    }

    /** Absolute paths under one of [dataRoots] mentioned anywhere in [prefs]. */
    fun referencedPaths(prefs: Map<String, Map<String, Any>>, dataRoots: List<String>): Set<String> {
        val roots = dataRoots.map { it.trimEnd('/') + "/" }
        val out = LinkedHashSet<String>()
        for (values in prefs.values) for (v in values.values) for (leaf in stringLeaves(v)) {
            if (roots.any { leaf.startsWith(it) } && !leaf.contains("/../")) out += leaf
        }
        return out
    }

    /** Copy of [values] with every string (also inside sets and embedded JSON) found in [map] replaced. */
    fun relocate(values: Map<String, Any>, map: Map<String, String>): Map<String, Any> {
        if (map.isEmpty()) return values
        return values.mapValues { (_, v) ->
            when (v) {
                is String -> relocateString(v, map)
                is Set<*> -> v.mapTo(LinkedHashSet()) { if (it is String) relocateString(it, map) else it }
                else -> v
            }
        }
    }

    private fun relocateString(s: String, map: Map<String, String>): String {
        map[s]?.let { return it }
        val json = parseJson(s) ?: return s
        return if (relocateJson(json, map)) json.toString() else s
    }

    /** Rewrites [v] in place; true if anything changed. */
    private fun relocateJson(v: Any?, map: Map<String, String>): Boolean {
        var changed = false
        when (v) {
            is JSONObject -> for (k in v.keys().asSequence().toList()) {
                val child = v.opt(k)
                if (child is String) {
                    val r = relocateString(child, map)
                    if (r != child) { v.put(k, r); changed = true }
                } else if (relocateJson(child, map)) changed = true
            }
            is JSONArray -> for (i in 0 until v.length()) {
                val child = v.opt(i)
                if (child is String) {
                    val r = relocateString(child, map)
                    if (r != child) { v.put(i, r); changed = true }
                } else if (relocateJson(child, map)) changed = true
            }
        }
        return changed
    }

    /**
     * Packages the backup refers to: component names ("pkg/.Cls", also inside intent URIs),
     * "pkg|id" entries (dock, shortcuts), values of keys ending in "Package" and DB text cells.
     */
    fun referencedPackages(payload: BackupPayload, ownPackage: String?): Set<String> {
        val out = LinkedHashSet<String>()
        fun scan(leaf: String) {
            COMPONENT.findAll(leaf).forEach { out += it.groupValues[1] }
            val bar = leaf.indexOf('|')
            if (bar > 0 && PACKAGE.matches(leaf.substring(0, bar))) out += leaf.substring(0, bar)
        }
        for (values in payload.prefs.values) for ((key, v) in values) {
            val leaves = stringLeaves(v)
            leaves.forEach(::scan)
            if (key.endsWith("Package") && v is String && PACKAGE.matches(v)) out += v
        }
        for (t in payload.tables.values) for (row in t.rows) for (cell in row) {
            if (cell is String) {
                if (PACKAGE.matches(cell)) out += cell else scan(cell)
            }
        }
        ownPackage?.let { out -= it }
        return out
    }
}
