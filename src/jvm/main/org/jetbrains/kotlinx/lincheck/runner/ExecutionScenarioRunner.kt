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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.ExecutionClassLoader
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.ActorTracker
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.util.*
import java.lang.reflect.Method

internal class ExecutionScenarioRunner(
    val testClass: Class<*>,
    val scenario: ExecutionScenario,
    val validationFunction: Actor?,
    val stateRepresentationFunction: Method?,
    val strategy: Strategy?,
    val actorTracker: ActorTracker?,
    val timeoutMs: Long, // for deadlock or livelock detection
) {
    val nThreads: Int get() = scenario.nThreads

    private val executionClassLoader = ExecutionClassLoader()

    // Current actor id for each thread.
    private val currentActorId = IntArray(nThreads) { -1 }

    fun onActorStart(threadId: ThreadId) {
        val actorId = 1 + currentActorId[threadId]
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorStart(threadId, actorId, actor)
    }

    fun onActorReturn(threadId: ThreadId, result: Any?) {
        val actorId = currentActorId[threadId].ensure { it >= 0 }
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorReturn(threadId, actorId, actor, result)
    }

    fun onActorThrow(threadId: ThreadId, throwable: Throwable) {
        val actorId = currentActorId[threadId].ensure { it >= 0 }
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorThrow(threadId, actorId, actor, throwable)
    }

    fun onActorSuspension(threadId: ThreadId) {
        val actorId = currentActorId[threadId].ensure { it >= 0 }
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorSuspension(threadId, actorId, actor)
    }

    fun onActorResumption(threadId: ThreadId) {
        val actorId = currentActorId[threadId].ensure { it >= 0 }
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorResumption(threadId, actorId, actor)
    }

    fun onActorCancellation(threadId: ThreadId) {
        val actorId = currentActorId[threadId].ensure { it >= 0 }
        val actor = scenario.threads[threadId][actorId]
        actorTracker?.onActorCancellation(threadId, actorId, actor)
    }


}