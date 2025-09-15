/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.trace

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.recomputeSpinCycleStartCallStack
import org.jetbrains.lincheck.util.*
import kotlin.math.max

internal typealias SingleThreadedTable<T> = List<SingleThreadedSection<T>>
internal typealias SingleThreadedSection<T> = List<T>

internal typealias MultiThreadedTable<T> = List<MultiThreadedSection<T>>
internal typealias MultiThreadedSection<T> = List<Column<T>>
internal typealias Column<T> = List<T>

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun Appendable.appendTrace(
    trace: Trace,
    failure: LincheckFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    TraceReporter(trace, failure, exceptionStackTraces).appendTrace(this)
}

/**
 * Appends [Trace] to [Appendable]
 */
internal class TraceReporter(
    trace: Trace,
    private val failure: LincheckFailure,
    private val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    private val trace = trace.deepCopy()

    val tree: SingleThreadedTable<TraceNode>

    init {
        // Prepares trace by: 
        // - removing the validation section (in case of no validation failure);
        // - moving starting switch points outside of method calls (to make the trace more readable);
        // - moving spin cycle start trace points to the place where the recursive method call trace points are located;
        // - numbering actor exceptions (to make the trace more readable);
        // - removing the GPMC lambda section (in case of GPMC mode).
        val fixedTrace = this.trace
            .removeValidationIfNeeded(failure)
            .moveStartingSwitchPointsOutOfMethodCalls()
            .moveSpinCycleStartTracePoints()
            .numberExceptionResults()

         tree = traceToCollapsedTree(fixedTrace, failure.analysisProfile, failure.scenario)
    }

    fun appendTrace(app: Appendable) = with(app) {
        // Turn tree into a chronological sequence of calls and events, for verbose and simple trace.
        val flattenedShort: SingleThreadedTable<TraceNode> = tree.flattenNodes(ShortTraceFlattenPolicy()).reorder()
        val flattenedVerbose: SingleThreadedTable<TraceNode> = tree.flattenNodes(VerboseTraceFlattenPolicy()).reorder()
        appendTraceTable(TRACE_TITLE, trace, failure, flattenedShort, showStackTraceElements = false)
        appendLine()

        if (!isGeneralPurposeModelCheckingScenario(failure.scenario)) {
            appendExceptionsStackTracesBlock(exceptionStackTraces)
        }

        // if empty trace show only the first
        if (flattenedVerbose.sumOf { it.size } != 1) {
            appendTraceTable(DETAILED_TRACE_TITLE, trace, failure, flattenedVerbose)
        }
    }
}

/**
 * Appends trace table to [Appendable]
 */
internal fun Appendable.appendTraceTable(title: String, trace: Trace, failure: LincheckFailure?, tree: SingleThreadedTable<TraceNode>, showStackTraceElements: Boolean = true) {
    appendLine(title)
    val traceRepresentationSplitted = splitInColumns(trace.threadNames.size, tree)
    val stringTable = traceNodeTableToString(traceRepresentationSplitted, showStackTraceElements)
    val layout = ExecutionLayout(
        nThreads = trace.threadNames.size,
        interleavingSections = stringTable,
        threadNames = trace.threadNames,
    )
    with(layout) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        stringTable.forEach { section ->
            appendColumns(section)
            appendSeparatorLine()
        }
    }
    if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
}

/**
 * Splits trace into thread columns. Order is maintained.
 * Example of Events `E1 - E3` on threads t1 -t3`
 * ```
 * | E1(t1) |          | E1(t1) |        |        |
 * | E2(t3) |    -->   |        |        | E2(t3) |
 * | E3(t2) |          |        | E3(t2) |        |
 * ```
 */
private fun splitInColumns(nThreads: Int, flattened: SingleThreadedTable<TraceNode>): MultiThreadedTable<TraceNode?> =
    flattened.map { section ->
        val multiThreadedSection = List<MutableList<TraceNode?>>(nThreads) { mutableListOf() }
        section.forEach { node ->
            repeat(nThreads) { i ->
                if (i == node.iThread) multiThreadedSection[i].add(node)
                else multiThreadedSection[i].add(null)
            }
        }
        multiThreadedSection
    }

private const val NO_SPIN_CYCLE = -1
private const val START_SPIN_CYCLE = -2
// TODO bugfix for spin cycle start points not having the lowest indent up to switch event
/**
 * Prints all cells of the [MultiThreadedTable] to string representation.
 * Prepends spin cycle visualization where needed.
 */
private fun traceNodeTableToString(table: MultiThreadedTable<TraceNode?>, showStackTraceElements: Boolean = true): MultiThreadedTable<String> =
    table.map tableMap@{ section -> section.map sectionMap@{ column ->
        var spinCycleDepth = NO_SPIN_CYCLE
        var additionalSpace = column
            .firstOrNull { it is EventNode && it.tracePoint is SpinCycleStartTracePoint }
            ?.let { max(0, 2 - it.callDepth) } ?: 0

        // TraceNode to string
        column.map { node ->
            if (node == null) return@map ""
            val virtualCallDepth = additionalSpace + node.callDepth
            val virtualSpinCycleDepth = additionalSpace + spinCycleDepth

            // If begin of spin cycle
            if (spinCycleDepth == START_SPIN_CYCLE) {
                spinCycleDepth = node.callDepth
                val prefix = "  ".repeat((virtualCallDepth - 2).coerceAtLeast(0)) + "┌╶> "
                return@map prefix + node.toStringImpl(withLocation = showStackTraceElements)
            }

            // If spinc cycle detected change state. Next iteration will start visualization
            if (node is EventNode && node.tracePoint is SpinCycleStartTracePoint) {
                spinCycleDepth = START_SPIN_CYCLE
            }

            // If end of spin cycle
            if (spinCycleDepth >= 0 && node is EventNode &&
                (node.tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint || node.tracePoint is SwitchEventTracePoint)
            ) {
                spinCycleDepth = NO_SPIN_CYCLE
                val prefix = "  ".repeat((virtualSpinCycleDepth - 2).coerceAtLeast(0)) + "└╶╶╶" + "╶╶".repeat(max(virtualCallDepth - virtualSpinCycleDepth, 0))
                return@map prefix.dropLast(1) + " " + node.toStringImpl(withLocation = showStackTraceElements)
            }

            // If during spin cycle
            if (spinCycleDepth >= 0) {
                val prefix = "  ".repeat((virtualSpinCycleDepth - 2).coerceAtLeast(0)) + "|   " + "  ".repeat(max(virtualCallDepth - virtualSpinCycleDepth, 0))
                return@map prefix + node.toStringImpl(withLocation = showStackTraceElements)
            }

            // Default
            return@map "  ".repeat(virtualCallDepth) + node.toStringImpl(withLocation = showStackTraceElements)
        }
    }
}

internal fun traceToCollapsedTree(trace: Trace, analysisProfile: AnalysisProfile, scenario: ExecutionScenario?): SingleThreadedTable<TraceNode> {
    // Turn trace into a tree which is List of sections, where a section is a list of root nodes (actors).
    val traceTree = traceToTree(trace)

    // Optimizes trace by combining trace points for synthetic field accesses etc.
    val compressedTraceTree = traceTree
        .compressTrace()
        .collapseLibraries(analysisProfile)

    return if (scenario != null && isGeneralPurposeModelCheckingScenario(scenario)) removeGPMCLambda(compressedTraceTree) else compressedTraceTree
}

internal const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"
internal const val TRACE_TITLE = "The following interleaving leads to the error:"
internal const val DETAILED_TRACE_TITLE = "Detailed trace:"
