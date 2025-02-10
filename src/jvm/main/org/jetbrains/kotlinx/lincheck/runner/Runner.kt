/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.util.ensure
import sun.nio.ch.lincheck.TestThread
import sun.nio.ch.lincheck.ThreadDescriptor
import java.io.*

/**
 * Interface defining a runner that executes invocations of a given code under analysis.
 */
interface Runner : Closeable {
    /**
     * Runs the next invocation.
     */
    fun runInvocation(): InvocationResult

    /**
     * Closes the resources used in this runner.
     */
    override fun close() {}
}

internal abstract class AbstractActiveThreadPoolRunner : Runner {

    lateinit var strategy: Strategy
        private set

    abstract val executor : ActiveThreadPoolExecutor

    fun initializeStrategy(strategy: Strategy) {
        this.strategy = strategy
    }

    protected fun setEventTracker() {
        val eventTracker = (strategy as? ManagedStrategy) ?: return
        executor.threads.forEachIndexed { i, thread ->
            var descriptor = ThreadDescriptor.getThreadDescriptor(thread)
            if (descriptor == null) {
                descriptor = ThreadDescriptor(thread)
                ThreadDescriptor.setThreadDescriptor(thread, descriptor)
            }
            descriptor.eventTracker = eventTracker
            eventTracker.registerThread(thread, descriptor)
                .ensure { threadId -> threadId == i }
        }
    }

    protected fun resetEventTracker() {
        if (!::strategy.isInitialized) return
        if (strategy !is ManagedStrategy) return
        for (thread in executor.threads) {
            val descriptor = ThreadDescriptor.getThreadDescriptor(thread)
                ?: continue
            descriptor.eventTracker = null
        }
    }

    /**
     * Determines if this runner manages the provided thread.
     */
    protected fun isCurrentRunnerThread(thread: Thread): Boolean =
        executor.threads.any { it === thread }

    /**
     * Collects the current thread dump from all threads.
     */
    protected fun collectThreadDump() = Thread.getAllStackTraces().filter { (t, _) ->
        t is TestThread && isCurrentRunnerThread(t)
    }
}
