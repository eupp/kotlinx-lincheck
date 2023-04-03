/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.LincheckOptions
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

class StressStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    private val verifier: Verifier,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    options: LincheckOptions,
) : Strategy(scenario) {
    private val invocations = options.invocationsPerIteration
    private val runner: Runner

    init {
        runner = ParallelThreadsRunner(
            strategy = this,
            testClass = testClass,
            validationFunctions = validationFunctions,
            stateRepresentationFunction = stateRepresentationFunction,
            timeoutMs = options.invocationTimeoutMs,
            useClocks = UseClocks.RANDOM
        )
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(timeoutMs: Long): LincheckFailure? = runner.use {
        // TODO: unify time and invocations counting logic in StressStrategy and ModelCheckingStrategy
        val startTime = System.currentTimeMillis()
        // Run invocations
        for (invocation in 0 until invocations) {
            when (val invocationResult = runner.run()) {
                is CompletedInvocationResult -> {
                    if (!verifier.verifyResults(scenario, invocationResult.results))
                        return@use IncorrectResultsFailure(scenario, invocationResult.results)
                }
                else -> return@use invocationResult.toLincheckFailure(scenario)
            }
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs)
                return@use null
        }
        null
    }
}