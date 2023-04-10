/*-
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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import kotlin.reflect.*

abstract class AbstractLincheckTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) : VerifierState() {
    open fun LincheckInternalOptions.customize() {}
    override fun extractState(): Any = System.identityHashCode(this)

    private fun LincheckInternalOptions.runInternalTest() {
        val failure: LincheckFailure? = checkImpl(this@AbstractLincheckTest::class.java)
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = LincheckInternalOptions().run {
        invocationsPerIteration(5_000)
        commonConfiguration()
        mode(LincheckMode.Stress)
        runInternalTest()
    }

    @Test(timeout = TIMEOUT)
    fun testWithModelCheckingStrategy(): Unit = LincheckInternalOptions().run {
        invocationsPerIteration(1_000)
        commonConfiguration()
        mode(LincheckMode.ModelChecking)
        runInternalTest()
    }

    private fun LincheckInternalOptions.commonConfiguration(): Unit = run {
        /*
         * TODO: currently we set TIMEOUT both as option and as JUnit test parameter,
         *       because currently init/post parts are run in the same thread as Lincheck
         *       (thus potentially causing hang of the tests);
         *       once PR https://github.com/Kotlin/kotlinx-lincheck/pull/146
         *       gets merged we will be able to set TIMEOUT here only.
         */
        testingTimeInSeconds(TIMEOUT / 1000)
        iterations(30)
        actorsBefore(2)
        threads(3)
        actorsPerThread(2)
        actorsAfter(2)
        minimizeFailedScenario(false)
        customize()
    }

}

private const val TIMEOUT = 100_000L

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}