package com.acite.swdw.model

object StatusJsonParser {
    fun parse(json: String): SystemStatus {
        try {
            val os = parseOs(json)
            val audio = parseAudio(json)
            val activeListeners = findInt(json, "active_listeners") ?: 0
            val serverTime = findLong(json, "server_time") ?: 0L
            return SystemStatus(
                os = os,
                audio = audio,
                activeListeners = activeListeners,
                serverTime = serverTime
            )
        } catch (_: Exception) {
            return SystemStatus()
        }
    }

    private fun parseOs(json: String): OsInfo {
        val osSection = extractSubObject(json, "\"os\"") ?: json
        return OsInfo(
            osName = findString(osSection, "os_name") ?: "Linux",
            kernel = findString(osSection, "kernel") ?: "-",
            hostname = findString(osSection, "hostname") ?: "localhost",
            cpuModel = findString(osSection, "cpu_model") ?: "CPU",
            cpuCores = findInt(osSection, "cpu_cores") ?: 1,
            memTotalMb = findLong(osSection, "mem_total_mb") ?: 0L,
            memUsedMb = findLong(osSection, "mem_used_mb") ?: 0L,
            uptimeSecs = findLong(osSection, "uptime_secs") ?: 0L
        )
    }

    private fun parseAudio(json: String): AudioStackInfo {
        val audioSection = extractSubObject(json, "\"audio\"") ?: json
        val vmicSection = extractSubObject(audioSection, "\"virtual_mic\"")

        val vmic = VirtualMicInfo(
            name = vmicSection?.let { findString(it, "name") } ?: "SWD-Virtual-Mic",
            description = vmicSection?.let { findString(it, "description") } ?: "SWD 移动端麦克风",
            isStreaming = vmicSection?.let { findBool(it, "is_streaming") } ?: false,
            sampleRate = vmicSection?.let { findInt(it, "sample_rate") } ?: 48000,
            channels = vmicSection?.let { findInt(it, "channels") } ?: 2,
            peak = vmicSection?.let { findFloat(it, "peak") } ?: 0f,
            totalReceivedFrames = vmicSection?.let { findLong(it, "total_received_frames") } ?: 0L
        )

        val devices = parseDevices(audioSection)

        return AudioStackInfo(
            pipewireVersion = findString(audioSection, "pipewire_version") ?: "1.0",
            cpalHost = findString(audioSection, "cpal_host") ?: "PipeWire",
            defaultOutputDevice = findString(audioSection, "default_output_device") ?: "Default Sink",
            defaultInputDevice = findString(audioSection, "default_input_device") ?: "Default Source",
            captureRate = findInt(audioSection, "capture_rate") ?: 96000,
            captureChannels = findInt(audioSection, "capture_channels") ?: 2,
            captureFormat = findString(audioSection, "capture_format") ?: "16-bit PCM",
            totalCapturedFrames = findLong(audioSection, "total_captured_frames") ?: 0L,
            peakLeft = findFloat(audioSection, "peak_left") ?: 0f,
            peakRight = findFloat(audioSection, "peak_right") ?: 0f,
            devices = devices,
            virtualMic = vmic
        )
    }

    private fun parseDevices(json: String): List<AudioDeviceInfo> {
        val list = mutableListOf<AudioDeviceInfo>()
        val startKey = json.indexOf("\"devices\"")
        if (startKey == -1) return list
        val startBracket = json.indexOf('[', startKey)
        if (startBracket == -1) return list

        var depth = 1
        var i = startBracket + 1
        var itemStart = -1

        while (i < json.length && depth > 0) {
            val c = json[i]
            if (c == '{') {
                if (depth == 1) itemStart = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 1 && itemStart != -1) {
                    val itemJson = json.substring(itemStart, i + 1)
                    val name = findString(itemJson, "name") ?: "Audio Device"
                    val isDef = findBool(itemJson, "is_default") ?: false
                    val maxCh = findInt(itemJson, "max_channels") ?: 2
                    list.add(AudioDeviceInfo(name = name, isDefault = isDef, maxChannels = maxCh))
                    itemStart = -1
                }
            } else if (c == ']' && depth == 1) {
                break
            }
            i++
        }
        return list
    }

    private fun extractSubObject(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val brace = json.indexOf('{', idx)
        if (brace == -1) return null
        var depth = 1
        var i = brace + 1
        while (i < json.length && depth > 0) {
            val c = json[i]
            if (c == '{') depth++
            else if (c == '}') depth--
            i++
        }
        return if (depth == 0) json.substring(brace, i) else null
    }

    private fun findString(json: String, key: String): String? {
        val pattern = "\"$key\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val colon = json.indexOf(':', idx + pattern.length)
        if (colon == -1) return null
        val firstQuote = json.indexOf('"', colon)
        if (firstQuote == -1) return null
        val secondQuote = json.indexOf('"', firstQuote + 1)
        if (secondQuote == -1) return null
        return json.substring(firstQuote + 1, secondQuote)
    }

    private fun findInt(json: String, key: String): Int? {
        return findNumber(json, key)?.toIntOrNull()
    }

    private fun findLong(json: String, key: String): Long? {
        return findNumber(json, key)?.toLongOrNull()
    }

    private fun findFloat(json: String, key: String): Float? {
        return findNumber(json, key)?.toFloatOrNull()
    }

    private fun findBool(json: String, key: String): Boolean? {
        val pattern = "\"$key\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val colon = json.indexOf(':', idx + pattern.length)
        if (colon == -1) return null
        val tail = json.substring(colon + 1).trimStart()
        if (tail.startsWith("true")) return true
        if (tail.startsWith("false")) return false
        return null
    }

    private fun findNumber(json: String, key: String): String? {
        val pattern = "\"$key\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val colon = json.indexOf(':', idx + pattern.length)
        if (colon == -1) return null
        var i = colon + 1
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r')) {
            i++
        }
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '.' || json[i] == 'e' || json[i] == 'E')) {
            i++
        }
        return if (start < i) json.substring(start, i) else null
    }
}
