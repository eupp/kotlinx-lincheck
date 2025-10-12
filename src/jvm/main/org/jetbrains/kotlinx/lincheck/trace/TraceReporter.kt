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
import org.jetbrains.lincheck.util.*
import kotlin.math.max

internal typealias SingleThreadedTable<T> = List<SingleThreadedSection<T>>
internal typealias SingleThreadedSection<T> = List<T>

internal typealias MultiThreadedTable<T> = List<MultiThreadedSection<T>>
internal typealias MultiThreadedSection<T> = List<Column<T>>
internal typealias Column<T> = List<T>

/**
 * Appends [Trace] to [Appendable]
 */
internal class TraceReporter(
    private val trace: Trace,
    analysisProfile: AnalysisProfile,
) {
    val tree: SingleThreadedTable<TraceNode> =
        traceToCollapsedTree(this.trace, analysisProfile)

    fun appendTrace(appendable: Appendable, verbose: Boolean) = with(appendable) {
        val flattenPolicy = if (verbose) VerboseTraceFlattenPolicy() else ShortTraceFlattenPolicy()
        val flattenedTree = tree.flattenNodes(flattenPolicy).reorder()
        appendTraceTable(trace.threadNames, flattenedTree, verbose)
    }
}

/**
 * Appends trace table to [Appendable]
 */
internal fun Appendable.appendTraceTable(threadNames: List<String>, tree: SingleThreadedTable<TraceNode>, verbose: Boolean) {
    val traceRepresentationSplitted = splitInColumns(threadNames.size, tree)
    val stringTable = traceNodeTableToString(traceRepresentationSplitted, verbose)
    val layout = ExecutionLayout(
        nThreads = threadNames.size,
        interleavingSections = stringTable,
        threadNames = threadNames,
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

/**
 * Prints all cells of the [MultiThreadedTable] to string representation.
 * Prepends spin cycle visualization where needed.
 */
private fun traceNodeTableToString(table: MultiThreadedTable<TraceNode?>, verbose: Boolean = true): MultiThreadedTable<String> =
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
            // TODO bugfix for spin cycle start points not having the lowest indent up to switch event
            if (spinCycleDepth == START_SPIN_CYCLE) {
                spinCycleDepth = node.callDepth
                val prefix = "  ".repeat((virtualCallDepth - 2).coerceAtLeast(0)) + "┌╶> "
                return@map prefix + node.toStringImpl(withLocation = verbose)
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
                return@map prefix.dropLast(1) + " " + node.toStringImpl(withLocation = verbose)
            }

            // If during spin cycle
            if (spinCycleDepth >= 0) {
                val prefix = "  ".repeat((virtualSpinCycleDepth - 2).coerceAtLeast(0)) + "|   " + "  ".repeat(max(virtualCallDepth - virtualSpinCycleDepth, 0))
                return@map prefix + node.toStringImpl(withLocation = verbose)
            }

            // Default
            return@map "  ".repeat(virtualCallDepth) + node.toStringImpl(withLocation = verbose)
        }
    }
}

internal fun traceToCollapsedTree(trace: Trace, analysisProfile: AnalysisProfile): SingleThreadedTable<TraceNode> {
    // Turn trace into a tree which is List of sections, where a section is a list of root nodes (actors).
    val traceTree = traceToTree(trace)

    // Optimizes trace by combining trace points for synthetic field accesses etc.
    val compressedTraceTree = traceTree
        .compressTrace()
        .collapseLibraries(analysisProfile)

    return compressedTraceTree
}
