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

internal interface TraceFilter {
    fun shouldUnfold(callNode: CallNode): Boolean
    fun filterChildren(callNode: CallNode): List<TraceNode>
}

internal class ShortenTraceFilter : TraceFilter {

    // a cache storing whether a node can be unfolded or not
    private val unfoldableNodes = mutableMapOf<CallNode, Boolean>()

    override fun shouldUnfold(callNode: CallNode): Boolean {
        if (callNode.isRootCall && callNode.tracePoint.isThreadStart) return true
        unfoldableNodes[callNode]?.let { return it }
        return callNode.children.any { child ->
            when (child) {
                is EventNode -> with(child) {
                    !tracePoint.isVirtual && (
                        tracePoint.isBlocking && isLast ||
                        tracePoint is SwitchEventTracePoint ||
                        tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint
                    )
                }
                is CallNode -> {
                    child.tracePoint.wasSuspended ||
                    shouldUnfold(child)
                }
                else -> false
            }
        }.also { decision ->
            unfoldableNodes[callNode] = decision
        }
    }

    override fun filterChildren(callNode: CallNode): List<TraceNode> {
        return callNode.children.filterNot { it.tracePoint.shouldFilter() }
    }

    private fun TracePoint.shouldFilter(): Boolean =
        isThrowableTracePoint
}

internal class VerboseTraceFilter : TraceFilter {
    override fun shouldUnfold(callNode: CallNode): Boolean = true

    override fun filterChildren(callNode: CallNode): List<TraceNode> {
        return callNode.children.filterNot { it.tracePoint.shouldFilter() }
    }

    private fun TracePoint.shouldFilter(): Boolean =
        isThrowableTracePoint
}