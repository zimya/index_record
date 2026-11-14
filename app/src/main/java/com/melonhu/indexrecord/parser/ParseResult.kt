package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.model.UniversalRecord

/** Empty success is valid; only explicit success/ignore authorizes raw deletion. */
sealed class ParseResult {
    abstract val records: List<UniversalRecord>
    open val diagnostics: List<ParseDiagnostic> = emptyList()
    val consumeRaw: Boolean get() = this is Success || this is Ignored

    data class Success(override val records: List<UniversalRecord>) : ParseResult()
    data class Ignored(val reason: String) : ParseResult() {
        override val records: List<UniversalRecord> = emptyList()
    }
    data class Failure(override val diagnostics: List<ParseDiagnostic>) : ParseResult() {
        init { require(diagnostics.isNotEmpty()) }
        override val records: List<UniversalRecord> = emptyList()
    }
    data class Partial(
        override val records: List<UniversalRecord>,
        override val diagnostics: List<ParseDiagnostic>
    ) : ParseResult() {
        init { require(records.isNotEmpty() && diagnostics.isNotEmpty()) }
    }

    companion object {
        fun failure(stage: String, error: Exception): Failure =
            Failure(listOf(ParseDiagnostic(stage, error.message ?: error.javaClass.simpleName, error)))

        fun decoded(records: List<UniversalRecord>, diagnostics: List<ParseDiagnostic>): ParseResult = when {
            diagnostics.isEmpty() -> Success(records)
            records.isEmpty() -> Failure(diagnostics.toList())
            else -> Partial(records, diagnostics.toList())
        }

        fun attempt(stage: String, parse: () -> ParseResult): ParseResult = try {
            parse()
        } catch (e: Exception) {
            failure(stage, e)
        }
    }
}

/** Contains location/error context, never a copy of the potentially sensitive payload. */
data class ParseDiagnostic(val stage: String, val message: String, val cause: Exception? = null)
