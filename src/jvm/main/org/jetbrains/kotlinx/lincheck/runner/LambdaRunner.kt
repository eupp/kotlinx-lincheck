/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.NoResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import org.jetbrains.kotlinx.lincheck.toLinCheckResult
import org.jetbrains.kotlinx.lincheck.util.threadMapOf
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

internal class LambdaRunner<R>(
    private val timeoutMs: Long, // for deadlock or livelock detection
    val block: () -> R
) : AbstractActiveThreadPoolRunner() {

    private val testName =
        runCatching { block.javaClass.simpleName }.getOrElse { "lambda" }

    override val executor = ActiveThreadPoolExecutor(testName, 1)

    override fun runInvocation(): InvocationResult {
        var timeout = timeoutMs * 1_000_000
        val wrapper = LambdaWrapper(block)
        try {
            setEventTracker()
            val tasks = threadMapOf(0 to wrapper)
            timeout -= executor.submitAndAwait(tasks, timeout)
            timeout -= strategy.awaitUserThreads(timeout)
            return CompletedInvocationResult(collectExecutionResults(wrapper))
        } catch (e: TimeoutException) {
            return RunnerTimeoutInvocationResult(wrapper)
        } catch (e: ExecutionException) {
            // In case when invocation raised an exception,
            // we need to wait for all user threads to finish before continuing.
            // In case if waiting for threads termination abrupt with the timeout,
            // we return `RunnerTimeoutInvocationResult`.
            // Otherwise, we return `UnexpectedExceptionInvocationResult` with the original exception.
            runCatching { strategy.awaitUserThreads(timeout) }.onFailure { exception ->
                if (exception is TimeoutException) return RunnerTimeoutInvocationResult(wrapper)
            }
            return UnexpectedExceptionInvocationResult(e.cause!!, collectExecutionResults(wrapper))
        } finally {
            resetEventTracker()
        }
    }

    private class LambdaWrapper<R>(val block: () -> R) : Runnable {
        var result: kotlin.Result<R>? = null

        override fun run() {
            result = kotlin.runCatching { block() }
        }
    }

    // TODO: currently we have to use `ExecutionResult`,
    //   even though in case of `LambdaRunner` the result can be significantly simplified
    private fun collectExecutionResults(wrapper: LambdaWrapper<*>) = ExecutionResult(
        parallelResultsWithClock = listOf(listOf(
            ResultWithClock(wrapper.result?.toLinCheckResult() ?: NoResult, emptyClock(0))
        )),
        initResults = listOf(),
        postResults = listOf(),
        afterInitStateRepresentation = null,
        afterParallelStateRepresentation = null,
        afterPostStateRepresentation = null,
    )

    private fun RunnerTimeoutInvocationResult(wrapper: LambdaWrapper<*>): RunnerTimeoutInvocationResult {
        val threadDump = collectThreadDump()
        return RunnerTimeoutInvocationResult(threadDump, collectExecutionResults(wrapper))
    }
}