package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.PastedProxyEntry
import com.github.kr328.clash.service.store.DomainRule
import com.github.kr328.clash.service.store.parseAppNodeRulesForProfile
import com.github.kr328.clash.service.store.parseDomainNodeRulesForProfile
import com.github.kr328.clash.service.store.parsePastedProxies
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                Clash.load(resolveLoadDir(active.uuid)).await()

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }

    // FEATURE-1 BEGIN: app blocklist via staging dir.
    // (FEATURE-2 extends this same staging path. Since
    // FEATURE-APPNODE-FALLBACK, per-app node rules are NOT appended
    // after the FEATURE-1 REJECT lines any more: REJECT stays at the
    // very top, node rules go just before MATCH/FINAL, so subscription
    // rules win and a designated node is only the fallback.)
    // Self-check note: BlockedAppsActivity calls sendOverrideChanged() on
    // leaving the page; this module listens for ACTION_OVERRIDE_CHANGED and
    // its select branch returns null for it, so `changed` is null and the
    // loop below does NOT skip -- it re-runs Clash.load(resolveLoadDir()).
    // The block therefore takes effect on leaving the page without a full
    // service restart. If the block set is empty, the original imported dir
    // is loaded untouched: baseline behaviour, zero overhead.
    // Runs on Dispatchers.IO (see ClashRuntime), so file IO here is safe.
    private fun resolveLoadDir(uuid: UUID): File {
        val blocked = store.blockedPackages
        // FEATURE-2D: per-app node rules ride the same staging copy,
        // but only this profile's own rules -- never another profile's.
        val appNodeRules = parseAppNodeRulesForProfile(store.appNodeRules, uuid)
        // FEATURE-DOMAINNODE: this profile's domain rules only.
        val domainNodeRules = parseDomainNodeRulesForProfile(store.domainNodeRules, uuid)
        // FEATURE-PASTE: pasted nodes ride the same staging copy, so
        // they survive subscription updates (see injectPastedProxies).
        val pasted = parsePastedProxies(store.pastedProxies, uuid)
        val imported = service.importedDir.resolve(uuid.toString())
        // Everything off -> original dir untouched (baseline unchanged).
        if (blocked.isEmpty() && appNodeRules.isEmpty() && pasted.isEmpty() && domainNodeRules.isEmpty())
            return imported

        val stagingRoot = service.filesDir.resolve("staging")
        stagingRoot.mkdirs()

        // Drop staging dirs of other profiles (and stale temp dirs): loads
        // are serialised in this module's loop, so nothing still reads
        // them, and the imported originals are never touched. Without this
        // every profile switch would orphan a full profile copy on disk.
        stagingRoot.listFiles()?.forEach {
            if (it.name != uuid.toString() && it.name != uuid.toString() + ".tmp")
                it.deleteRecursively()
        }

        // Build the copy in a temp dir first, then swap it in, so a reader
        // never sees a half-copied staging dir. If the rename fails (should
        // not happen inside one parent dir), load the temp dir directly.
        val staging = stagingRoot.resolve(uuid.toString())
        val stagingTmp = stagingRoot.resolve(uuid.toString() + ".tmp")
        stagingTmp.deleteRecursively()
        imported.copyRecursively(stagingTmp, overwrite = true)

        // FEATURE-2D defence in depth: even within one profile, a
        // subscription update can remove a node a rule still points at.
        // Dropping such rules for this load (not persisted: the page's
        // live-core prune owns persistence) keeps a stale pick from
        // failing the whole load with "proxy [...] not found".
        val safeAppNodeRules = filterAppNodeRulesToKnownTargets(
            stagingTmp.resolve("config.yaml"), appNodeRules
        )

        // FEATURE-APPNODE-FALLBACK BEGIN: blocked REJECT lines stay at
        // the very top (they must win over everything), but per-app node
        // rules are no longer prepended with them. They are inserted
        // just before MATCH/FINAL (or at the end of the rules section
        // when there is no terminal rule), so every subscription rule
        // (DOMAIN/GEOIP DIRECT, specific proxy rules, ...) matches
        // first and the designated node only catches what nothing else
        // matched. Without this, a designated node swallowed domestic
        // DIRECT rules too, because PROCESS-NAME was first.
        injectFeatureRules(
            stagingTmp.resolve("config.yaml"),
            buildBlockRuleLines(blocked)
        )
        // FEATURE-DOMAINNODE BEGIN: domain rules go AFTER the last
        // REJECT rule in the rules section (app block REJECT lines just
        // injected count too, but subscription ad REJECT rules sit
        // later, so they are the ones that set the position). Final
        // order: block REJECT > subscription REJECT (ads) > domain
        // rules > remaining subscription rules (including the .cn and
        // GEOIP,CN DIRECT rules near MATCH, which domain rules beat)
        // > per-app node rules > MATCH. Stale targets are filtered
        // the same way as appNodeRules (see FEATURE-2D).
        val domainMap = LinkedHashMap<String, String>()
        for (rule in domainNodeRules)
            domainMap["${rule.type}|${rule.pattern}"] = rule.target
        val safeDomainMap = filterAppNodeRulesToKnownTargets(
            stagingTmp.resolve("config.yaml"), domainMap
        )
        val safeDomainRules = safeDomainMap.entries.map { (key, target) ->
            val sep = key.indexOf('|')
            DomainRule(key.substring(0, sep), key.substring(sep + 1), target)
        }
        injectDomainNodeRulesAfterReject(
            stagingTmp.resolve("config.yaml"),
            buildDomainNodeRuleLines(safeDomainRules)
        )
        // FEATURE-DOMAINNODE END
        injectAppNodeRulesBeforeMatch(
            stagingTmp.resolve("config.yaml"),
            buildAppNodeRuleLines(safeAppNodeRules)
        )
        // FEATURE-APPNODE-FALLBACK END

        // FEATURE-PASTE: pasted nodes are injected after the rules (the
        // sections are independent, so order between the two does not
        // matter; the file is written twice, once per injector).
        injectPastedProxies(stagingTmp.resolve("config.yaml"), pasted)

        staging.deleteRecursively()
        if (!stagingTmp.renameTo(staging))
            return stagingTmp

        return staging
    }

    // FEATURE-1: REJECT rule lines for blocked apps. Order matters:
    // these lines are always placed BEFORE any FEATURE-2 per-app node rule
    // (at the very top, while node rules now sit just before MATCH),
    // so a blocked app is REJECTed even if it also has a node rule (mihomo
    // matches rules in order, first match wins).
    private fun buildBlockRuleLines(blocked: Set<String>): List<String> {
        return blocked.sorted().map { "- PROCESS-NAME,$it,REJECT" }
    }

    // FEATURE-2 BEGIN: one PROCESS-NAME rule per app, pointing at the
    // node/group the user picked in the UI. Sorted by package for stable
    // output. No YAML is ever hand-edited by the user: these lines are
    // generated here at load time from ServiceStore.appNodeRules.
    // A target name containing ',' can never be expressed in a
    // comma-separated rule line, so such rules are skipped (the picker
    // already filters those names out; this is defence in depth).
    private fun buildAppNodeRuleLines(appNodeRules: Map<String, String>): List<String> {
        return appNodeRules.entries
            .filter { ',' !in it.value }
            .sortedBy { it.key }
            .map { "- PROCESS-NAME,${it.key},${it.value}" }
    }

    // FEATURE-DOMAINNODE BEGIN: rule lines for domain rules, sorted
    // by type rank (DOMAIN, then DOMAIN-SUFFIX, then DOMAIN-KEYWORD)
    // with insertion order preserved within one type (Kotlin's
    // sortedBy is stable, and the input list is insertion-ordered, so
    // first-added wins within a type). A target containing ',' is
    // skipped, as with appNodeRules: it cannot be expressed in a
    // comma-separated rule line.
    private fun buildDomainNodeRuleLines(domainRules: List<DomainRule>): List<String> {
        val rank = mapOf("DOMAIN" to 0, "DOMAIN-SUFFIX" to 1, "DOMAIN-KEYWORD" to 2)
        return domainRules
            .filter { rule ->
                // Second layer of defence (parse already drops these):
                // a comma/tab/newline in either field must never reach
                // a generated rule line.
                val bad = listOf(',', '\t', '\n', '\r', '|')
                bad.none { rule.pattern.contains(it) || rule.target.contains(it) }
            }
            .sortedBy { rank[it.type] ?: 99 }
            .map { "- ${it.type},${it.pattern},${it.target}" }
    }

    // FEATURE-DOMAINNODE: insert domain rule lines immediately after
    // the LAST REJECT rule in the rules section. That position is what
    // makes both Lu decisions hold at once: every subscription REJECT
    // rule (ads, trackers) stays above domain rules, while domain rules
    // stay above the .cn/GEOIP,CN DIRECT rules that sit near MATCH.
    // Rules before the ad REJECT block (specific subscription rules)
    // still win over domain rules -- that is the documented, accepted
    // consequence of "ads REJECT above domain", not a bug: only the
    // general DIRECT rules near the end are beaten by domain rules.
    // Indent matching follows injectFeatureRules (FEATURE-2B).
    private fun injectDomainNodeRulesAfterReject(configFile: File, ruleLines: List<String>) {
        if (ruleLines.isEmpty())
            return
        if (!configFile.exists()) {
            Log.w("config.yaml missing, domain node rules not applied")
            return
        }

        val lines = configFile.readLines().toMutableList()

        // Case 1: block-style `rules:` list at column 0.
        val blockStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.removePrefix("rules:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (blockStyle >= 0) {
            val itemIndent = detectSectionItemIndent(lines, blockStyle)
            val end = sectionEnd(lines, blockStyle)
            // Last item whose final comma field is REJECT. Block
            // REJECT lines (PROCESS-NAME,...,REJECT) count, so with no
            // subscription REJECT rules the domain lines land right
            // after the block lines, still above every subscription
            // rule; with subscription REJECT rules they land after the
            // last of those instead.
            var insertAt = blockStyle + 1
            for (i in blockStyle + 1 until end) {
                val line = lines[i]
                if (line.isBlank() || line.trimStart().startsWith("#"))
                    continue
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("- "))
                    continue
                val ruleText = trimmed.removePrefix("- ").trim().trim('"', '\'').trim()
                // Any comma field being REJECT counts (a REJECT rule
                // could in principle carry a trailing parameter, and
                // then the last field would not be REJECT itself).
                val isReject = ruleText.split(",").any {
                    it.trim().equals("REJECT", ignoreCase = true)
                }
                if (isReject)
                    insertAt = i + 1
            }
            lines.addAll(insertAt, ruleLines.map { itemIndent + it })
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Case 2: single-line flow-style `rules: [...]` -> insert
        // after the last REJECT item, or prepend when there is none.
        // Items contain commas, so REJECT items are found by regex on
        // item boundaries (after `[` or `,`, optional spaces/quotes),
        // never by splitting the whole line on commas.
        val flowStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.contains("[") && it.contains("]")
        }
        if (flowStyle >= 0) {
            val line = lines[flowStyle]
            val open = line.indexOf('[')
            val close = line.indexOf(']', open)
            if (open < 0 || close < 0) {
                Log.w("Unsupported rules format, domain node rules not applied")
                return
            }
            val items = ruleLines.joinToString(", ") { "\"${it.removePrefix("- ")}\"" }
            val reject = Regex("""(?:\[|,\s*"?)[^",\]]*,[^",\]]*,REJECT"?""")
                .findAll(line.substring(open, close))
                .lastOrNull()
            lines[flowStyle] = if (reject != null) {
                val at = open + reject.range.last + 1
                line.substring(0, at) + ", " + items + line.substring(at)
            } else {
                val inner = line.substring(open + 1, close).trim()
                if (inner.isEmpty()) {
                    line.substring(0, open + 1) + items + line.substring(close)
                } else {
                    line.substring(0, open + 1) + items + ", " + line.substring(open + 1)
                }
            }
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Case 3: no top-level `rules:` key at all -> append a block.
        if (lines.none { it.startsWith("rules:") }) {
            lines.add("")
            lines.add("rules:")
            lines.addAll(ruleLines)
            configFile.writeText(lines.joinToString("\n") + "\n")
        } else {
            Log.w("Unsupported rules format, domain node rules not applied")
        }
    }
    // FEATURE-DOMAINNODE END

    // FEATURE-2D: known proxy/group names from a config file, used only
    // to drop rules pointing at targets this config does not have.
    // Fail-safe by design, so a parse miss can never delete a valid
    // rule from the load: provider-based configs are skipped outright
    // (their node names live in provider files, not here), flow-style
    // sections yield no names, and an empty known set keeps all rules.
    // Only top-level `proxies:` / `proxy-groups:` sections are read;
    // an indented `proxies:` inside a group item must not reset the
    // section (column-0 lines are the only section boundaries).
    private fun filterAppNodeRulesToKnownTargets(
        configFile: File,
        rules: Map<String, String>,
    ): Map<String, String> {
        if (rules.isEmpty() || !configFile.exists())
            return rules
        val lines = configFile.readLines()
        if (lines.any { it.startsWith("proxy-providers:") })
            return rules
        val known = HashSet<String>()
        var section: String? = null
        for (line in lines) {
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                if (line.contains(":"))
                    section = line.substringBefore(":").trim()
                continue
            }
            if (section != "proxies" && section != "proxy-groups")
                continue
            var trimmed = line.trimStart()
            if (trimmed.startsWith("- "))
                trimmed = trimmed.removePrefix("- ").trimStart()
            if (!trimmed.startsWith("name:"))
                continue
            var value = trimmed.removePrefix("name:").trim()
            if (!value.startsWith("\"") && !value.startsWith("'"))
                value = value.substringBefore("#").trim()
            value = value.trim('"', '\'').trim()
            if (value.isNotEmpty())
                known.add(value)
        }
        if (known.isEmpty())
            return rules
        return rules.filterValues { it in known }
    }
    // FEATURE-2 END

    // Shared staging-rule inserter (FEATURE-1 only since
    // FEATURE-APPNODE-FALLBACK; FEATURE-2 now uses
    // injectAppNodeRulesBeforeMatch). It only ever
    // inserts one block, so a duplicate `rules:` key can never be created.
    private fun injectFeatureRules(configFile: File, ruleLines: List<String>) {
        if (ruleLines.isEmpty())
            return
        if (!configFile.exists()) {
            Log.w("config.yaml missing, feature rules not applied")
            return
        }

        val lines = configFile.readLines().toMutableList()

        // Case 1: block-style `rules:` list at column 0 -> insert right after
        // it, so the feature rules win over every later rule. Column 0 is
        // required (startsWith, not trim): an indented `rules:` inside
        // another section must never match. A trailing comment is allowed.
        // FEATURE-2B BEGIN: the inserted lines MUST use the same indent
        // as the section's existing items. Inserting at column 0 into a
        // list whose items are indented (e.g. 4 spaces, as in HENET
        // configs and many airport subscriptions) breaks YAML parsing
        // under Go yaml.v3 (mihomo: "did not find expected key"), which
        // fails Clash.load, kills the service and breaks imports. The
        // scan skips blank/comment lines and stops at the next column-0
        // line (the next top-level key = an empty rules section).
        val blockStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.removePrefix("rules:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (blockStyle >= 0) {
            var itemIndent = "  "
            for (i in blockStyle + 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank())
                    continue
                val trimmed = line.trimStart()
                if (trimmed.startsWith("#"))
                    continue
                // A column-0 item counts too (prefix is empty): check the
                // dash first, otherwise col-0 items would be mistaken for
                // the next top-level key and get the wrong indent.
                if (trimmed.startsWith("- ")) {
                    itemIndent = line.substring(0, line.indexOf('-'))
                    break
                }
                if (!line.startsWith(" ") && !line.startsWith("\t"))
                    break
            }
            val indented = ruleLines.map { itemIndent + it }
            lines.addAll(blockStyle + 1, indented)
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }
        // FEATURE-2B END

        // Case 2: single-line flow-style `rules: [...]` -> prepend items
        // inside [ ]. Block lines lose their "- " prefix inside flow style.
        val flowStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.contains("[") && it.contains("]")
        }
        if (flowStyle >= 0) {
            val line = lines[flowStyle]
            val bracket = line.indexOf("[")
            val prefix = line.substring(0, bracket + 1)
            val rest = line.substring(bracket + 1)
            val items = ruleLines.joinToString(", ") { "\"${it.removePrefix("- ")}\"" }
            lines[flowStyle] = if (rest.trim() == "]") {
                "$prefix$items]"
            } else {
                "$prefix$items, $rest"
            }
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Case 3: no top-level `rules:` key at all -> append a block at the end.
        // Any other `rules:` form (e.g. multi-line flow) is left untouched so
        // a duplicate key can never break parsing; the profile still loads.
        if (lines.none { it.startsWith("rules:") }) {
            lines.add("")
            lines.add("rules:")
            lines.addAll(ruleLines)
            configFile.writeText(lines.joinToString("\n") + "\n")
        } else {
            Log.w("Unsupported rules format, feature rules not applied")
        }
    }

    // FEATURE-APPNODE-FALLBACK BEGIN: per-app node rules as the
    // fallback, after every subscription rule but before the terminal
    // MATCH/FINAL rule. Putting them literally after MATCH would make
    // them dead code (MATCH matches everything), so "after the rules"
    // means after all specific rules, with MATCH still last as the
    // group's own fallback. Blocked REJECT lines are injected
    // separately by injectFeatureRules and stay at the very top, so
    // they still win over both subscription rules and node rules.
    // Indent matching follows the same rule as injectFeatureRules
    // (FEATURE-2B): use the section's existing item indent, so
    // mixed-indent YAML errors cannot occur.
    private fun injectAppNodeRulesBeforeMatch(configFile: File, ruleLines: List<String>) {
        if (ruleLines.isEmpty())
            return
        if (!configFile.exists()) {
            Log.w("config.yaml missing, app node rules not applied")
            return
        }

        val lines = configFile.readLines().toMutableList()

        // Case 1: block-style `rules:` list at column 0.
        val blockStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.removePrefix("rules:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (blockStyle >= 0) {
            val itemIndent = detectSectionItemIndent(lines, blockStyle)
            val end = sectionEnd(lines, blockStyle)
            var insertAt = end
            for (i in blockStyle + 1 until end) {
                val line = lines[i]
                if (line.isBlank() || line.trimStart().startsWith("#"))
                    continue
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("- "))
                    continue
                val ruleText = trimmed.removePrefix("- ").trim()
                    .trim('"', '\'').trim()
                val head = ruleText.substringBefore(",").trim()
                if (head.equals("MATCH", ignoreCase = true) ||
                    head.equals("FINAL", ignoreCase = true)) {
                    insertAt = i
                    break
                }
            }
            lines.addAll(insertAt, ruleLines.map { itemIndent + it })
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Case 2: single-line flow-style `rules: [...]` -> insert
        // before the MATCH/FINAL item, or before `]` when there is none.
        // Items themselves contain commas (e.g. DOMAIN-SUFFIX,a,DIRECT),
        // so the terminal item is found by scanning for an item start
        // (after `[` or `,`, optional spaces/quotes) followed by
        // MATCH, or FINAL, and a comma, not by splitting on commas.
        val flowStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.contains("[") && it.contains("]")
        }
        if (flowStyle >= 0) {
            val line = lines[flowStyle]
            val open = line.indexOf('[')
            val close = line.indexOf(']', open)
            if (open < 0 || close < 0) {
                Log.w("Unsupported rules format, app node rules not applied")
                return
            }
            val items = ruleLines.joinToString(", ") { "\"${it.removePrefix("- ")}\"" }
            val terminal = Regex("""(?:\[|,\s*"?)(MATCH|FINAL),""")
                .find(line, open)
            lines[flowStyle] = if (terminal != null) {
                // Item start = just after the `[` or `,` separator,
                // skipping spaces, landing on the item's opening quote
                // (if quoted) or on MATCH/FINAL itself. Inserting there
                // keeps the separator and the quote with the terminal
                // item, so no quoted string is split in two.
                var itemStart = terminal.range.first + 1
                while (itemStart < line.length && line[itemStart] == ' ')
                    itemStart++
                line.substring(0, itemStart) + items + ", " + line.substring(itemStart)
            } else {
                val inner = line.substring(open + 1, close).trim()
                if (inner.isEmpty()) {
                    line.substring(0, open + 1) + items + line.substring(close)
                } else {
                    line.substring(0, close) + ", " + items + line.substring(close)
                }
            }
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Case 3: no top-level `rules:` key at all -> append a block.
        if (lines.none { it.startsWith("rules:") }) {
            lines.add("")
            lines.add("rules:")
            lines.addAll(ruleLines)
            configFile.writeText(lines.joinToString("\n") + "\n")
        } else {
            Log.w("Unsupported rules format, app node rules not applied")
        }
    }
    // FEATURE-APPNODE-FALLBACK END

    // FEATURE-PASTE BEGIN: pasted nodes ride the same staging copy as the
    // feature rules, so they survive subscription updates (staging is
    // rebuilt from the imported originals on every load). Two edits:
    //  1. each pasted YAML block is appended to the top-level `proxies:`
    //     section (created if absent), re-indented to that section's item
    //     indent -- the same indent-matching rule as injectFeatureRules;
    //  2. each pasted name is appended to every Selector group's `proxies:`
    //     list (groups without a proxies list, e.g. provider-only `use:`
    //     groups, and url-test/fallback/load-balance groups are skipped).
    //     A name already in a group's list is not duplicated. Only names
    //     actually injected in step 1 are appended: a group must never
    //     reference a proxy the config does not define (mihomo fails the
    //     whole load with "proxy [...] not found").
    //  Provider-based configs still get step 1 (inline proxies coexist
    //  with providers); their pasted nodes are usable in global mode,
    //  where the GLOBAL group lists every proxy.
    private fun injectPastedProxies(configFile: File, entries: List<PastedProxyEntry>) {
        if (entries.isEmpty())
            return
        if (!configFile.exists()) {
            Log.w("config.yaml missing, pasted proxies not applied")
            return
        }
        val lines = configFile.readLines().toMutableList()
        var changed = false

        // ---- step 1: proxies: section ----
        val proxiesIdx = lines.indexOfFirst {
            it.startsWith("proxies:") && it.removePrefix("proxies:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        val flowProxiesIdx = if (proxiesIdx < 0) lines.indexOfFirst {
            it.startsWith("proxies:") && it.contains("[") && it.contains("]")
        } else -1
        if (flowProxiesIdx >= 0) {
            // Flow-style inline proxies cannot take block items without
            // rewriting the user's structure; skip both steps for safety.
            Log.w("Flow-style proxies section, pasted proxies not applied")
            return
        }
        if (proxiesIdx >= 0) {
            val itemIndent = detectSectionItemIndent(lines, proxiesIdx)
            val insertAt = sectionEnd(lines, proxiesIdx)
            val toAdd = ArrayList<String>()
            for (entry in entries) {
                for (line in entry.yamlLines)
                    toAdd.add(itemIndent + line)
            }
            lines.addAll(insertAt, toAdd)
            changed = true
        } else {
            // No proxies: section at all: create one at the end.
            lines.add("")
            lines.add("proxies:")
            for (entry in entries) {
                for (line in entry.yamlLines)
                    lines.add("  " + line)
            }
            changed = true
        }

        // ---- step 2: Selector groups' proxies: lists ----
        // Search the UPDATED list (step 1 may have appended a section).
        val groupsIdx = lines.indexOfFirst {
            it.startsWith("proxy-groups:") && it.removePrefix("proxy-groups:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (groupsIdx >= 0) {
            val groupIndent = detectSectionItemIndent(lines, groupsIdx)
            val injectedNames = entries.map { it.name }
            // Section end and item indices shift with every edit, so both
            // are recomputed from the current list on each iteration, and
            // the next-group search is always bounded by the current
            // section end (a later section's items share the indent and
            // must never be mistaken for groups).
            var i = groupsIdx + 1
            while (true) {
                val end = sectionEnd(lines, groupsIdx)
                if (i >= end) break
                if (isSectionItem(lines[i], groupIndent)) {
                    val blockEnd = findNextSectionItem(lines, i + 1, end, groupIndent)
                    if (appendToGroupIfSelector(lines, i, blockEnd, injectedNames))
                        changed = true
                    val newEnd = sectionEnd(lines, groupsIdx)
                    val next = findNextSectionItem(lines, i + 1, newEnd, groupIndent)
                    if (next >= newEnd) break
                    i = next
                } else {
                    i++
                }
            }
        }

        if (changed)
            configFile.writeText(lines.joinToString("\n") + "\n")
    }

    // Appends injectedNames to one group's proxies: list if the group is a
    // Selector (or has no type, which defaults to select) and has a
    // proxies list. Returns true when the lines changed. Block edits are
    // done in place; the caller recomputes indices afterwards.
    private fun appendToGroupIfSelector(
        lines: MutableList<String>,
        blockStart: Int,
        blockEnd: Int,
        names: List<String>,
    ): Boolean {
        // Scan the whole block: field order varies (type: may come
        // after proxies:), so an early break at proxies: could miss a
        // url-test/fallback type and wrongly treat the group as a
        // selector. First occurrences win; groups have at most one of
        // each key.
        var type: String? = null
        var proxiesLine = -1
        var proxiesFlow = false
        for (j in blockStart + 1 until blockEnd.coerceAtMost(lines.size)) {
            val trimmed = lines[j].trim()
            if (type == null && trimmed.startsWith("type:")) {
                type = unquoteYamlScalar(trimmed.removePrefix("type:").trim())
            } else if (proxiesLine < 0 && trimmed.startsWith("proxies:")) {
                proxiesLine = j
                proxiesFlow = trimmed.contains("[") && trimmed.contains("]")
            }
        }
        if (proxiesLine < 0) return false
        if (type != null && type != "select") return false

        if (proxiesFlow) {
            val line = lines[proxiesLine]
            val open = line.indexOf('[')
            val close = line.indexOf(']', open)
            if (open < 0 || close < 0) return false
            val existing = line.substring(open + 1, close).split(",")
                .map { unquoteYamlScalar(it.trim()) }
                .filter { it.isNotEmpty() }
                .toSet()
            val missing = names.filter { it !in existing }
            if (missing.isEmpty()) return false
            val inner = (existing + missing).joinToString(", ") { yamlQuote(it) }
            val trailing = line.substring(close + 1)
            lines[proxiesLine] = line.substring(0, open + 1) + inner + "]" + trailing
            return true
        }

        // Block-style list. Two YAML styles exist, and the old code only
        // handled one: indented items (deeper than the proxies: key).
        // Many Clash configs use indentless sequences, where items sit
        // at the SAME indent as the proxies: key itself:
        //   proxies:
        //   - a
        //   - b
        // The old loop broke on the first such item (indent <= list
        // indent), then inserted the pasted name at listIndent + "  ",
        // producing a mixed-indent sequence:
        //   proxies:
        //     - "pasted"
        //   - a
        // Go yaml.v3 rejects the dedented "- a" with "did not find
        // expected key", Clash.load fails, and the VPN service stops
        // (seen in the 2026-10-30 log: staging config line 42).
        // Fix: a "- " line at indent >= listIndent is a list item;
        // any other non-blank line ends the list. The inserted names
        // then use the existing items' indent, so the sequence stays
        // uniform in both styles. An empty list still falls back to
        // listIndent + "  ", which is valid when no items exist to
        // conflict with.
        val listIndent = lines[proxiesLine].let { it.substring(0, it.indexOf("proxies:")) }
        var insertAt = proxiesLine + 1
        var itemIndent: String? = null
        val existing = HashSet<String>()
        var j = proxiesLine + 1
        while (j < blockEnd.coerceAtMost(lines.size)) {
            val lj = lines[j]
            if (lj.isBlank() || lj.trimStart().startsWith("#")) {
                j++
                continue
            }
            val trimmed = lj.trimStart()
            if (!trimmed.startsWith("- "))
                break
            val indent = lj.substring(0, lj.length - lj.trimStart().length)
            if (indent.length < listIndent.length)
                break
            if (itemIndent == null) itemIndent = indent
            existing.add(unquoteYamlScalar(trimmed.removePrefix("- ").trim()))
            insertAt = j + 1
            j++
        }
        val missing = names.filter { it !in existing }
        if (missing.isEmpty()) return false
        val indent = itemIndent ?: (listIndent + "  ")
        lines.addAll(insertAt, missing.map { "$indent- ${yamlQuote(it)}" })
        return true
    }

    // First index after sectionIdx that starts the next top-level key:
    // a non-blank, non-comment line at column 0 that is not a "- " item.
    private fun sectionEnd(lines: List<String>, sectionIdx: Int): Int {
        for (i in sectionIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#"))
                continue
            if (!line.startsWith(" ") && !line.startsWith("\t") && !line.trimStart().startsWith("- "))
                return i
        }
        return lines.size
    }

    // Indent of the first "- " item in a section, or "  " when the section
    // has no items yet (column-0 items count with an empty prefix).
    private fun detectSectionItemIndent(lines: List<String>, sectionIdx: Int): String {
        val end = sectionEnd(lines, sectionIdx)
        for (i in sectionIdx + 1 until end) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#"))
                continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("- "))
                return line.substring(0, line.indexOf('-'))
        }
        return "  "
    }

    private fun isSectionItem(line: String, indent: String): Boolean {
        if (!line.startsWith(indent)) return false
        val rest = line.substring(indent.length)
        return rest.startsWith("- ")
    }

    private fun findNextSectionItem(
        lines: List<String>,
        from: Int,
        until: Int,
        indent: String,
    ): Int {
        for (i in from until until.coerceAtMost(lines.size)) {
            if (isSectionItem(lines[i], indent))
                return i
        }
        return until.coerceAtMost(lines.size)
    }

    private fun unquoteYamlScalar(raw: String): String {
        val value = raw.trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\""))
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        if (value.length >= 2 && value.startsWith("'") && value.endsWith("'"))
            return value.substring(1, value.length - 1).replace("''", "'")
        return value.substringBefore("#").trim()
    }

    private fun yamlQuote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    // FEATURE-PASTE END

    // FEATURE-1 END

}
