package com.github.kr328.clash

import android.util.Base64
import java.net.URI

// FEATURE-PASTE: parse a pasted proxy share link into mihomo YAML lines.
//
// Supported schemes (v1): vless, trojan, ss (shadowsocks, plain and
// base64 forms), vmess (base64 JSON form only), hysteria2, tuic, socks5,
// http/https. Anything else, or a link missing its required fields,
// fails with PasteParseException carrying a Chinese message for the UI.
//
// The result is opaque YAML text for the loader: the first line is
// "- name: ...", continuation lines are indented two spaces, and the
// loader re-indents the whole block to the config's own item indent at
// inject time (the same indent-matching rule as the feature-rule
// injector). Numbers and booleans are emitted bare; every other scalar
// is double-quoted so names/paths/hosts with spaces, emoji, CJK text or
// YAML-special characters can never break parsing. udp is always true:
// these are VPN nodes, and UDP traffic must go through the tunnel.
//
// Percent-decoding is deliberately %XX-only (a '+' stays a '+'): share
// links percent-encode their specials, and '+' is a legal literal in
// passwords, paths and names here.
class PasteParseException(message: String) : Exception(message)

data class ParsedProxy(
    val name: String,
    val yamlLines: List<String>,
)

object PasteProxyParser {
    // takenNames: node/group names already live in this profile. A pasted
    // node colliding with one is renamed ("name (2)", "name (3)", ...) so
    // the loader never sees two proxies with the same name.
    fun parse(rawLink: String, takenNames: Set<String> = emptySet()): ParsedProxy {
        val parsed = parseInner(rawLink)
        if (parsed.name !in takenNames)
            return parsed
        var i = 2
        var newName: String
        do {
            newName = "${parsed.name} ($i)"
            i++
        } while (newName in takenNames)
        val lines = listOf("- name: ${yamlStr(newName)}") + parsed.yamlLines.drop(1)
        return ParsedProxy(newName, lines)
    }

    private fun parseInner(rawLink: String): ParsedProxy {
        val link = rawLink.trim()
        if (link.isEmpty())
            throw PasteParseException("粘贴的链接是空的")
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            throw PasteParseException("链接格式不对，无法解析")
        }
        return when (uri.scheme?.lowercase()) {
            "vless" -> parseVless(uri)
            "trojan" -> parseTrojan(uri)
            "ss" -> parseShadowsocks(link, uri)
            "vmess" -> parseVmess(link)
            "hysteria2", "hy2" -> parseHysteria2(uri)
            "tuic" -> parseTuic(uri)
            "socks5", "socks" -> parseSocks(uri)
            "http", "https" -> parseHttp(uri)
            null -> throw PasteParseException("链接没有协议头（比如 vless://、trojan://）")
            else -> throw PasteParseException("暂不支持 ${uri.scheme} 协议")
        }
    }

    // ---------- shared helpers ----------

    private fun pctDecode(raw: String?): String {
        if (raw == null) return ""
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val v = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.add(v.toByte())
                    i += 3
                    continue
                }
            }
            // Non-ASCII chars in the raw string are appended as UTF-8.
            bytes.addAll(c.toString().toByteArray(Charsets.UTF_8).toList())
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun queryParams(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                result[pctDecode(pair)] = ""
            } else {
                result[pctDecode(pair.substring(0, eq))] = pctDecode(pair.substring(eq + 1))
            }
        }
        return result
    }

    private fun decodeBase64Loose(input: String): ByteArray {
        var s = input.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        repeat(pad) { s += "=" }
        return try {
            Base64.decode(s, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw PasteParseException("链接里的 base64 部分解不开")
        }
    }

    private fun requireHost(uri: URI): String {
        uri.host?.takeIf { it.isNotEmpty() }?.let { return it }
        // Fallback for hosts java.net.URI will not expose (underscores,
        // unusual literals): strip userinfo and the port by hand.
        val authority = uri.rawAuthority
            ?: throw PasteParseException("链接里没有服务器地址")
        val noUser = authority.substringAfterLast('@')
        val noPort = noUser
            .removePrefix("[")
            .substringBeforeLast("]")
            .let { bracketed ->
                if (noUser.startsWith("[")) bracketed
                else noUser.substringBeforeLast(":").ifEmpty { noUser }
            }
        return noPort.takeIf { it.isNotEmpty() }
            ?: throw PasteParseException("链接里没有服务器地址")
    }

    private fun requirePort(uri: URI): Int {
        val port = uri.port
        if (port <= 0 || port > 65535)
            throw PasteParseException("链接里没有合法端口")
        return port
    }

    private fun requireName(uri: URI, fallback: String): String {
        val name = pctDecode(uri.rawFragment).trim()
        return name.ifEmpty { fallback }
    }

    private fun requireUuid(uuid: String, what: String = "UUID"): String {
        val uuidRegex = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        if (!uuidRegex.matches(uuid))
            throw PasteParseException("$what 格式不对（应为 8-4-4-4-12 的十六进制）")
        return uuid
    }

    private fun userInfoParts(uri: URI): List<String> {
        val raw = uri.rawUserInfo ?: return emptyList()
        return raw.split(":").map { pctDecode(it) }
    }

    private fun yamlStr(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun boolParam(params: Map<String, String>, vararg keys: String): Boolean? {
        for (key in keys) {
            val v = params[key] ?: continue
            return v == "1" || v.equals("true", ignoreCase = true)
        }
        return null
    }

    private fun alpnLines(alpn: String?): List<String> {
        if (alpn.isNullOrBlank()) return emptyList()
        val items = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return emptyList()
        return listOf("  alpn:") + items.map { "    - ${yamlStr(it)}" }
    }

    private fun wsLines(params: Map<String, String>): List<String> {
        val path = params["path"]
        val host = params["host"] ?: params["Host"]
        if (path == null && host == null) return emptyList()
        val lines = ArrayList<String>()
        lines.add("  ws-opts:")
        if (path != null) lines.add("    path: ${yamlStr(path)}")
        if (host != null) {
            lines.add("    headers:")
            lines.add("      Host: ${yamlStr(host)}")
        }
        return lines
    }

    private fun grpcLines(params: Map<String, String>): List<String> {
        val service = params["serviceName"] ?: params["grpc-service-name"] ?: return emptyList()
        return listOf("  grpc-opts:", "    grpc-service-name: ${yamlStr(service)}")
    }

    private fun tlsOf(params: Map<String, String>): Boolean? {
        val security = params["security"]?.lowercase()
        return when (security) {
            "tls", "reality" -> true
            "none" -> false
            else -> boolParam(params, "tls")
        }
    }

    private fun sniOf(params: Map<String, String>): String? =
        params["sni"] ?: params["servername"] ?: params["peer"]

    private fun finish(name: String, type: String, body: List<String>): ParsedProxy {
        val lines = ArrayList<String>()
        lines.add("- name: ${yamlStr(name)}")
        lines.add("  type: $type")
        lines.addAll(body)
        return ParsedProxy(name, lines)
    }

    // ---------- protocols ----------

    private fun parseVless(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        val uuid = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: throw PasteParseException("VLESS 链接里没有 UUID")
        requireUuid(uuid)
        val host = requireHost(uri)
        val port = requirePort(uri)
        val params = queryParams(uri)
        val network = params["type"]?.lowercase() ?: "tcp"
        val security = params["security"]?.lowercase()
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  uuid: ${yamlStr(uuid)}")
        params["flow"]?.takeIf { it.isNotEmpty() }?.let { body.add("  flow: ${yamlStr(it)}") }
        body.add("  network: $network")
        tlsOf(params)?.let { body.add("  tls: $it") }
        sniOf(params)?.let { body.add("  servername: ${yamlStr(it)}") }
        boolParam(params, "allowInsecure", "insecure", "skip-cert-verify")?.let {
            body.add("  skip-cert-verify: $it")
        }
        if (network == "ws") body.addAll(wsLines(params))
        if (network == "grpc") body.addAll(grpcLines(params))
        if (security == "reality") {
            val publicKey = params["pbk"]?.takeIf { it.isNotEmpty() }
                ?: throw PasteParseException("Reality 链接里没有公钥（pbk）")
            body.add("  reality-opts:")
            body.add("    public-key: ${yamlStr(publicKey)}")
            params["sid"]?.takeIf { it.isNotEmpty() }?.let {
                body.add("    short-id: ${yamlStr(it)}")
            }
        }
        body.add("  udp: true")
        return finish(requireName(uri, "$host:$port"), "vless", body)
    }

    private fun parseTrojan(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        val password = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: throw PasteParseException("Trojan 链接里没有密码")
        val host = requireHost(uri)
        val port = requirePort(uri)
        val params = queryParams(uri)
        val network = params["network"]?.lowercase() ?: params["type"]?.lowercase() ?: "tcp"
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  password: ${yamlStr(password)}")
        body.add("  network: $network")
        sniOf(params)?.let { body.add("  sni: ${yamlStr(it)}") }
        body.addAll(alpnLines(params["alpn"]))
        boolParam(params, "allowInsecure", "insecure", "skip-cert-verify")?.let {
            body.add("  skip-cert-verify: $it")
        }
        if (network == "ws") body.addAll(wsLines(params))
        if (network == "grpc") body.addAll(grpcLines(params))
        body.add("  udp: true")
        return finish(requireName(uri, "$host:$port"), "trojan", body)
    }

    private fun parseShadowsocks(link: String, uri: URI): ParsedProxy {
        val fragment = uri.rawFragment
        val nameFromFragment = pctDecode(fragment).trim()
        val (method, password, host, port, plugin) = parseShadowsocksParts(link, uri)
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  cipher: ${yamlStr(method)}")
        body.add("  password: ${yamlStr(password)}")
        body.add("  udp: true")
        plugin?.let { (pluginName, opts) ->
            body.add("  plugin: ${yamlStr(pluginName)}")
            if (opts.isNotEmpty()) {
                body.add("  plugin-opts:")
                for ((k, v) in opts) body.add("    $k: ${yamlStr(v)}")
            }
        }
        return finish(nameFromFragment.ifEmpty { "$host:$port" }, "ss", body)
    }

    // Returns method, password, host, port, plugin (name + opts).
    private fun parseShadowsocksParts(
        link: String,
        uri: URI,
    ): ShadowsocksParts {
        val rawAuthority = uri.rawAuthority ?: ""
        if (rawAuthority.contains("@")) {
            // Plain form: ss://method:password@host:port[?plugin=...]#name
            val userInfo = uri.rawUserInfo ?: ""
            val colon = userInfo.indexOf(':')
            if (colon <= 0)
                throw PasteParseException("SS 链接里没有加密方法和密码")
            val method = pctDecode(userInfo.substring(0, colon))
            val password = pctDecode(userInfo.substring(colon + 1))
            if (method.isEmpty() || password.isEmpty())
                throw PasteParseException("SS 链接里没有加密方法和密码")
            val plugin = queryParams(uri)["plugin"]?.let { parseSsPlugin(it) }
            return ShadowsocksParts(method, password, requireHost(uri), requirePort(uri), plugin)
        }
        // Base64 form: ss://BASE64(method:password@host:port[?plugin=...])#name
        var payload = link.substringAfter("ss://")
        payload = payload.substringBefore("#")
        val query = payload.substringAfter("?", "")
        payload = payload.substringBefore("?")
        val decoded = try {
            String(decodeBase64Loose(payload), Charsets.UTF_8)
        } catch (e: PasteParseException) {
            throw e
        } catch (_: Exception) {
            throw PasteParseException("SS 链接里的 base64 部分解不开")
        }
        val at = decoded.lastIndexOf('@')
        if (at <= 0)
            throw PasteParseException("SS 链接解开后找不到服务器地址")
        val userPart = decoded.substring(0, at)
        val hostPart = decoded.substring(at + 1)
        val colon = userPart.indexOf(':')
        if (colon <= 0)
            throw PasteParseException("SS 链接解开后没有加密方法和密码")
        val method = userPart.substring(0, colon)
        val password = userPart.substring(colon + 1)
        val hostColon = hostPart.lastIndexOf(':')
        if (hostColon <= 0)
            throw PasteParseException("SS 链接解开后没有端口")
        val host = hostPart.substring(0, hostColon)
        val port = hostPart.substring(hostColon + 1).toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: throw PasteParseException("SS 链接解开后端口不对")
        if (method.isEmpty() || password.isEmpty() || host.isEmpty())
            throw PasteParseException("SS 链接解开后内容不完整")
        var plugin: Pair<String, Map<String, String>>? = null
        if (query.isNotEmpty()) {
            for (pair in query.split("&")) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pctDecode(pair.substring(0, eq)) == "plugin")
                    plugin = parseSsPlugin(pctDecode(pair.substring(eq + 1)))
            }
        }
        return ShadowsocksParts(method, password, host, port, plugin)
    }

    private data class ShadowsocksParts(
        val method: String,
        val password: String,
        val host: String,
        val port: Int,
        val plugin: Pair<String, Map<String, String>>?,
    )

    // "obfs-local;obfs=tls;obfs-host=x" or "v2ray-plugin;tls;host=x;path=/y".
    private fun parseSsPlugin(raw: String): Pair<String, Map<String, String>> {
        val segments = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val pluginName = segments.getOrNull(0) ?: throw PasteParseException("SS 插件写得不对")
        val opts = LinkedHashMap<String, String>()
        val flags = ArrayList<String>()
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq > 0) opts[segment.substring(0, eq).trim()] = segment.substring(eq + 1).trim()
            else flags.add(segment)
        }
        return when (pluginName) {
            "obfs-local", "simple-obfs" -> {
                opts["obfs"]?.let { opts["mode"] = it }
                opts.remove("obfs")
                opts["obfs-host"]?.let { opts["host"] = it }
                opts.remove("obfs-host")
                "obfs-local" to opts.filterKeys { it == "mode" || it == "host" }
            }
            "v2ray-plugin" -> {
                if ("tls" in flags) opts["tls"] = "true"
                "v2ray-plugin" to opts.filterKeys {
                    it == "tls" || it == "host" || it == "path" || it == "mux"
                }
            }
            else -> throw PasteParseException("暂不支持 SS 插件 $pluginName")
        }
    }

    private fun parseVmess(link: String): ParsedProxy {
        val payload = link.substringAfter("vmess://").substringBefore("#")
        if (payload.isEmpty())
            throw PasteParseException("VMess 链接里没有内容")
        val jsonText = try {
            String(decodeBase64Loose(payload), Charsets.UTF_8)
        } catch (e: PasteParseException) {
            throw e
        } catch (_: Exception) {
            throw PasteParseException("VMess 链接里的 base64 部分解不开")
        }
        val json = try {
            org.json.JSONObject(jsonText)
        } catch (_: Exception) {
            throw PasteParseException("VMess 链接里的 JSON 解析失败（只支持标准 base64 JSON 形式）")
        }
        val uuid = json.optString("id").takeIf { it.isNotEmpty() }
            ?: throw PasteParseException("VMess 链接里没有 UUID")
        requireUuid(uuid)
        val host = json.optString("add").takeIf { it.isNotEmpty() }
            ?: throw PasteParseException("VMess 链接里没有服务器地址")
        val port = json.optString("port").toIntOrNull()
            ?: json.optInt("port", -1).takeIf { it in 1..65535 }
            ?: throw PasteParseException("VMess 链接里没有合法端口")
        val net = json.optString("net").lowercase().ifEmpty { "tcp" }
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  uuid: ${yamlStr(uuid)}")
        json.optString("aid").toIntOrNull()?.let { body.add("  alterId: $it") }
        body.add("  cipher: ${yamlStr(json.optString("scy").ifEmpty { "auto" })}")
        body.add("  network: $net")
        if (json.optString("tls") == "tls") body.add("  tls: true")
        val sni = json.optString("sni").ifEmpty { json.optString("host") }
        if (sni.isNotEmpty()) body.add("  servername: ${yamlStr(sni)}")
        if (json.optBoolean("skip-cert-verify", false)) body.add("  skip-cert-verify: true")
        val path = json.optString("path")
        val wsHost = json.optString("host")
        when (net) {
            "ws" -> {
                if (path.isNotEmpty() || wsHost.isNotEmpty()) {
                    body.add("  ws-opts:")
                    if (path.isNotEmpty()) body.add("    path: ${yamlStr(path)}")
                    if (wsHost.isNotEmpty()) {
                        body.add("    headers:")
                        body.add("      Host: ${yamlStr(wsHost)}")
                    }
                }
            }
            "grpc" -> {
                if (path.isNotEmpty()) {
                    body.add("  grpc-opts:")
                    body.add("    grpc-service-name: ${yamlStr(path)}")
                }
            }
            "tcp" -> {
                if (json.optString("type") == "http") {
                    body.add("  http-opts:")
                    body.add("    method: GET")
                    if (path.isNotEmpty()) {
                        body.add("    path:")
                        body.add("      - ${yamlStr(path)}")
                    }
                    if (wsHost.isNotEmpty()) {
                        body.add("    headers:")
                        body.add("      Host:")
                        body.add("        - ${yamlStr(wsHost)}")
                    }
                }
            }
        }
        body.add("  udp: true")
        val name = json.optString("ps").ifEmpty { "$host:$port" }
        return finish(name, "vmess", body)
    }

    private fun parseHysteria2(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        val (username, password) = when (parts.size) {
            1 -> null to (parts[0].takeIf { it.isNotEmpty() }
                ?: throw PasteParseException("Hysteria2 链接里没有密码"))
            else -> {
                val pass = parts.drop(1).joinToString(":")
                if (pass.isEmpty())
                    throw PasteParseException("Hysteria2 链接里没有密码")
                parts[0].ifEmpty { null } to pass
            }
        }
        val host = requireHost(uri)
        val port = requirePort(uri)
        val params = queryParams(uri)
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  password: ${yamlStr(password)}")
        username?.let { body.add("  username: ${yamlStr(it)}") }
        params["obfs"]?.takeIf { it.isNotEmpty() }?.let { body.add("  obfs: ${yamlStr(it)}") }
        params["obfs-password"]?.takeIf { it.isNotEmpty() }?.let {
            body.add("  obfs-password: ${yamlStr(it)}")
        }
        sniOf(params)?.let { body.add("  sni: ${yamlStr(it)}") }
        body.addAll(alpnLines(params["alpn"]))
        boolParam(params, "insecure", "allowInsecure", "skip-cert-verify")?.let {
            body.add("  skip-cert-verify: $it")
        }
        body.add("  udp: true")
        return finish(requireName(uri, "$host:$port"), "hysteria2", body)
    }

    private fun parseTuic(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        if (parts.size < 2 || parts[0].isEmpty() || parts.drop(1).joinToString(":").isEmpty())
            throw PasteParseException("TUIC 链接里没有 UUID 和密码（应为 uuid:密码@服务器）")
        requireUuid(parts[0])
        val host = requireHost(uri)
        val port = requirePort(uri)
        val params = queryParams(uri)
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        body.add("  uuid: ${yamlStr(parts[0])}")
        body.add("  password: ${yamlStr(parts.drop(1).joinToString(":"))}")
        params["congestion_control"]?.takeIf { it.isNotEmpty() }?.let {
            body.add("  congestion-controller: ${yamlStr(it)}")
        }
        params["udp_relay_mode"]?.takeIf { it.isNotEmpty() }?.let {
            body.add("  udp-relay-mode: ${yamlStr(it)}")
        }
        body.addAll(alpnLines(params["alpn"]))
        sniOf(params)?.let { body.add("  servername: ${yamlStr(it)}") }
        boolParam(params, "allow_insecure", "insecure", "skip-cert-verify")?.let {
            body.add("  skip-cert-verify: $it")
        }
        body.add("  udp: true")
        return finish(requireName(uri, "$host:$port"), "tuic", body)
    }

    private fun parseSocks(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        val host = requireHost(uri)
        val port = requirePort(uri)
        val params = queryParams(uri)
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        if (parts.size >= 2) {
            if (parts[0].isNotEmpty()) body.add("  username: ${yamlStr(parts[0])}")
            val pass = parts.drop(1).joinToString(":")
            if (pass.isNotEmpty()) body.add("  password: ${yamlStr(pass)}")
        }
        boolParam(params, "tls")?.let { body.add("  tls: $it") }
        boolParam(params, "skip-cert-verify")?.let { body.add("  skip-cert-verify: $it") }
        body.add("  udp: true")
        return finish(requireName(uri, "$host:$port"), "socks5", body)
    }

    private fun parseHttp(uri: URI): ParsedProxy {
        val parts = userInfoParts(uri)
        val host = requireHost(uri)
        val port = when {
            uri.port in 1..65535 -> uri.port
            uri.scheme?.lowercase() == "https" -> 443
            else -> 80
        }
        val body = ArrayList<String>()
        body.add("  server: ${yamlStr(host)}")
        body.add("  port: $port")
        if (parts.size >= 2) {
            if (parts[0].isNotEmpty()) body.add("  username: ${yamlStr(parts[0])}")
            val pass = parts.drop(1).joinToString(":")
            if (pass.isNotEmpty()) body.add("  password: ${yamlStr(pass)}")
        }
        body.add("  tls: ${uri.scheme?.lowercase() == "https"}")
        return finish(requireName(uri, "$host:$port"), "http", body)
    }
}
