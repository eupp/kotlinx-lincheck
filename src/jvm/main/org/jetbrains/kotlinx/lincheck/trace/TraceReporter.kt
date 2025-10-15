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
        // val flattenPolicy = if (verbose) VerboseTraceFlattenPolicy() else ShortTraceFlattenPolicy()
        // val flattenedTree = tree.flattenNodes(flattenPolicy).reorder()
        appendTraceTable(trace.threadNames, tree.reorder() /* flattenedTree */, verbose)
    }
}

/**
 * Appends trace table to [Appendable]
 */
internal fun Appendable.appendTraceTable(threadNames: List<String>, tree: SingleThreadedTable<TraceNode>, verbose: Boolean) {
    val sections = tree.splitIntoSections().map { section ->
        splitInColumns(threadNames.size, section).toStringTable(verbose)
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

/**
 * Splits trace into thread columns, preserving the order of events.
 *
 * Example (`e1, e2, e3` - events, `t1, t2, t3` - threads):
 * ```
 * | t1: e1 |          | t1:e1 |        |       |
 * | t3: e2 |    -->   |       |        | t3:e2 |
 * | t2: e3 |          |       | t2: e3 |       |
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

/**
 * Splits a single threaded table of trace nodes into multiple sections
 * based on placement of `SectionDelimiterTracePoint` trace points in the table.
 */
private fun SingleThreadedTable<TraceNode>.splitIntoSections(): List<SingleThreadedTable<TraceNode>> {
    val nodes = this
    val sections = mutableListOf<SingleThreadedTable<TraceNode>>()

    // Collect contiguous ranges between section delimiters as we iterate indices
    data class ExecutionPartRange(val part: ExecutionPart, val range: IntRange)
    val partRanges = mutableListOf<ExecutionPartRange>()

    var i = 0
    while (i < nodes.size) {
        val start = nodes.indexOf(from = i) { it.tracePoint is SectionDelimiterTracePoint }
            .takeIf { it != -1 } ?: break
        val end = nodes.indexOf(from = start + 1) { it.tracePoint is SectionDelimiterTracePoint }
            .takeIf { it != -1 } ?: nodes.size
        partRanges += ExecutionPartRange(
            part = (nodes[start].tracePoint as SectionDelimiterTracePoint).executionPart,
            range = IntRange(start + 1, end)
        )
        i = end
    }

    // Validate that execution parts appear in expected order.
    if (partRanges.isNotEmpty()) {
        val parts = partRanges.map { it.part }
        check(parts.count { it == ExecutionPart.INIT } <= 1) {
            "Expected at most one INIT section delimiter"
        }
        check(parts.count { it == ExecutionPart.PARALLEL } == 1) {
            "Expected exactly one PARALLEL section delimiter"
        }
        check(parts.count { it == ExecutionPart.POST } <= 1) {
            "Expected at most one POST section delimiter"
        }
        check(parts.count { it == ExecutionPart.VALIDATION } <= 1) {
            "Expected at most one VALIDATION section delimiter"
        }
        check(parts.isSortedBy { it.ordinal }) {
            "Expected section delimiters to be in the following order: INIT, PARALLEL, POST, VALIDATION," +
            "but got: $parts"
        }
    }

    for (range in partRanges.map { it.range }) {
        sections += nodes.subList(range.first, range.last)
    }
    // No sections found => add a single section consisting of all trace nodes
    if (sections.isEmpty()) sections.add(nodes)

    return sections
}

/**
 * Maps all trace nodes of the [MultiThreadedTable] to their string representation.

 * - Unfolds all nested trace nodes where needed.
 * - Prepends spin cycle visualization where needed.
 */
private fun MultiThreadedTable<TraceNode?>.toStringTable(verbose: Boolean = true): MultiThreadedTable<String> {
    return this.map { column ->
        val filter = if (verbose) VerboseTraceFilter() else ShortenTraceFilter()
        val columnPrinter = TraceColumnPrinter(filter, verbose)
        column.forEach { node ->
            columnPrinter.appendTraceNode(node)
        }
        columnPrinter.lines
    }
}

private class TraceColumnPrinter(
    val filter: TraceFilter? = null,
    val verbose: Boolean = true,
) {
    private val _lines: MutableList<String> = mutableListOf()
    val lines: List<String> get() = _lines

    private var callStack = mutableListOf<CallNode>()
    private val callDepth get() = callStack.size

    private var spinCycleState: SpinCycleState? = null
    private var spinCycleDepth: Int = -1

    fun appendTraceNode(node: TraceNode?) {
        if (node == null) {
            _lines.add("")
            return
        }

        val nodeLine = getPrefix() + node.toStringImpl(withLocation = verbose)
        _lines.add(nodeLine)
        updateSpinCycleState(node)

        if (node is CallNode && (filter?.shouldUnfold(node) ?: true)) {
            pushCallStack(node)
            try {
                val children = filter?.filterChildren(node) ?: node.children
                for (child in children) {
                    appendTraceNode(child)
                }
            } finally {
                popCallStack()
            }
        }
    }

    private fun updateSpinCycleState(node: TraceNode) {
        when {
            node is EventNode &&
            node.tracePoint.isSpinCycleStartTracePoint -> {
                check(spinCycleState == null)
                spinCycleState = SpinCycleState.START
                spinCycleDepth = node.callDepth
            }
            spinCycleState == SpinCycleState.START -> {
                spinCycleState = SpinCycleState.INSIDE
            }
            node is EventNode &&
            node.tracePoint.isSpinCycleEndTracePoint &&
            spinCycleState == SpinCycleState.INSIDE -> {
                check(spinCycleState == SpinCycleState.INSIDE)
                spinCycleState = SpinCycleState.END
            }
            spinCycleState == SpinCycleState.END -> {
                spinCycleState = null
                spinCycleDepth = -1
            }
        }
    }

    private fun pushCallStack(node: CallNode) {
        callStack.add(node)
    }

    private fun popCallStack() {
        callStack.removeLast()
    }

    fun getPrefix(): String {
        var paddingWidth = CALL_DEPTH_INDENT_MULTIPLIER * callDepth
        val spinCycleState = spinCycleState // redeclare local val for smart casting
        if (spinCycleState != null) {
            if (paddingWidth < SPIN_CYCLE_INDENT_MIN_WIDTH) {
                paddingWidth = SPIN_CYCLE_INDENT_MIN_WIDTH
            }
            check(spinCycleDepth >= 0)
            check(callDepth >= spinCycleDepth)
            val spinIndent = spinCycleState.indent.repeat(CALL_DEPTH_INDENT_MULTIPLIER * (callDepth - spinCycleDepth))
            val spacePadding = " ".repeat(paddingWidth - spinCycleState.prefix.length - spinIndent.length)
            // depending on spin state, returns one of these (assuming 1 call depth pad on each side):
            // - "  ┌╶>   "
            // - "  |     "
            // - "  └╶╶╶╶╶"
            return spacePadding + spinCycleState.prefix + spinIndent
        }
        return " ".repeat(paddingWidth)
    }

    private enum class SpinCycleState { START, INSIDE, END }

    private val SpinCycleState.prefix: String get() = when (this) {
        SpinCycleState.START  -> "┌╶> "
        SpinCycleState.INSIDE -> "|   "
        SpinCycleState.END    -> "└╶╶╶"
    }

    private val SpinCycleState.indent: String get() = when (this) {
        SpinCycleState.START  -> " "
        SpinCycleState.INSIDE -> " "
        SpinCycleState.END    -> "╶"
    }
}

private const val CALL_DEPTH_INDENT_MULTIPLIER : Int = 2  // indent on each call depth level
private const val SPIN_CYCLE_INDENT_MIN_WIDTH  : Int = 4  // min. indent of a trace point related to spin cycle
                                                          // should be equal to tracePointPrefix.length

private val TracePoint.isSpinCycleStartTracePoint: Boolean get() =
    this is SpinCycleStartTracePoint

private val TracePoint.isSpinCycleEndTracePoint: Boolean get() =
    this is ObstructionFreedomViolationExecutionAbortTracePoint ||
    this is SwitchEventTracePoint

internal fun traceToCollapsedTree(trace: Trace, analysisProfile: AnalysisProfile): SingleThreadedTable<TraceNode> {
    // Turn trace into a tree which is List of sections, where a section is a list of root nodes (actors).
    val traceTree = traceToTree(trace)
        .apply { appendResultNodes() }

    // Optimizes trace by combining trace points for synthetic field accesses etc.
    val compressedTraceTree = traceTree
        .compressTrace()
        .collapseLibraries(analysisProfile)

    return compressedTraceTree
}
