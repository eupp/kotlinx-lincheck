/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*

class SingletonCollectionInTraceTest {
    private var x = 0

    fun useList(set: List<Int>) {
        x = 1
    }

    fun useSet(set: Set<Int>) {
        x = 2
    }

    fun useMap(set: Map<Int, Int>) {
        x = 3
    }

    @Operation(runOnce = true)
    fun op() {
        useList(listOf(1))
        useSet(setOf(1))
        useMap(mapOf(1 to 1))
        error("fail")
    }

    @Test
    fun modelCheckingTest() {
        val failure = LincheckOptions()
            .mode(LincheckMode.ModelChecking)
            .iterations(1)
            .verboseTrace(true)
            .checkImpl(this::class.java)
        val message = failure.toString()
        assert("SingletonList" !in message)
        assert("SingletonSet" !in message)
        assert("SingletonMap" !in message)
    }
}