/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.distributed.*


internal class EnvironmentImpl<Message, Log>(
    val context: DistributedRunnerContext<Message, Log>,
    override val nodeId: Int,
    override val log: MutableList<Log> = mutableListOf()
) :
    Environment<Message, Log> {
    companion object {
        const val TICK_TIME = 1
    }

    override val numberOfNodes = context.addressResolver.totalNumberOfNodes

    private val probability = context.probabilities[nodeId]

    internal val timers = mutableSetOf<String>()

    override fun getAddressesForClass(cls: Class<out Node<Message>>) = context.addressResolver[cls]

    override suspend fun send(message: Message, receiver: Int) {
        if (isFinished) {
            return
        }
        probability.curMsgCount++
        if (context.testCfg.supportRecovery != RecoveryMode.NO_CRASHES &&
            context.addressResolver.canFail(nodeId) &&
            probability.nodeFailed() &&
            context.crashNode(nodeId)
        ) {
            throw CrashError()
        }
        if (context.testCfg.networkPartitions &&
            probability.isNetworkPartition()
        ) {
            context.setNetworkPartition(nodeId)
        }
        val crashInfo = context.crashInfo.value
        if (!crashInfo.canSend(nodeId, receiver)) {
            check(context.testCfg.networkPartitions || crashInfo[receiver])
            return
        }
        val event = MessageSentEvent(
            message = message,
            receiver = receiver,
            id = context.messageId.getAndIncrement(),
            clock = context.incClockAndCopy(nodeId),
            state = context.getStateRepresentation(nodeId)
        )
        context.events.put(nodeId to event)
        val rate = probability.duplicationRate()
        repeat(rate) {
            context.messageHandler[nodeId, event.receiver].send(event)
        }
    }

    override fun events(): Array<List<Event>> = if (isFinished) {
        val events = context.events.toList().groupBy { it.first }.mapValues { it.value.map { it.second } }
        Array(numberOfNodes) {
            events[it]!!
        }
    } else {
        throw IllegalAccessException("Cannot access events until the execution is over ")
    }

    @Volatile
    internal var isFinished = false

    override suspend fun withTimeout(ticks: Int, block: suspend CoroutineScope.() -> Unit) =
        context.taskCounter.runSafely {
            val res = withTimeoutOrNull((ticks * TICK_TIME).toLong(), block)
            res != null
        }

    override fun getLogs() = context.logs


    override suspend fun sleep(ticks: Int) {
        context.taskCounter.runSafely { delay((ticks * TICK_TIME).toLong()) }
    }

    override fun setTimer(name: String, ticks: Int, f: suspend () -> Unit) {
        if (timers.contains(name)) {
            throw IllegalArgumentException("Timer with name \"$name\" already exists")
        }
        timers.add(name)
        context.events.put(
            nodeId to SetTimerEvent(
                name,
                context.incClockAndCopy(nodeId),
                context.testInstances[nodeId].stateRepresentation()
            )
        )
        GlobalScope.launch(context.dispatchers[nodeId] + CoroutineExceptionHandler { _, _ -> }) {
            while (true) {
                if (!timers.contains(name) || isFinished) return@launch
                context.events.put(
                    nodeId to TimerTickEvent(
                        name,
                        context.incClockAndCopy(nodeId),
                        context.testInstances[nodeId].stateRepresentation()
                    )
                )
                f()
                delay(ticks.toLong())
            }
        }
    }

    override fun cancelTimer(name: String) {
        if (!timers.contains(name)) {
            throw IllegalArgumentException("Timer with name \"$name\" does not exist")
        }
        timers.remove(name)
        context.events.put(
            nodeId to CancelTimerEvent(
                name,
                context.incClockAndCopy(nodeId),
                context.testInstances[nodeId].stateRepresentation()
            )
        )
    }

    override fun recordInternalEvent(message: String) {
        context.events.put(
            nodeId to InternalEvent(
                message,
                context.incClockAndCopy(nodeId),
                context.getStateRepresentation(nodeId)
            )
        )
    }
}