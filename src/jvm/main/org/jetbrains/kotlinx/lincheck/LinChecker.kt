/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.math.max
import kotlin.reflect.*

/**
 * This class runs concurrent tests.
 */
class LinChecker(private val testClass: Class<*>, options: LincheckOptions?) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)
    private val testConfigurations: List<LincheckOptions>
    private val reporter: Reporter

    init {
        reporter = Reporter(options?.logLevel
            ?: testClass.getAnnotation(LogLevel::class.java)?.value
            ?: DEFAULT_LOG_LEVEL
        )
        testConfigurations = if (options == null)
            LincheckOptions.createFromTestClassAnnotations(testClass)
        else listOf(options)
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        checkImpl()?.let { throw LincheckAssertionError(it) }
    }

    /**
     * @return TestReport with information about concurrent test run.
     */
    internal fun checkImpl(): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) {
            "No Lincheck test configuration to run"
        }
        for (options in testConfigurations)
            check(options)?.let { return it }
        return null
    }

    private fun check(options: LincheckOptions): LincheckFailure? {
        val planner = Planner(options)
        val executionGenerator = options.createExecutionGenerator()
        var verifier = options.createVerifier(checkStateEquivalence = true)
        // var timeoutMs = options.testingTimeMs
        while (planner.shouldDoNextIteration()) {
            val i = planner.iteration
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behaviour is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % VERIFIER_REFRESH_CYCLE == 0)
                verifier = options.createVerifier(checkStateEquivalence = false)
            // TODO: maybe we should move custom scenarios logic into Planner?
            val isCustomScenario = (i < options.customScenarios.size)
            val scenario = if (isCustomScenario)
                options.customScenarios[i]
            else
                executionGenerator.nextExecution()
            scenario.validate()
            reporter.logIteration(scenario, planner)
            val strategy = options.createStrategy(testClass, scenario, testStructure)
            val failure = planner.measureIterationTime {
                strategy.run(verifier, planner)
            }
            reporter.logIterationStatistics(i, planner)
            if (failure == null)
                continue
            // fix the number of invocations for failure minimization
            val minimizationInvocationsCount =
                max(2 * planner.iterationsInvocationCount[i], planner.invocationsBound)
            val minimizedFailedIteration = if (options.minimizeFailedScenario && !isCustomScenario)
                failure.minimize(options, minimizationInvocationsCount)
            else
                failure
            reporter.logFailedIteration(minimizedFailedIteration)
            return minimizedFailedIteration
        }
        return null
    }

    private fun Strategy.run(verifier: Verifier, planner: Planner): LincheckFailure? {
        this.use {
            while (planner.shouldDoNextInvocation()) {
                val invocationResult = planner.measureInvocationTime { runInvocation() }
                    ?: return null
                invocationResult.check(this, verifier)
                    ?.let { return it }
            }
        }
        return null
    }

    // TODO: unify two Strategy.run functions (introduce Planner interface?)
    private fun Strategy.run(verifier: Verifier, invocationsCount: Int): LincheckFailure? {
        this.use {
            repeat(invocationsCount) {
                val invocationResult = runInvocation()
                    ?: return null
                invocationResult.check(this, verifier)
                    ?.let { return it }
            }
        }
        return null
    }

    /*
     * Checks whether the [result] is a failing one or is [CompletedInvocationResult]
     * but the verification fails, and return the corresponding failure.
     * Returns `null` if the result is correct.
     */
    private fun InvocationResult.check(strategy: Strategy, verifier: Verifier): LincheckFailure? = when (this) {
        is CompletedInvocationResult -> {
            if (!verifier.verifyResults(strategy.scenario, results))
                IncorrectResultsFailure(strategy.scenario, results, strategy.collectTrace(this))
            else null
        }
        else -> toLincheckFailure(strategy.scenario, strategy.collectTrace(this))
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private fun LincheckFailure.minimize(options: LincheckOptions, invocationsCount: Int): LincheckFailure {
        reporter.logScenarioMinimization(scenario)
        var minimizedFailure = this
        while (true) {
            minimizedFailure = minimizedFailure.scenario.tryMinimize(options, invocationsCount)
                ?: break
        }
        return minimizedFailure
    }

    private fun ExecutionScenario.tryMinimize(options: LincheckOptions, invocationsCount: Int): LincheckFailure? {
        // Reversed indices to avoid conflicts with in-loop removals
        for (i in parallelExecution.indices.reversed()) {
            for (j in parallelExecution[i].indices.reversed()) {
                val failure = tryMinimize(i + 1, j, options, invocationsCount)
                if (failure != null) return failure
            }
        }
        for (j in initExecution.indices.reversed()) {
            val failure = tryMinimize(0, j, options, invocationsCount)
            if (failure != null) return failure
        }
        for (j in postExecution.indices.reversed()) {
            val failure = tryMinimize(threads + 1, j, options, invocationsCount)
            if (failure != null) return failure
        }
        return null
    }

    private fun ExecutionScenario.tryMinimize(
        threadId: Int, position: Int, options: LincheckOptions, invocationsCount: Int
    ): LincheckFailure? {
        val newScenario = this.copy()
        val actors = newScenario[threadId] as MutableList<Actor>
        actors.removeAt(position)
        if (actors.isEmpty() && threadId != 0 && threadId != newScenario.threads + 1) {
            // Also remove the empty thread
            newScenario.parallelExecution.removeAt(threadId - 1)
        }
        return if (newScenario.isValid) {
            val verifier = options.createVerifier(checkStateEquivalence = false)
            val strategy = options.createStrategy(testClass, newScenario, testStructure)
            strategy.run(verifier, invocationsCount)
        } else null
    }

    private fun ExecutionScenario.copy() = ExecutionScenario(
        ArrayList(initExecution),
        parallelExecution.map { ArrayList(it) },
        ArrayList(postExecution)
    )

    private val ExecutionScenario.isValid: Boolean
        get() = !isParallelPartEmpty &&
                (!hasSuspendableActors() || (!hasSuspendableActorsInInitPart && !hasPostPartAndSuspendableActors))

    private fun ExecutionScenario.validate() {
        require(!isParallelPartEmpty) {
            "The generated scenario has empty parallel part"
        }
        if (hasSuspendableActors()) {
            require(!hasSuspendableActorsInInitPart) {
                "The generated scenario for the test class with suspendable methods contains suspendable actors in initial part"
            }
            require(!hasPostPartAndSuspendableActors) {
                "The generated scenario  for the test class with suspendable methods has non-empty post part"
            }
        }
    }

    private val ExecutionScenario.hasSuspendableActorsInInitPart get() =
        initExecution.stream().anyMatch(Actor::isSuspendable)
    private val ExecutionScenario.hasPostPartAndSuspendableActors get() =
        (parallelExecution.stream().anyMatch { actors -> actors.stream().anyMatch { it.isSuspendable } } && postExecution.size > 0)
    private val ExecutionScenario.isParallelPartEmpty get() =
        parallelExecution.map { it.size }.sum() == 0

    private fun LincheckOptions.createExecutionGenerator() =
        executionGenerator.getConstructor(
            LincheckOptions::class.java,
            CTestStructure::class.java
        ).newInstance(this, testStructure)

    private fun LincheckOptions.createVerifier(checkStateEquivalence: Boolean): Verifier {
        val sequentialSpecification = chooseSequentialSpecification(this.sequentialSpecification, testClass)
        return verifier.getConstructor(Class::class.java).newInstance(sequentialSpecification).also {
            if (!checkStateEquivalence) return@also
            val stateEquivalenceCorrect = it.checkStateEquivalenceImplementation()
            if (!stateEquivalenceCorrect) {
                if (requireStateEquivalenceImplementationCheck) {
                    val errorMessage = StringBuilder()
                        .appendStateEquivalenceViolationMessage(sequentialSpecification)
                        .toString()
                    error(errorMessage)
                } else {
                    reporter.logStateEquivalenceViolation(sequentialSpecification)
                }
            }
        }
    }

    private fun LincheckOptions.createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        testStructure: CTestStructure,
    ): Strategy = when(mode) {
        LincheckMode.Stress ->
            StressStrategy(testClass, scenario, testStructure.validationFunctions, testStructure.stateRepresentation, this)
        LincheckMode.ModelChecking ->
            ModelCheckingStrategy(testClass, scenario, testStructure.validationFunctions, testStructure.stateRepresentation, this)
        else -> TODO()
    }

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: LincheckOptions? = null) {
            LinChecker(testClass, options).check()
        }

        private const val VERIFIER_REFRESH_CYCLE = 100
    }
}


/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
fun LincheckOptions.check(testClass: Class<*>) = LinChecker.check(testClass, this)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
fun LincheckOptions.check(testClass: KClass<*>) = this.check(testClass.java)

internal fun LincheckOptions.checkImpl(testClass: Class<*>) = LinChecker(testClass, this).checkImpl()