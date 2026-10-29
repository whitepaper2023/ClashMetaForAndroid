package com.github.kr328.clash.design.model

import java.util.*

data class LogFile(val fileName: String, val date: Date) {
    companion object {
        // New files are walash-<ts>.log; old clash-<ts>.log files must
        // still list, so the regex accepts both prefixes. groupValues[1]
        // is the prefix, groupValues[2] is the timestamp.
        private val REGEX_FILE = Regex("(clash|walash)-(\\d+).log")
        private const val FORMAT_FILE_NAME = "walash-%d.log"

        fun parseFromFileName(fileName: String): LogFile? {
            return REGEX_FILE.matchEntire(fileName)?.run {
                LogFile(fileName, Date(groupValues[2].toLong()))
            }
        }

        fun generate(): LogFile {
            val current = Date()
            val fileName = FORMAT_FILE_NAME.format(current.time)

            return LogFile(fileName, current)
        }
    }
}