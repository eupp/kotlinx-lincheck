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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier

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
    var verifier: Class<out Verifier?>

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
    message= "LincheckInternalOptions class exposes internal API, please use LincheckOptions instead",
    replaceWith=ReplaceWith("LincheckOptions"),
    level=DeprecationLevel.WARNING,
)
open class LincheckInternalOptions : LincheckOptions {

    /* Execution generation options */

    var threads         = DEFAULT_THREADS
    var actorsPerThread = DEFAULT_ACTORS_PER_THREAD
    var actorsBefore    = DEFAULT_ACTORS_BEFORE
    var actorsAfter     = DEFAULT_ACTORS_AFTER

    override val customScenarios = mutableListOf<ExecutionScenario>()

    internal var executionGenerator = DEFAULT_EXECUTION_GENERATOR

    internal var minimizeFailedScenario = true

    /* Running mode and time options */

    internal open var mode = LincheckMode.Hybrid

    internal var iterations         = DEFAULT_ITERATIONS
    internal var adjustIterations   = true

    internal var invocations         = DEFAULT_INVOCATIONS
    internal var adjustInvocations   = true

    override var testingTimeInSeconds = DEFAULT_TESTING_TIME_S

    internal var invocationTimeoutMs = DEFAULT_INVOCATION_TIMEOUT_MS

    /* Verification options */

    override var verifier                  = DEFAULT_VERIFIER
    override var sequentialImplementation  = null as Class<*>?
    override var checkObstructionFreedom   = false

    internal var requireStateEquivalenceImplementationCheck = false

    internal val guarantees: List<ManagedStrategyGuarantee>
        get() = _guarantees
    private val _guarantees = DEFAULT_GUARANTEES.toMutableList()

    /* Hang detection options */

    internal var hangingDetectionThreshold   = DEFAULT_HANGING_DETECTION_THRESHOLD
    internal var livelockEventsThreshold     = DEFAULT_LIVELOCK_EVENTS_THRESHOLD

    /* Optimization options */

    internal var eliminateLocalObjects = true

    /* Logging options */

    internal var verboseTrace   = false
    internal var logLevel       = DEFAULT_LOG_LEVEL

    /**
     * Use the specified number of threads for the parallel part of an execution.
     *
     * Note, that the actual number of threads can be less due to some restrictions
     * like [Operation.runOnce].
     *
     * @see ExecutionScenario.parallelExecution
     */
    fun threads(threads: Int) = apply {
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
    fun actorsPerThread(actorsPerThread: Int) = apply {
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
    fun actorsBefore(actorsBefore: Int) = apply {
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
    fun actorsAfter(actorsAfter: Int) = apply {
        this.actorsAfter = actorsAfter
    }

    /**
     * Use the specified execution generator.
     */
    fun executionGenerator(executionGenerator: Class<out ExecutionGenerator?>) = apply {
        this.executionGenerator = executionGenerator
    }

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    fun minimizeFailedScenario(minimizeFailedScenario: Boolean) = apply {
        this.minimizeFailedScenario = minimizeFailedScenario
    }

    /**
     * The mode used for running tests.
     *
     * @see LincheckMode
     */
    fun mode(mode: LincheckMode) = apply {
        this.mode = mode
    }

    /**
     * Number of different test scenarios to be executed.
     */
    fun iterations(iterations: Int) = apply {
        this.iterations = iterations
        this.adjustIterations = false
    }

    /**
     * Run each test scenario the specified number of times.
     */
    fun invocationsPerIteration(invocations: Int) = apply {
        this.invocations = invocations
        this.adjustInvocations = false
    }

    /**
     * The maximal amount of time in seconds dedicated to testing.
     */
    fun testingTimeInSeconds(time: Long) = apply {
        this.testingTimeInSeconds = time
    }

    /**
     * Timeout for single invocation.
     */
    internal fun invocationTimeout(timeoutMs: Long) = apply {
        this.invocationTimeoutMs = timeoutMs
    }

    /**
     * Use the specified verifier.
     */
    fun verifier(verifier: Class<out Verifier?>) = apply {
        this.verifier = verifier
    }

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    // TODO: we left the name sequentialSpecification for backward compatibility
    fun sequentialSpecification(clazz: Class<*>?) = apply {
        sequentialImplementation = clazz
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all the methods
     * in `java.util.concurrent.ConcurrentHashMap` are correct and this way the strategy will not try to switch threads
     * inside these methods. We can also mark methods irrelevant (e.g., in logging classes) so that they will be
     * completely ignored (so that they will neither be treated as atomic nor interrupted in the middle) while
     * studying possible interleavings.
     */
    fun addGuarantee(guarantee: ManagedStrategyGuarantee) = apply {
        _guarantees.add(guarantee)
    }

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    fun checkObstructionFreedom(checkObstructionFreedom: Boolean = true) = apply {
        this.checkObstructionFreedom = checkObstructionFreedom
    }

    /**
     * Require correctness check of test instance state equivalency relation defined by the user.
     * It checks whether two new instances of a test class are equal.
     * If the check fails [[IllegalStateException]] is thrown.
     */
    fun requireStateEquivalenceImplCheck(require: Boolean) = apply {
        requireStateEquivalenceImplementationCheck = require
    }

    /**
     * Use the specified maximum number of repetitions to detect endless loops (hangs).
     * A found loop will force managed execution to switch the executing thread or report
     * ab obstruction-freedom violation if [checkObstructionFreedom] is set.
     */
    fun hangingDetectionThreshold(hangingDetectionThreshold: Int) = apply {
        this.hangingDetectionThreshold = hangingDetectionThreshold
    }

    /**
     * Local objects elimination optimization.
     */
    internal fun eliminateLocalObjects(eliminateLocalObjects: Boolean) = apply {
        this.eliminateLocalObjects = eliminateLocalObjects
    }

    /**
     * Set to `true` to make Lincheck log all events in an incorrect execution trace.
     * By default, Lincheck collapses the method invocations that were not interrupted
     * (e.g., due to a switch to another thread), and omits all the details except for
     * the method invocation result.
     */
    fun verboseTrace(verboseTrace: Boolean = true) = apply {
        this.verboseTrace = verboseTrace
    }

    /**
     * Set logging level, [DEFAULT_LOG_LEVEL] is used by default.
     */
    fun logLevel(logLevel: LoggingLevel) = apply {
        this.logLevel = logLevel
    }

    companion object {
        internal const val DEFAULT_THREADS = 2
        internal const val DEFAULT_ACTORS_PER_THREAD = 5
        internal const val DEFAULT_ACTORS_BEFORE = 5
        internal const val DEFAULT_ACTORS_AFTER = 5
        internal val DEFAULT_EXECUTION_GENERATOR: Class<out ExecutionGenerator> =
            RandomExecutionGenerator::class.java


        internal const val DEFAULT_TESTING_TIME_S: Long = 10
        internal const val DEFAULT_ITERATIONS = 50
        internal const val DEFAULT_INVOCATIONS = 10_000
        internal const val DEFAULT_INVOCATION_TIMEOUT_MS: Long = 10_000 // 10 sec.

        internal val DEFAULT_VERIFIER: Class<out Verifier> = LinearizabilityVerifier::class.java
        internal val DEFAULT_GUARANTEES = listOf(
            // These classes use WeakHashMap, and thus, their code is non-deterministic.
            // Non-determinism should not be present in managed executions, but luckily the classes
            // can be just ignored, so that no thread context switches are added inside their methods.
            forClasses("kotlinx.coroutines.internal.StackTraceRecoveryKt").allMethods().ignore(),
            // Some atomic primitives are common and can be analyzed from a higher level of abstraction.
            forClasses { className: String -> isTrustedPrimitive(className) }.allMethods().treatAsAtomic()
        )

        internal const val DEFAULT_HANGING_DETECTION_THRESHOLD = 101
        internal const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = 10001

        internal fun createFromTestClassAnnotations(testClass: Class<*>): List<LincheckInternalOptions> {

            val stressOptions = testClass.getAnnotationsByType(StressCTest::class.java).map {
                LincheckInternalOptions().apply {
                    mode(LincheckMode.Stress)

                    threads(it.threads)
                    actorsPerThread(it.actorsPerThread)
                    actorsBefore(it.actorsBefore)
                    actorsAfter(it.actorsAfter)
                    executionGenerator(it.generator.java)
                    minimizeFailedScenario(it.minimizeFailedScenario)

                    iterations(it.iterations)
                    invocationsPerIteration(it.invocationsPerIteration)

                    verifier(it.verifier.java)
                    sequentialSpecification(chooseSequentialSpecification(it.sequentialSpecification.java, testClass))
                    requireStateEquivalenceImplCheck(it.requireStateEquivalenceImplCheck)
                }
            }

            val modelCheckingOptions = testClass.getAnnotationsByType(ModelCheckingCTest::class.java).map {
                LincheckInternalOptions().apply {
                    mode(LincheckMode.ModelChecking)

                    threads(it.threads)
                    actorsPerThread(it.actorsPerThread)
                    actorsBefore(it.actorsBefore)
                    actorsAfter(it.actorsAfter)
                    executionGenerator(it.generator.java)
                    minimizeFailedScenario(it.minimizeFailedScenario)

                    iterations(it.iterations)
                    invocationsPerIteration(it.invocationsPerIteration)

                    verifier(it.verifier.java)
                    sequentialSpecification(chooseSequentialSpecification(it.sequentialSpecification.java, testClass))
                    requireStateEquivalenceImplCheck(it.requireStateEquivalenceImplCheck)
                    checkObstructionFreedom(it.checkObstructionFreedom)

                    hangingDetectionThreshold(it.hangingDetectionThreshold)
                }
            }

            return stressOptions + modelCheckingOptions
        }
    }

}

enum class LincheckMode {
    Stress, ModelChecking, Hybrid
}

/*
 * Some atomic primitives are common and can be analyzed from a higher level
 * of abstraction or can not be transformed (i.e, Unsafe or AFU).
 * Thus, we do not transform them and improve the trace representation.
 *
 * For example, in the execution trace where `AtomicLong.get()` happens,
 * we print the code location where this atomic method is called
 * instead of going deeper inside it.
 */
private fun isTrustedPrimitive(className: String) =
    className == "java.lang.invoke.VarHandle" ||
    className == "sun.misc.Unsafe" ||
    className == "jdk.internal.misc.Unsafe" ||
    // AFUs and Atomic[Integer/Long/...]
    className.startsWith("java.util.concurrent.atomic.Atomic") ||
    className.startsWith("kotlinx.atomicfu.Atomic")
