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
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.lincheck.util.*
import kotlin.math.max

internal typealias SingleThreadedTable<T> = Column<T>
internal typealias MultiThreadedTable<T> = List<Column<T>>
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
    val sections = tree.splitIntoSections().map { section ->
        splitInColumns(threadNames.size, section).mapCellsToStrings(verbose)
    }
    val layout = ExecutionLayout(
        nThreads = threadNames.size,
        interleavingSections = sections,
        threadNames = threadNames,
    )
    with(layout) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        sections.forEach { section ->
            appendColumns(section)
            appendSeparatorLine()
        }
    }
}

private fun SingleThreadedTable<TraceNode>.splitIntoSections(): List<SingleThreadedTable<TraceNode>> {
    val nodes = this
    val sections = mutableListOf<SingleThreadedTable<TraceNode>>()

    var hasInitSection = false
    var hasParallelSection = false
    var hasPostSection = false
    var hasValidationSection = false

    // start indices are inclusive, end indices are exclusive
    var initSectionStart = -1
    var initSectionEnd = -1
    var parallelSectionStart = -1
    var parallelSectionEnd = -1
    var postSectionStart = -1
    var postSectionEnd = -1
    var validationSectionStart = -1
    var validationSectionEnd = -1

    nodes.forEachIndexed { i, node ->
        val sectionDelimiterPoint = (node.tracePoint as? SectionDelimiterTracePoint)
            ?: return@forEachIndexed
        when (sectionDelimiterPoint.executionPart) {
            ExecutionPart.INIT -> {
                check(!hasInitSection) { "Init section was already discovered" }
                hasInitSection = true
                initSectionStart = i + 1
            }
            ExecutionPart.PARALLEL -> {
                if (hasInitSection) {
                    initSectionEnd = i
                }
                hasParallelSection = true
                parallelSectionStart = i + 1
            }
            ExecutionPart.POST -> {
                check(!hasPostSection) { "Post section was already discovered" }
                hasPostSection = true
                parallelSectionEnd = i
                postSectionStart = i + 1
            }
            ExecutionPart.VALIDATION -> {
                check(!hasValidationSection) { "Validation section was already discovered" }
                hasValidationSection = true
                validationSectionStart = i + 1
                if (hasPostSection) {
                    postSectionEnd = i
                } else {
                    parallelSectionEnd = i
                }
            }
        }
    }
    if (parallelSectionEnd == -1) {
        parallelSectionEnd = nodes.size
    }
    if (hasPostSection && postSectionEnd == -1) {
        postSectionEnd = nodes.size
    }
    if (hasValidationSection && validationSectionEnd == -1) {
        validationSectionEnd = nodes.size
    }

    if (hasInitSection) {
        sections.add(nodes.subList(initSectionStart, initSectionEnd))
    }
    if (hasParallelSection) {
        sections.add(nodes.subList(parallelSectionStart, parallelSectionEnd))
    }
    if (hasPostSection) {
        sections.add(nodes.subList(postSectionStart, postSectionEnd))
    }
    if (hasValidationSection) {
        sections.add(nodes.subList(validationSectionStart, validationSectionEnd))
    }

    // no sections found => add a single section consisting of all trace nodes
    if (sections.isEmpty()) {
        sections.add(nodes)
    }

    return sections
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
private fun splitInColumns(nThreads: Int, flattened: SingleThreadedTable<TraceNode>): MultiThreadedTable<TraceNode?> {
    val multiThreadedTable = List<MutableList<TraceNode?>>(nThreads) { mutableListOf() }
    repeat(nThreads) { iThread ->
        flattened.forEach { node ->
            multiThreadedTable[iThread].add(node.takeIf { it.iThread == iThread })
        }
    }
    return multiThreadedTable
}

private const val NO_SPIN_CYCLE = -1
private const val START_SPIN_CYCLE = -2

/**
 * Maps all cells of the [MultiThreadedTable] to their string representation.
 * Prepends spin cycle visualization where needed.
 */
private fun MultiThreadedTable<TraceNode?>.mapCellsToStrings(verbose: Boolean = true): MultiThreadedTable<String> {
    return this.map tableMap@{ column ->
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
