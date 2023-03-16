/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class ExceptionInParallelPartTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
        threads(2)
        actorsPerThread(2)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
    }

}

class ExceptionInInitPartTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        initial {
            actor(ExceptionInInitPartTest::exception)
        }
        parallel {
            thread {
                actor(ExceptionInInitPartTest::idle)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
    }

}

class ExceptionInPostPartTest : AbstractLincheckTest(UnexpectedExceptionFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        parallel {
            thread {
                actor(ExceptionInPostPartTest::idle)
            }
        }
        post {
            actor(ExceptionInPostPartTest::exception)
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
    }

}