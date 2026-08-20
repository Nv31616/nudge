package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.model.BlockMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(
    val rules: List<ExportedRule>,
    val groups: List<ExportedGroup>,
    val version: Int,
    val error: String? = null,
    /**
     * Entries (rules or groups) in the file that could not be parsed and were skipped. Every other
     * entry still imports -- see [RuleExporter.importRules].
     */
    val invalidCount: Int = 0,
    /**
     * Human-readable reason per skipped entry, in file order ("Rule 3: ..."). Capped -- these are
     * for display, so [invalidCount] is the authoritative total, not `invalidReasons.size`.
     */
    val invalidReasons: List<String> = emptyList(),
    /**
     * Usage history carried by the file. Empty for a rules-only export (every file written before
     * this existed, and every file written by an older Nudge).
     */
    val history: List<ExportedHistoryEvent> = emptyList(),
    /**
     * History entries that could not be read. Counted SEPARATELY from [invalidCount] because they
     * are a different kind of loss to the user: a dropped rule stops protecting them, a dropped
     * history row only dents a statistic. Merging the two would let a corrupt history array read as
     * "20 of your rules could not be imported".
     */
    val invalidHistoryCount: Int = 0,
    /** Reason per skipped history entry ("History event 3: ..."), capped like [invalidReasons]. */
    val invalidHistoryReasons: List<String> = emptyList()
)

@Singleton
class RuleExporter @Inject constructor() {

    companion object {
        private const val CURRENT_VERSION = 1

        /** Cap on how many per-entry reasons an error message quotes, so it stays readable. */
        private const val MAX_REPORTED_REASONS = 3

        /**
         * Cap on how many reasons are retained at all. [ImportResult.invalidCount] still counts
         * every skip; only the explanatory strings are bounded, so a pathological file cannot make
         * the reason list grow with its own entry count.
         */
        private const val MAX_COLLECTED_REASONS = 20

        /**
         * Accepted values for a rule's `mode` on import, derived from [BlockMode] rather than
         * hand-listed. A hardcoded list silently goes stale the moment a mode is added: BlockMode.NONE
         * shipped without updating it, and because [parseRule] throws inside an eager map over the
         * whole array, ONE unrecognized rule aborted the ENTIRE import -- every rule and every group
         * lost, on the app's only backup mechanism. Deriving it means that class of bug cannot recur.
         */
        private val VALID_MODES: Set<String> = BlockMode.entries.mapTo(mutableSetOf()) { it.name }

        /** Rough per-entry size, used only to pre-size the output buffer. */
        private const val ESTIMATED_HISTORY_ENTRY_CHARS = 120
    }

    /**
     * Exports rules, groups and usage [history] to a JSON string.
     *
     * Rules and groups stay pretty-printed (a backup a human can read and hand-edit was the point);
     * the history array is written COMPACTLY, one object per line-less entry, because it is machine
     * data whose row count is unbounded -- pretty-printing tens of thousands of events would inflate
     * the file several-fold for nobody's benefit. See [serializeToJson].
     */
    fun exportRules(
        rules: List<BlockRule>,
        groups: List<AppGroup>,
        groupMembers: Map<Long, List<AppGroupMember>>,
        history: List<UsageEvent> = emptyList()
    ): String {
        val groupIdToName = groups.associateBy({ it.id }, { it.name })

        val exportedRules = rules.map { rule ->
            ExportedRule(
                packageName = rule.packageName,
                groupName = rule.groupId?.let { groupIdToName[it] },
                mode = rule.mode,
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays,
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = rule.inAppFeatures,
                grayscale = rule.grayscale,
                showCounter = rule.showCounter,
                autoKickAfter = rule.autoKickAfter,
                showTimeRemaining = rule.showTimeRemaining,
                autoKickCooldownSeconds = rule.autoKickCooldownSeconds,
                webDomains = rule.webDomains,
                autoKickAfterMinutes = rule.autoKickAfterMinutes,
                webBlockMode = rule.webBlockMode
            )
        }

        val exportedGroups = groups.map { group ->
            val members = groupMembers[group.id]?.map { it.packageName } ?: emptyList()
            ExportedGroup(name = group.name, members = members)
        }

        val export = NudgeExport(
            rules = exportedRules,
            groups = exportedGroups,
            history = history.map(HistoryMerge::toExported)
        )

        return serializeToJson(export)
    }

    /**
     * Parses and validates a JSON string into an ImportResult.
     *
     * Entry-level failures are ISOLATED: a rule or group that cannot be parsed is skipped and
     * counted in [ImportResult.invalidCount], and every other entry still imports. Export/import is
     * the app's only backup path, so one bad entry must never cost the user the whole file (the
     * original eager `map` threw out of the loop and the catch returned an empty list -- issue #20).
     *
     * ENVELOPE-level failures still fail loudly via [ImportResult.error]: not JSON, a bad version,
     * a `rules`/`groups`/`history` key that is present but is not an array, or a file in which
     * literally nothing was importable. "Imported 0 rules" is never reported as a success.
     *
     * UNKNOWN top-level keys are ignored, and always have been -- only `version`, `rules`, `groups`
     * and now `history` are read by name. That is precisely why history could be added at envelope
     * version 1: an older Nudge skips it, where a version bump would have made it reject the file
     * outright and lose the user their rules too.
     */
    fun importRules(json: String): ImportResult {
        return try {
            val root = JSONObject(json)

            val version = root.optInt("version", 0)
            if (version < 1) {
                return ImportResult(
                    rules = emptyList(),
                    groups = emptyList(),
                    version = 0,
                    error = "Invalid or missing version field"
                )
            }
            if (version > CURRENT_VERSION) {
                return ImportResult(
                    rules = emptyList(),
                    groups = emptyList(),
                    version = version,
                    error = "Export version $version is newer than supported ($CURRENT_VERSION). Please update the app."
                )
            }

            // Count every skip but retain only the first few reasons: the reason list is driven by
            // file content, and a wrong-file pick (a big JSON of anything else) must not turn into
            // a million strings on a 3GB device.
            var invalidCount = 0
            val reasons = mutableListOf<String>()
            val onSkip: (String) -> Unit = { reason ->
                invalidCount++
                if (reasons.size < MAX_COLLECTED_REASONS) reasons.add(reason)
            }

            // History skips are tallied on their own counter: losing a rule and losing a statistic
            // are not the same event, and the UI reports them as separate lines.
            var invalidHistoryCount = 0
            val historyReasons = mutableListOf<String>()
            val onHistorySkip: (String) -> Unit = { reason ->
                invalidHistoryCount++
                if (historyReasons.size < MAX_COLLECTED_REASONS) historyReasons.add(reason)
            }

            val rules = parseEach(root.arrayOrEmpty("rules"), "Rule", onSkip, ::parseRule)
            val groups = parseEach(root.arrayOrEmpty("groups"), "Group", onSkip, ::parseGroup)
            val history = parseEach(
                root.arrayOrEmpty("history"),
                "History event",
                onHistorySkip,
                ::parseHistoryEvent
            )

            // Nothing survived. Skipping every entry is not a successful import of zero rules --
            // report it as a failure so the user sees why instead of a silent "Imported: 0".
            // History counts as something surviving: a file whose rules are all unreadable but
            // whose history restores cleanly still did something for the user.
            val nothingImportable = rules.isEmpty() && groups.isEmpty() && history.isEmpty()
            val error = if (nothingImportable && invalidCount + invalidHistoryCount > 0) {
                allInvalidMessage(invalidCount + invalidHistoryCount, reasons + historyReasons)
            } else {
                null
            }

            // All three lists are empty whenever `error` is set, so they need no special-casing.
            ImportResult(
                rules = rules,
                groups = groups,
                version = version,
                error = error,
                invalidCount = invalidCount,
                invalidReasons = reasons,
                history = history,
                invalidHistoryCount = invalidHistoryCount,
                invalidHistoryReasons = historyReasons
            )
        } catch (e: JSONException) {
            ImportResult(
                rules = emptyList(),
                groups = emptyList(),
                version = 0,
                error = "Invalid JSON format: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            ImportResult(
                rules = emptyList(),
                groups = emptyList(),
                version = 0,
                error = "Invalid data: ${e.message}"
            )
        }
    }

    private fun serializeToJson(export: NudgeExport): String {
        val root = JSONObject()
        root.put("version", export.version)
        root.put("exportedAt", export.exportedAt)

        val rulesArray = JSONArray()
        export.rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("packageName", rule.packageName ?: JSONObject.NULL)
            obj.put("groupName", rule.groupName ?: JSONObject.NULL)
            obj.put("mode", rule.mode)
            obj.put("delaySeconds", rule.delaySeconds)
            obj.put("dailyLimitMinutes", rule.dailyLimitMinutes ?: JSONObject.NULL)
            obj.put("enabled", rule.enabled)
            obj.put("scheduleDays", rule.scheduleDays ?: JSONObject.NULL)
            obj.put("scheduleStartMinute", rule.scheduleStartMinute ?: JSONObject.NULL)
            obj.put("scheduleEndMinute", rule.scheduleEndMinute ?: JSONObject.NULL)
            obj.put("inAppFeatures", rule.inAppFeatures ?: JSONObject.NULL)
            obj.put("grayscale", rule.grayscale)
            obj.put("showCounter", rule.showCounter)
            obj.put("autoKickAfter", rule.autoKickAfter ?: JSONObject.NULL)
            obj.put("showTimeRemaining", rule.showTimeRemaining)
            obj.put("autoKickCooldownSeconds", rule.autoKickCooldownSeconds)
            obj.put("webDomains", rule.webDomains ?: JSONObject.NULL)
            obj.put("autoKickAfterMinutes", rule.autoKickAfterMinutes ?: JSONObject.NULL)
            obj.put("webBlockMode", rule.webBlockMode ?: JSONObject.NULL)
            rulesArray.put(obj)
        }
        root.put("rules", rulesArray)

        val groupsArray = JSONArray()
        export.groups.forEach { group ->
            val obj = JSONObject()
            obj.put("name", group.name)
            val membersArr = JSONArray()
            group.members.forEach { membersArr.put(it) }
            obj.put("members", membersArr)
            groupsArray.put(obj)
        }
        root.put("groups", groupsArray)

        return spliceHistory(root.toString(2), export.history)
    }

    /**
     * Appends the compact `history` array as the last member of an already pretty-printed envelope.
     *
     * org.json pretty-prints a whole document or none of it, and history must not be pretty-printed:
     * it is machine data with an unbounded row count (retention is not enforced anywhere), so four
     * lines and ~30 bytes of indentation per event would multiply a heavy user's backup several
     * times over. Rules and groups stay readable; history is one dense line.
     *
     * Splicing by the closing brace is index-of-'}' arithmetic rather than string parsing, and is
     * independent of member ORDER -- which matters, because Android's org.json preserves insertion
     * order while the desktop implementation the unit tests run against does not. The round-trip
     * tests are what prove the result is valid JSON.
     */
    private fun spliceHistory(pretty: String, history: List<ExportedHistoryEvent>): String {
        if (history.isEmpty()) return pretty
        val close = pretty.lastIndexOf('}')
        if (close < 0) return pretty // not an object; unreachable for an envelope we just built

        val body = pretty.substring(0, close).trimEnd()
        val separator = if (body.endsWith("{")) "" else ","
        return buildString(body.length + history.size * ESTIMATED_HISTORY_ENTRY_CHARS + 32) {
            append(body)
            append(separator)
            append("\n  \"history\": ")
            appendHistoryArray(history)
            append("\n}")
        }
    }

    /**
     * Writes `[{...},{...}]` straight into [this] instead of building a `JSONArray` of
     * `JSONObject`s first. One buffer, no intermediate object graph -- the difference between a
     * 50k-event export being a few MB of text and being a few MB of text plus 50k live objects on
     * a 3GB device. Every string still goes through [JSONObject.quote], so escaping is not
     * hand-rolled.
     */
    private fun StringBuilder.appendHistoryArray(history: List<ExportedHistoryEvent>) {
        append('[')
        history.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append("{\"packageName\":").append(JSONObject.quote(event.packageName))
            append(",\"timestamp\":").append(event.timestamp)
            append(",\"wasBlocked\":").append(event.wasBlocked)
            append(",\"blockMode\":")
            append(event.blockMode?.let { JSONObject.quote(it) } ?: "null")
            append(",\"userChangedMind\":").append(event.userChangedMind)
            append('}')
        }
        append(']')
    }

    /**
     * Parses every element of [array] independently, collecting a reason into [skipped] for each
     * one that fails instead of aborting the whole array. Shared by rules and groups so the two can
     * never drift in how tolerant they are.
     */
    private fun <T> parseEach(
        array: JSONArray,
        label: String,
        onSkip: (String) -> Unit,
        parse: (JSONObject) -> T
    ): List<T> {
        val parsed = ArrayList<T>(array.length())
        for (i in 0 until array.length()) {
            try {
                parsed.add(parse(array.getJSONObject(i)))
            } catch (e: JSONException) {
                onSkip("$label ${i + 1}: ${e.skipReason()}")
            } catch (e: IllegalArgumentException) {
                onSkip("$label ${i + 1}: ${e.skipReason()}")
            }
        }
        return parsed
    }

    private fun Exception.skipReason(): String =
        message?.takeIf { it.isNotBlank() } ?: "could not be read"

    private fun allInvalidMessage(invalidCount: Int, reasons: List<String>): String {
        val shown = reasons.take(MAX_REPORTED_REASONS).joinToString("\n")
        val extra = invalidCount - MAX_REPORTED_REASONS
        val more = if (extra > 0) "\n...and $extra more" else ""
        return "No valid rules or groups found. All $invalidCount entries were invalid:\n$shown$more"
    }

    /**
     * A key that is absent or null means "none" (older exports omit `groups` entirely); a key that
     * is present but is NOT an array means the file is not a Nudge export, which must fail loudly
     * rather than quietly importing zero rules.
     */
    private fun JSONObject.arrayOrEmpty(key: String): JSONArray {
        if (!has(key) || isNull(key)) return JSONArray()
        return optJSONArray(key) ?: throw JSONException("\"$key\" must be an array")
    }

    private fun parseRule(obj: JSONObject): ExportedRule {
        val mode = obj.getString("mode")
        require(mode in VALID_MODES) {
            "Unknown block mode: $mode"
        }

        return ExportedRule(
            packageName = obj.optStringOrNull("packageName"),
            groupName = obj.optStringOrNull("groupName"),
            mode = mode,
            delaySeconds = obj.optInt("delaySeconds", 15),
            dailyLimitMinutes = obj.optIntOrNull("dailyLimitMinutes"),
            enabled = obj.optBoolean("enabled", true),
            scheduleDays = obj.optStringOrNull("scheduleDays"),
            scheduleStartMinute = obj.optIntOrNull("scheduleStartMinute"),
            scheduleEndMinute = obj.optIntOrNull("scheduleEndMinute"),
            inAppFeatures = obj.optStringOrNull("inAppFeatures"),
            grayscale = obj.optBoolean("grayscale", false),
            showCounter = obj.optBoolean("showCounter", false),
            autoKickAfter = obj.optIntOrNull("autoKickAfter"),
            showTimeRemaining = obj.optBoolean("showTimeRemaining", false),
            autoKickCooldownSeconds = obj.optInt("autoKickCooldownSeconds", 60),
            webDomains = obj.optStringOrNull("webDomains"),
            autoKickAfterMinutes = obj.optIntOrNull("autoKickAfterMinutes"),
            // Null (absent, or written by an older Nudge) = inherit the app-level mode, which is
            // exactly what those exports meant. An unrecognized value is tolerated rather than
            // failing the import: WebBlockMode falls back to the app-level mode for it.
            webBlockMode = obj.optStringOrNull("webBlockMode")
        )
    }

    /**
     * Parses one history row. Anything that fails here is skipped and counted -- one corrupt event
     * out of forty thousand must never cost the user the rules in the same file.
     *
     * Type checks are EXACT (`opt(key) as? T`) rather than org.json's coercing `getString` /
     * `optBoolean`, for two reasons. Android's org.json coerces where the desktop implementation
     * the unit tests run against throws, so a coercing read would mean the tests and the device
     * disagree about which entries are valid. And a `"wasBlocked": "yes"` quietly coerced to
     * `false` would not be a skipped row, it would be a silently WRONG row -- history feeds the
     * stat tiles, so mis-reading one is worse than dropping it.
     */
    private fun parseHistoryEvent(obj: JSONObject): ExportedHistoryEvent {
        val packageName = obj.requiredString("packageName")
        require(packageName.isNotBlank()) { "Package name is blank" }
        val timestamp = obj.requiredLong("timestamp")
        require(timestamp > 0L) { "Timestamp is not a positive epoch-millisecond value" }

        return ExportedHistoryEvent(
            packageName = packageName,
            timestamp = timestamp,
            wasBlocked = obj.strictBoolean("wasBlocked"),
            blockMode = obj.strictStringOrNull("blockMode"),
            userChangedMind = obj.strictBoolean("userChangedMind")
        )
    }

    private fun parseGroup(obj: JSONObject): ExportedGroup {
        val name = obj.getString("name")
        require(name.isNotBlank()) { "Group name is blank" }
        val membersArr = obj.optJSONArray("members") ?: JSONArray()
        val members = (0 until membersArr.length()).map { membersArr.getString(it) }
        return ExportedGroup(name = name, members = members)
    }

    /**
     * Extension: returns null for JSONObject.NULL or missing keys, otherwise the string value.
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return getString(key)
    }

    /**
     * Extension: returns null for JSONObject.NULL or missing keys, otherwise the int value.
     */
    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return getInt(key)
    }

    // --- Exact-type readers, used by the history parser (see [parseHistoryEvent]) ---

    private fun JSONObject.requiredString(key: String): String =
        opt(key) as? String ?: throw JSONException("\"$key\" is missing or is not text")

    private fun JSONObject.requiredLong(key: String): Long =
        (opt(key) as? Number)?.toLong()
            ?: throw JSONException("\"$key\" is missing or is not a number")

    private fun JSONObject.strictBoolean(key: String, default: Boolean = false): Boolean {
        if (!has(key) || isNull(key)) return default
        return opt(key) as? Boolean ?: throw JSONException("\"$key\" is not true or false")
    }

    private fun JSONObject.strictStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? String ?: throw JSONException("\"$key\" is not text")
    }
}
