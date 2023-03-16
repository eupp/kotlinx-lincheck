/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class HangingInParallelPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
        threads(2)
        actorsPerThread(2)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}

class HangingInInitPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        initial {
            actor(HangingInInitPartTest::hang)
        }
        parallel {
            thread {
                actor(HangingInInitPartTest::idle)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}

class HangingInPostPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        parallel {
            thread {
                actor(HangingInPostPartTest::idle)
            }
        }
        post {
            actor(HangingInPostPartTest::hang)
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}