/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

/**
 * Configuration for [random search][ModelCheckingStrategy] strategy.
 */
class ModelCheckingCTestConfiguration(testClass: Class<*>, iterations: Int, threads: Int, actorsPerThread: Int, actorsBefore: Int,
                                      actorsAfter: Int, generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
                                      checkObstructionFreedom: Boolean, hangingDetectionThreshold: Int,
                                      invocationsPerIteration: Int, guarantees: List<ManagedStrategyGuarantee>,
                                      minimizeFailedScenario: Boolean, sequentialSpecification: Class<*>, timeoutMs: Long,
                                      customScenarios: List<ExecutionScenario>
) : ManagedCTestConfiguration(
    testClass = testClass,
    iterations = iterations,
    threads = threads,
    actorsPerThread = actorsPerThread,
    actorsBefore = actorsBefore,
    actorsAfter = actorsAfter,
    generatorClass = generatorClass,
    verifierClass = verifierClass,
    checkObstructionFreedom = checkObstructionFreedom,
    hangingDetectionThreshold = hangingDetectionThreshold,
    invocationsPerIteration = invocationsPerIteration,
    guarantees = guarantees,
    minimizeFailedScenario = minimizeFailedScenario,
    sequentialSpecification = sequentialSpecification,
    timeoutMs = timeoutMs,
    customScenarios = customScenarios
) {

    override val instrumentationMode: InstrumentationMode get() = MODEL_CHECKING

    // The flag to enable IntelliJ IDEA plugin mode
    private var inIdeaPluginReplayMode: Boolean = false

    internal fun enableReplayModeForIdeaPlugin() {
        inIdeaPluginReplayMode = true
    }

    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
        stateRepresentationMethod: Method?,
    ): Strategy {
        val runner = ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationFunction = stateRepresentationMethod,
            timeoutMs = getTimeOutMs(inIdeaPluginReplayMode, timeoutMs),
            useClocks = UseClocks.ALWAYS
        )
        return ModelCheckingStrategy(runner, this, inIdeaPluginReplayMode).also {
            runner.initializeStrategy(it)
        }
    }

}

private fun getTimeOutMs(inIdeaPluginReplayMode: Boolean, defaultTimeOutMs: Long): Long =
    if (inIdeaPluginReplayMode) INFINITE_TIMEOUT else defaultTimeOutMs

/**
 * With idea plugin enabled, we should not use default Lincheck timeout
 * as debugging may take more time than default timeout.
 */
private const val INFINITE_TIMEOUT = 1000L * 60 * 60 * 24 * 365
