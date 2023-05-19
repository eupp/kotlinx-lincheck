/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.LoggingLevel.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import java.io.*

class Reporter constructor(private val logLevel: LoggingLevel) {
    private val out: PrintStream = System.out
    private val outErr: PrintStream = System.err

    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = log(INFO) {
        appendLine("\n= Iteration $iteration / $maxIterations =")
        appendExecutionScenario(scenario)
    }

    fun logFailedIteration(failure: LincheckFailure) = log(INFO) {
        appendFailure(failure)
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) = log(INFO) {
        appendLine("\nInvalid interleaving found, trying to minimize the scenario below:")
        appendExecutionScenario(scenario)
    }


    private inline fun log(logLevel: LoggingLevel, crossinline msg: StringBuilder.() -> Unit): Unit = synchronized(this) {
        if (this.logLevel > logLevel) return
        val sb = StringBuilder()
        msg(sb)
        val output = if (logLevel == WARN) outErr else out
        output.println(sb)
    }
}

@JvmField val DEFAULT_LOG_LEVEL = WARN
enum class LoggingLevel {
    INFO, WARN
}

internal fun<T> columnsToString(
    data: List<List<T>>,
    columnWidths: List<Int>? = null,
    transform: ((T) -> String)? = null
): String {
    val nCols = data.size
    val nRows = data.maxOfOrNull { it.size } ?: 0
    val strings = data.map { col -> col.map {
        transform?.invoke(it) ?: it.toString() }
    }
    val colsWidth = columnWidths ?: strings.map { col ->
        col.maxOfOrNull { it.length } ?: 0
    }
    val table = (0 until nRows).map { iRow -> (0 until nCols).map { iCol ->
        strings[iCol].getOrNull(iRow).orEmpty().padEnd(colsWidth[iCol])
    }}
    return table.joinToString(separator = "\n") {
        it.joinToString(separator = " | ", prefix = "| ", postfix = " |")
    }
}

internal fun <T> StringBuilder.appendColumns(
    data: List<List<T>>,
    columnWidths: List<Int>? = null,
    transform: ((T) -> String)? = null
) {
    appendLine(columnsToString(data, columnWidths, transform))
}

internal class TableLayout(
    val columnNames: List<String>,
    columnWidths: List<Int>,
) {
    val nColumns
        get() = columnNames.size

    init {
        require(columnWidths.size == nColumns)
    }

    val columnWidths = columnWidths.mapIndexed { i, col ->
        col.coerceAtLeast(columnNames[i].length)
    }

    private val lineSize = columnWidths.sum() + " | ".length * (nColumns - 1)
    private val separator = "| " + "-".repeat(lineSize) + " |"

    fun StringBuilder.appendSeparatorLine() = apply {
        appendLine(separator)
    }

    fun StringBuilder.appendWrappedLine(line: String) = apply {
        appendLine("| " + line.padEnd(lineSize) + " |")
    }

    fun<T> StringBuilder.appendColumns(data: List<List<T>>, transform: ((T) -> String)? = null) = apply {
        require(data.size == nColumns)
        appendColumns(data, columnWidths, transform)
    }

    fun <T> StringBuilder.appendColumn(iCol: Int, data: List<T>, transform: ((T) -> String)? = null) = apply {
        val cols = (0 until nColumns).map { i -> if (i == iCol) data else listOf() }
        appendColumns(cols)
    }

    fun<T> StringBuilder.appendRow(data: List<T>, transform: ((T) -> String)? = null) = apply {
        require(data.size == nColumns)
        val strings = data
            .map { transform?.invoke(it) ?: it.toString() }
            .mapIndexed { i, str -> str.padEnd(columnWidths[i]) }
        appendLine(strings.joinToString(separator = " | ", prefix = "| ", postfix = " |"))
    }

    fun StringBuilder.appendHeader() = apply {
        appendRow(columnNames)
    }

}

internal fun ExecutionLayout(
    initPart: List<String>,
    parallelPart: List<List<String>>,
    postPart: List<String>
): TableLayout {
    val size = parallelPart.size
    val threadHeaders = (0 until size).map { "Thread ${it + 1}" }
    val columnsWidth = parallelPart.mapIndexed { i, actors ->
        val col = actors + if (i == 0) (initPart + postPart) else listOf()
        col.maxOfOrNull { it.length } ?: 0
    }
    return TableLayout(threadHeaders, columnsWidth)
}

internal fun StringBuilder.appendExecutionScenario(scenario: ExecutionScenario): StringBuilder {
    val initPart = scenario.initExecution.map(Actor::toString)
    val postPart = scenario.postExecution.map(Actor::toString)
    val parallelPart = scenario.parallelExecution.map { it.map(Actor::toString) }
    val layout = ExecutionLayout(initPart, parallelPart, postPart)
    with(layout) {
        appendHeader()
        appendSeparatorLine()
        if (initPart.isNotEmpty()) {
            appendColumn(0, initPart)
            appendSeparatorLine()
        }
        appendColumns(parallelPart)
        appendSeparatorLine()
        if (postPart.isNotEmpty()) {
            appendColumn(0, postPart)
            appendSeparatorLine()
        }
    }
    return this
}

private data class ActorWithResult(
    val actor: Actor,
    val result: Result,
    val clock: HBClock? = null,
    val exceptionInfo: ExceptionNumberAndStacktrace? = null
) {
    init {
        require(exceptionInfo == null || result is ExceptionResult)
    }

    constructor(actor: Actor, result: Result,
                exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
                clock: HBClock? = null,
    ) : this(actor, result, clock, result.exceptionInfo(exceptionStackTraces))

    override fun toString(): String =
        "${actor}: $result" +
                (exceptionInfo?.let { " #${it.number}" } ?: "") +
                (clock?.takeIf { !it.empty }?.let { " $it" } ?: "")

}

internal fun Result.exceptionInfo(exceptionMap: Map<Throwable, ExceptionNumberAndStacktrace>): ExceptionNumberAndStacktrace? =
    (this as? ExceptionResult)?.let { exceptionMap[it.throwable] }

private fun<T, U> requireEqualSize(x: List<T>, y: List<U>, lazyMessage: () -> String) {
    require(x.size == y.size) { "${lazyMessage()} (${x.size} != ${y.size})" }
}

internal fun StringBuilder.appendExecutionScenarioWithResults(
    scenario: ExecutionScenario,
    executionResult: ExecutionResult,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
): StringBuilder {
    requireEqualSize(scenario.parallelExecution, executionResult.parallelResults) {
        "Different numbers of threads and matching results found"
    }
    requireEqualSize(scenario.initExecution, executionResult.initResults) {
        "Different numbers of actors and matching results found"
    }
    requireEqualSize(scenario.postExecution, executionResult.postResults) {
        "Different numbers of actors and matching results found"
    }
    for (i in scenario.parallelExecution.indices) {
        requireEqualSize(scenario.parallelExecution[i], executionResult.parallelResults[i]) {
            "Different numbers of actors and matching results found"
        }
    }
    val initPart = scenario.initExecution.zip(executionResult.initResults) {
        actor, result -> ActorWithResult(actor, result, exceptionStackTraces).toString()
    }
    val postPart = scenario.postExecution.zip(executionResult.postResults) {
        actor, result -> ActorWithResult(actor, result, exceptionStackTraces).toString()
    }
    var hasClocks = false
    val parallelPart = scenario.parallelExecution.mapIndexed { i, actors ->
        actors.zip(executionResult.parallelResultsWithClock[i]) { actor, resultWithClock ->
            if (!resultWithClock.clockOnStart.empty)
                hasClocks = true
            ActorWithResult(actor, resultWithClock.result, exceptionStackTraces, clock = resultWithClock.clockOnStart).toString()
        }
    }
    val layout = ExecutionLayout(initPart, parallelPart, postPart)
    with(layout) {
        appendHeader()
        appendSeparatorLine()
        if (initPart.isNotEmpty()) {
            appendColumn(0, initPart)
            appendSeparatorLine()
        }
        if (executionResult.afterInitStateRepresentation != null) {
            appendWrappedLine("STATE: ${executionResult.afterInitStateRepresentation}")
            appendSeparatorLine()
        }
        appendColumns(parallelPart)
        appendSeparatorLine()
        if (executionResult.afterParallelStateRepresentation != null) {
            appendWrappedLine("STATE: ${executionResult.afterParallelStateRepresentation}")
            appendSeparatorLine()
        }
        if (postPart.isNotEmpty()) {
            appendColumn(0, postPart)
            appendSeparatorLine()
        }
        if (executionResult.afterPostStateRepresentation != null && postPart.isNotEmpty()) {
            appendWrappedLine("STATE: ${executionResult.afterPostStateRepresentation}")
            appendSeparatorLine()
        }
    }
    val hints = mutableListOf<String>()
    if (hasClocks) {
        hints.add(
            """
                Values in "[..]" brackets indicate the number of completed operations
                in each of the parallel threads seen at the beginning of the current operation
            """.trimIndent()
        )
    }
    if (exceptionStackTraces.isNotEmpty()) {
        hints.add(
            """
                The number next to an exception name helps you find its stack trace provided after the interleaving section
            """.trimIndent()
        )
    }
    appendHints(hints)
    return this
}

internal fun StringBuilder.appendFailure(failure: LincheckFailure): StringBuilder {
    val results: ExecutionResult? = (failure as? IncorrectResultsFailure)?.results
    // If a result is present - collect exceptions stack traces to print them
    val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace> = results?.let {
        when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
            // If some exception was thrown from the Lincheck itself, we ask for bug reporting
            is InternalLincheckBugResult -> {
                appendInternalLincheckBugFailure(exceptionsProcessingResult.exception)
                return this
            }

            is ExceptionStackTracesResult -> exceptionsProcessingResult.exceptionStackTraces
        }
    } ?: emptyMap()

    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure, exceptionStackTraces)
        is DeadlockWithDumpFailure -> appendDeadlockWithDumpFailure(failure)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
        is ValidationFailure -> appendValidationFailure(failure)
        is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure)
    }
    if (failure.trace != null) {
        appendLine()
        appendLine("= The following interleaving leads to the error =")
        appendTrace(failure.scenario, results, failure.trace, exceptionStackTraces)
        if (failure is DeadlockWithDumpFailure) {
            appendLine()
            append("All threads are in deadlock")
        }
    } else {
        appendExceptionsStackTracesBlock(exceptionStackTraces)
    }
    return this
}

internal fun StringBuilder.appendExceptionsStackTracesBlock(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) {
    if (exceptionStackTraces.isNotEmpty()) {
        appendLine(EXCEPTIONS_TRACES_TITLE)
        appendExceptionsStackTraces(exceptionStackTraces)
        appendLine()
    }
}

internal fun StringBuilder.appendExceptionsStackTraces(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): StringBuilder {
    exceptionStackTraces.entries.sortedBy { (_, description) -> description.number }.forEach { (exception, description) ->
        append("#${description.number}: ")

        appendLine(exception::class.java.canonicalName)
        description.stackTrace.forEach { appendLine("\tat $it") }

        if (description.number < exceptionStackTraces.size) appendLine()
    }

    return this
}

fun StringBuilder.appendInternalLincheckBugFailure(exception: Throwable) {
    appendLine(
        """
        Wow! You've caught a bug in Lincheck.
        We kindly ask to provide an issue here: https://github.com/JetBrains/lincheck/issues,
        attaching a stack trace printed below and the code that causes the error.
        
        Exception stacktrace:
    """.trimIndent()
    )

    val exceptionRepresentation = StringWriter().use {
        exception.printStackTrace(PrintWriter(it))
        it.toString()
    }
    append(exceptionRepresentation)
}

internal data class ExceptionNumberAndStacktrace(
    /**
     * Serves to match exception in a scenario with its stackTrace
     */
    val number: Int,
    /**
     * Prepared for output stackTrace of this exception
     */
    val stackTrace: List<StackTraceElement>
)

internal fun resultRepresentation(result: Result, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): String {
    return when (result) {
        is ExceptionResult -> {
            val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
            "$result$exceptionNumberRepresentation"
        }
        else -> result.toString()
    }
}

internal fun actorNodeResultRepresentation(result: Result, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): String? {
    return when (result) {
        is ExceptionResult -> {
            val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
            "$result$exceptionNumberRepresentation"
        }
        is VoidResult -> null // don't print
        else -> result.toString()
    }
}

/**
 * Result of collecting exceptions into a map from throwable to its number and stacktrace
 * to use this information to numerate them and print their stacktrace with number.
 * @see collectExceptionStackTraces
 */
private sealed interface ExceptionsProcessingResult

/**
 * Corresponds to the case when we tried to collect exceptions map but found one,
 * that was thrown from Lincheck internally.
 * In that case, we just want to print that exception and don't care about other exceptions.
 */
private data class InternalLincheckBugResult(val exception: Throwable) :
    ExceptionsProcessingResult

/**
 * Result of successful collection exceptions to map when no one of them was thrown from Lincheck.
 */
private data class ExceptionStackTracesResult(val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) :
    ExceptionsProcessingResult


/**
 * Collects stackTraces of exceptions thrown during execution
 *
 * This method traverses over all execution results and collects exceptions.
 * For each exception, it also filters stacktrace to cut off all Lincheck-related [StackTraceElement]s.
 * If filtered stackTrace of some exception is empty, then this exception was thrown from Lincheck itself,
 * in that case we return that exception as an internal bug to report it.
 *
 * @return exceptions stack traces map inside [ExceptionStackTracesResult] or [InternalLincheckBugResult]
 * if some exception occurred due a bug in Lincheck itself
 */
private fun collectExceptionStackTraces(executionResult: ExecutionResult): ExceptionsProcessingResult {
    val exceptionStackTraces = mutableMapOf<Throwable, ExceptionNumberAndStacktrace>()

    (executionResult.initResults.asSequence()
            + executionResult.parallelResults.asSequence().flatten()
            + executionResult.postResults.asSequence())
        .filterIsInstance<ExceptionResult>()
        .forEachIndexed { index, exceptionResult ->
            val exception = exceptionResult.throwable

            val filteredStacktrace = exception.stackTrace.takeWhile { LINCHECK_PACKAGE_NAME !in it.className }
            if (filteredStacktrace.isEmpty()) { // Exception in Lincheck itself
                return InternalLincheckBugResult(exception)
            }

            exceptionStackTraces[exception] = ExceptionNumberAndStacktrace(index + 1, filteredStacktrace)
        }

    return ExceptionStackTracesResult(exceptionStackTraces)
}

private fun StringBuilder.appendUnexpectedExceptionFailure(failure: UnexpectedExceptionFailure): StringBuilder {
    appendLine("= The execution failed with an unexpected exception =")
    appendExecutionScenario(failure.scenario)
    appendLine()
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendDeadlockWithDumpFailure(failure: DeadlockWithDumpFailure): StringBuilder {
    appendLine("= The execution has hung, see the thread dump =")
    appendExecutionScenario(failure.scenario)
    appendLine()
    for ((t, stackTrace) in failure.threadDump) {
        val threadNumber = if (t is FixedActiveThreadsExecutor.TestThread) t.iThread.toString() else "?"
        appendLine("Thread-$threadNumber:")
        stackTraceRepresentation(stackTrace).forEach { appendLine("\t$it") }
    }
    return this
}

private fun StringBuilder.appendIncorrectResultsFailure(
    failure: IncorrectResultsFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
): StringBuilder {
    appendLine("= Invalid execution results =")
    appendExecutionScenarioWithResults(failure.scenario, failure.results, exceptionStackTraces)
    return this
}

private fun StringBuilder.appendHints(hints: List<String>) {
    if (hints.isNotEmpty()) {
        appendLine(hints.joinToString(prefix = "\n---\n", separator = "\n---\n", postfix = "\n---"))
    }
}

private fun StringBuilder.appendValidationFailure(failure: ValidationFailure): StringBuilder {
    appendLine("= Validation function ${failure.functionName} has failed =")
    appendExecutionScenario(failure.scenario)
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendObstructionFreedomViolationFailure(failure: ObstructionFreedomViolationFailure): StringBuilder {
    appendLine("= ${failure.reason} =")
    appendExecutionScenario(failure.scenario)
    return this
}

private fun StringBuilder.appendException(t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    appendLine(sw.toString())
}

private const val EXCEPTIONS_TRACES_TITLE = "Exception stack traces:"
