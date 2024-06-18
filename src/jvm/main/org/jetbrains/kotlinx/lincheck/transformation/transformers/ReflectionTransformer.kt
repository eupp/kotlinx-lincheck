/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.transformation.ManagedStrategyMethodVisitor
import org.jetbrains.kotlinx.lincheck.transformation.invokeIfInTestingCode
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.Injections

/**
 * [ReflectionTransformer] tracks some of the reflection method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ReflectionTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    private val interceptArrayCopyMethod: Boolean = false,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        val isSystemMethodInvocation = (owner == "java/lang/System")
        when {
            isSystemMethodInvocation && name == "arraycopy" ->
                visitArrayCopyMethod(opcode, owner, name, desc, itf)

            else ->
                visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    private fun visitArrayCopyMethod(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (!interceptArrayCopyMethod) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        // STACK: srcArray, srcPos, dstArray, dstPos, length
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                invokeStatic(Injections::onArrayCopy)
            }
        )
    }

}