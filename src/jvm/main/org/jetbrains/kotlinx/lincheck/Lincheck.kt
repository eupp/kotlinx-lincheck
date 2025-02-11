/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.LambdaRunner
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureObjectIsTransformed
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier


@RequiresOptIn(message = "The model checking API is experimental and will change in the future.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ExperimentalModelCheckingAPI

/**
 * This method will explore different interleavings of the [block] body and all the threads created within it,
 * searching for the first raised exception.
 *
 * @param invocations number of different interleavings of code in the [block] that should be explored.
 * @param block lambda which body will be a target for the interleavings exploration.
 */
@ExperimentalModelCheckingAPI
fun <R> runConcurrentTest(
    invocations: Int = DEFAULT_INVOCATIONS_COUNT,
    block: () -> R
) {
    val options = ModelCheckingOptions()
    val testCfg = options.createTestConfigurations(block::class.java)

    withLincheckJavaAgent(testCfg.instrumentationMode) {
        ensureObjectIsTransformed(block)
        val runner = LambdaRunner(timeoutMs = testCfg.timeoutMs, block)
        val strategy = ModelCheckingStrategy(runner, testCfg).also {
            runner.initializeStrategy(it)
        }
        val verifier = NoExceptionVerifier()
        val failure = strategy.runIteration(invocations, verifier)
        if (failure != null) {
            throw LincheckAssertionError(failure)
        }
    }
}

/**
 * [NoExceptionVerifier] checks that the lambda passed into [runConcurrentTest] does not throw an exception.
 */
internal class NoExceptionVerifier() : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

private const val DEFAULT_INVOCATIONS_COUNT = 50_000
