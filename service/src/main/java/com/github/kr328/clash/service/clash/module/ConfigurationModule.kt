package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.parseAppNodeRules
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
    // (FEATURE-2 extends this same staging path: per-app node rules are
    // appended after the FEATURE-1 REJECT lines, never before them.)
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
        // FEATURE-2: per-app node rules ride the same staging copy.
        val appNodeRules = parseAppNodeRules(store.appNodeRules)
        val imported = service.importedDir.resolve(uuid.toString())
        // FEATURE-4: the global-auto group also needs the staging copy.
        // All three off -> original dir untouched (baseline unchanged).
        if (blocked.isEmpty() && appNodeRules.isEmpty() && !store.globalAutoEnabled)
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

        injectFeatureRules(
            stagingTmp.resolve("config.yaml"),
            // FEATURE-1 lines first, FEATURE-2 lines after: REJECT always
            // wins over a node rule for the same app (first match wins).
            buildBlockRuleLines(blocked) + buildAppNodeRuleLines(appNodeRules)
        )

        // FEATURE-4: cross-provider fastest group (separate section, no
        // interaction with the rule lines above).
        injectGlobalAutoGroup(stagingTmp.resolve("config.yaml"))

        staging.deleteRecursively()
        if (!stagingTmp.renameTo(staging))
            return stagingTmp

        return staging
    }

    // FEATURE-1: REJECT rule lines for blocked apps. Order matters:
    // these lines are always placed BEFORE any FEATURE-2 per-app node rule,
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
    // FEATURE-2 END

    // Shared staging-rule inserter (FEATURE-1; FEATURE-2 reuses it with its
    // own rule lines appended after the FEATURE-1 lines). It only ever
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
        val blockStyle = lines.indexOfFirst {
            it.startsWith("rules:") && it.removePrefix("rules:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (blockStyle >= 0) {
            lines.addAll(blockStyle + 1, ruleLines)
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

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
    // FEATURE-1 END

    // FEATURE-4 BEGIN: cross-provider fastest group ("全局自动").
    // Appends one url-test group that `use:`s every proxy-provider in the
    // config, so mihomo's fastest selection spans all subscriptions. The
    // user then picks this group in the Proxy page like any other group.
    // Scope limits, all fail-safe (log + skip, profile still loads):
    // - only proxy-provider configs are covered; inline-`proxies:` configs
    //   have no provider names to `use:`, so nothing is injected;
    // - a flow-style `proxy-groups: [...]` line cannot take an inserted
    //   item, so it is skipped rather than rewritten;
    // - if the config already has a group of the same name, injection is
    //   skipped (a duplicate group name would break the load).
    private val globalAutoGroupName = "🚀 全局自动"

    private fun injectGlobalAutoGroup(configFile: File) {
        if (!store.globalAutoEnabled)
            return
        if (!configFile.exists()) {
            Log.w("config.yaml missing, global-auto group not applied")
            return
        }

        val lines = configFile.readLines().toMutableList()

        if (lines.any { it.contains(globalAutoGroupName) }) {
            Log.w("Global-auto group name already present, skipping injection")
            return
        }

        val providers = parseProviderNames(lines)
        if (providers.isEmpty()) {
            Log.w("No proxy-providers found, global-auto group not applied")
            return
        }

        val groupLines = mutableListOf(
            "  - name: ${yamlQuote(globalAutoGroupName)}",
            "    type: url-test",
            "    use:",
        )
        providers.forEach { groupLines.add("      - ${yamlQuote(it)}") }
        groupLines.add("    url: https://www.gstatic.com/generate_204")
        groupLines.add("    interval: 300")
        groupLines.add("    lazy: false")

        val groupsAt = lines.indexOfFirst {
            // Column-0 key, trailing comment allowed (a comment-only rest
            // must NOT be mistaken for "no such section": appending a
            // second `proxy-groups:` block would be a duplicate key and
            // break the load).
            it.startsWith("proxy-groups:") && it.removePrefix("proxy-groups:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (groupsAt < 0) {
            // No proxy-groups section at all: append a new one at the end.
            lines.add("")
            lines.add("proxy-groups:")
            lines.addAll(groupLines)
            configFile.writeText(lines.joinToString("\n") + "\n")
            return
        }

        // Flow-style means '[' in the value part (before any comment).
        if (lines[groupsAt].removePrefix("proxy-groups:").substringBefore("#").contains("[")) {
            Log.w("Flow-style proxy-groups, global-auto group not applied")
            return
        }

        // Insert at the end of the existing proxy-groups block: the first
        // following line that is non-blank and starts at column 0 is the
        // next top-level key (block items are indented).
        var insertAt = groupsAt + 1
        while (insertAt < lines.size) {
            val line = lines[insertAt]
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t"))
                break
            insertAt++
        }
        lines.addAll(insertAt, groupLines)
        configFile.writeText(lines.joinToString("\n") + "\n")
    }

    // Provider names are the exactly-2-space-indented keys directly under
    // the top-level `proxy-providers:` key. Deeper-indented lines are the
    // providers' own options and must be SKIPPED (not break on: breaking
    // at the first option line would collect only the first provider).
    // The scan ends only at the next column-0 line (the next top-level
    // key) or end of file. A flow-style `proxy-providers: {...}` line has
    // no such following lines and yields an empty list.
    private fun parseProviderNames(lines: List<String>): List<String> {
        val start = lines.indexOfFirst {
            it.startsWith("proxy-providers:") && it.removePrefix("proxy-providers:").trim().let { rest ->
                rest.isEmpty() || rest.startsWith("#")
            }
        }
        if (start < 0)
            return emptyList()

        val result = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank())
                continue
            if (!line.startsWith(" "))
                break
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#"))
                continue
            val indent = line.length - trimmed.length
            if (indent != 2)
                continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0)
                continue
            val name = trimmed.substring(0, colon).trim().trim('"', '\'')
            if (name.isNotEmpty())
                result.add(name)
        }
        return result
    }

    private fun yamlQuote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    // FEATURE-4 END
}
