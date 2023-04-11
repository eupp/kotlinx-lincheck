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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*

interface LincheckOptions {

    /**
     * The maximal amount of time in seconds dedicated to testing.
     */
    var testingTimeInSeconds: Long

    /**
     * Examine the specified custom scenarios additionally to the generated ones.
     */
    val customScenarios: MutableList<ExecutionScenario>

    /**
     * The verifier class used to check consistency of the execution.
     */
    var verifier: Class<out Verifier>

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    var sequentialImplementation: Class<*>?

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    var checkObstructionFreedom: Boolean

}

/**
 * Add the specified custom scenario additionally to the generated ones.
 */
fun LincheckOptions.addCustomScenario(scenario: ExecutionScenario) = apply {
    customScenarios.add(scenario)
}

/**
 * Add the specified custom scenario additionally to the generated ones.
 */
fun LincheckOptions.addCustomScenario(scenarioBuilder: DSLScenarioBuilder.() -> Unit) = apply {
    addCustomScenario(scenario { scenarioBuilder() })
}

/**
 * Creates new instance of LincheckOptions class.
 */
// for backward compatibility, in order to imitate constructor call syntax, we use capital letters here.
fun LincheckOptions(): LincheckOptions =
    LincheckInternalOptions()

/**
 * Abstract class for test options.
 */
@Deprecated(
    message= "Options class exposes internal API, please use LincheckOptions instead",
    replaceWith=ReplaceWith("LincheckOptions"),
    level=DeprecationLevel.WARNING,
)
@Suppress("DEPRECATION_ERROR")
abstract class Options<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> {
    internal var logLevel = DEFAULT_LOG_LEVEL

    var iterations = CTestConfiguration.DEFAULT_ITERATIONS
    var invocationsPerIteration = CTestConfiguration.DEFAULT_INVOCATIONS
    var threads = CTestConfiguration.DEFAULT_THREADS
    var actorsPerThread = CTestConfiguration.DEFAULT_ACTORS_PER_THREAD
    var actorsBefore = CTestConfiguration.DEFAULT_ACTORS_BEFORE
    var actorsAfter = CTestConfiguration.DEFAULT_ACTORS_AFTER
    var executionGenerator = CTestConfiguration.DEFAULT_EXECUTION_GENERATOR
    var verifier = CTestConfiguration.DEFAULT_VERIFIER
    var requireStateEquivalenceImplementationCheck = false
    var minimizeFailedScenario = CTestConfiguration.DEFAULT_MINIMIZE_ERROR
    var sequentialSpecification: Class<*>? = null
    var timeoutMs: Long = CTestConfiguration.DEFAULT_TIMEOUT_MS
    val customScenarios: MutableList<ExecutionScenario> = mutableListOf()

    val adjustIterations = true
    val adjustInvocations = true

    /**
     * Run each test scenario the specified number of times.
     */
    fun invocationsPerIteration(invocations: Int): OPT = applyAndCast {
        invocationsPerIteration = invocations
    }

    /**
     * Number of different test scenarios to be executed
     */
    fun iterations(iterations: Int): OPT = applyAndCast {
        this.iterations = iterations
    }

    /**
     * Use the specified number of threads for the parallel part of an execution.
     *
     * Note, that the the actual number of threads can be less due to some restrictions
     * like [Operation.runOnce].
     *
     * @see ExecutionScenario.parallelExecution
     */
    fun threads(threads: Int): OPT = applyAndCast {
        this.threads = threads
    }

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [Operation.runOnce].
     *
     * @see ExecutionScenario.parallelExecution
     */
    fun actorsPerThread(actorsPerThread: Int): OPT = applyAndCast {
        this.actorsPerThread = actorsPerThread
    }

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [Operation.runOnce].
     *
     * @see ExecutionScenario.initExecution
     */
    fun actorsBefore(actorsBefore: Int): OPT = applyAndCast {
        this.actorsBefore = actorsBefore
    }

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [Operation.runOnce].
     *
     * @see ExecutionScenario.postExecution
     */
    fun actorsAfter(actorsAfter: Int): OPT = applyAndCast {
        this.actorsAfter = actorsAfter
    }

    /**
     * Use the specified execution generator.
     */
    fun executionGenerator(executionGenerator: Class<out ExecutionGenerator?>): OPT = applyAndCast {
        this.executionGenerator = executionGenerator
    }

    /**
     * Use the specified verifier.
     */
    fun verifier(verifier: Class<out Verifier?>): OPT = applyAndCast {
        this.verifier = verifier
    }

    /**
     * Require correctness check of test instance state equivalency relation defined by the user.
     * It checks whether two new instances of a test class are equal.
     * If the check fails [[IllegalStateException]] is thrown.
     */
    fun requireStateEquivalenceImplCheck(require: Boolean): OPT = applyAndCast {
        requireStateEquivalenceImplementationCheck = require
    }

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    fun minimizeFailedScenario(minimizeFailedScenario: Boolean): OPT = applyAndCast {
        this.minimizeFailedScenario = minimizeFailedScenario
    }

    abstract fun createTestConfigurations(testClass: Class<*>): CTEST

    /**
     * Set logging level, [DEFAULT_LOG_LEVEL] is used by default.
     */
    fun logLevel(logLevel: LoggingLevel): OPT = applyAndCast {
        this.logLevel = logLevel
    }

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    fun sequentialSpecification(clazz: Class<*>?): OPT = applyAndCast {
        sequentialSpecification = clazz
    }

    /**
     * Examine the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenario: ExecutionScenario) = applyAndCast {
        customScenarios.add(scenario)
    }

    /**
     * Examine the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenarioBuilder: DSLScenarioBuilder.() -> Unit) =
        addCustomScenario(scenario { scenarioBuilder() })

    /**
     * Internal, DO NOT USE.
     */
    internal fun invocationTimeout(timeoutMs: Long): OPT = applyAndCast {
        this.timeoutMs = timeoutMs
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> Options<OPT, CTEST>.applyAndCast(
            block: Options<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}

internal enum class LincheckMode {
    Stress, ModelChecking, Hybrid
}

@Suppress("DEPRECATION_ERROR")
internal class LincheckInternalOptions :
    LincheckOptions,
    Options<LincheckInternalOptions, CTestConfiguration>()
{

    internal var mode = LincheckMode.Hybrid

    override var testingTimeInSeconds: Long = DEFAULT_TESTING_TIME

    override var sequentialImplementation: Class<*>?
        get() = sequentialSpecification
        set(clazz) {
            sequentialSpecification = clazz
        }

    override var checkObstructionFreedom: Boolean = false

    override fun createTestConfigurations(testClass: Class<*>): CTestConfiguration = when (mode) {

        LincheckMode.Stress -> StressCTestConfiguration(testClass, iterations,
            threads, actorsPerThread, actorsBefore, actorsAfter,
            executionGenerator, verifier,
            invocationsPerIteration,
            requireStateEquivalenceImplementationCheck,
            minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass),
            timeoutMs, customScenarios
        )

        LincheckMode.ModelChecking -> ModelCheckingCTestConfiguration(testClass, iterations,
            threads, actorsPerThread, actorsBefore, actorsAfter,
            executionGenerator, verifier,
            checkObstructionFreedom,
            ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD,
            invocationsPerIteration,
            ManagedCTestConfiguration.DEFAULT_GUARANTEES,
            requireStateEquivalenceImplementationCheck,
            minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass),
            timeoutMs,
            ManagedCTestConfiguration.DEFAULT_ELIMINATE_LOCAL_OBJECTS,
            ManagedCTestConfiguration.DEFAULT_VERBOSE_TRACE,
            customScenarios,
        )

        else -> throw IllegalStateException()
    }

    companion object {
        private const val DEFAULT_TESTING_TIME = 10L

        fun fromCTestConfiguration(testCfg: CTestConfiguration) = LincheckInternalOptions().apply {
            threads = testCfg.threads
            iterations = testCfg.iterations
            actorsPerThread = testCfg.actorsPerThread
            actorsBefore = testCfg.actorsBefore
            actorsAfter = testCfg.actorsAfter
            executionGenerator = testCfg.generatorClass
            invocationsPerIteration = testCfg.invocationsPerIteration
            requireStateEquivalenceImplementationCheck = testCfg.requireStateEquivalenceImplCheck
            minimizeFailedScenario = testCfg.minimizeFailedScenario
            sequentialSpecification = testCfg.sequentialSpecification
            timeoutMs = testCfg.timeoutMs
            customScenarios.addAll(testCfg.customScenarios)
            if (testCfg is StressCTestConfiguration) {
                mode = LincheckMode.Stress
            }
            if (testCfg is ModelCheckingCTestConfiguration) {
                mode = LincheckMode.ModelChecking
                checkObstructionFreedom = testCfg.checkObstructionFreedom
            }
        }

    }

}