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

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

/**
 * [MethodCallTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class MethodCallTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // TODO: do not ignore <init>
        if (name == "<init>" || isIgnoredMethod(owner, name)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        if (isCoroutineInternalClass(owner)) {
            invokeInIgnoredSection {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                processMethodCall(desc, opcode, owner, name, itf)
            }
        )
    }

    private fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        val endLabel = newLabel()
        val methodCallStartLabel = newLabel()
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        val argumentLocals = storeArguments(desc)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] : <empty>
        when (opcode) {
            INVOKESTATIC -> visitInsn(ACONST_NULL)
            else -> dup()
        }
        push(owner)
        push(name)
        loadNewCodeLocationId()
        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
        // STACK [INVOKESTATIC]:         null , className, methodName, codeLocation
        pushArray(argumentLocals)
        // STACK: ..., argumentsArray
        invokeStatic(Injections::beforeMethodCall)
        invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        val methodCallEndLabel = newLabel()
        val handlerExceptionStartLabel = newLabel()
        visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
        visitLabel(methodCallStartLabel)
        loadLocals(argumentLocals)
        visitMethodInsn(opcode, owner, name, desc, itf)
        visitLabel(methodCallEndLabel)
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        processMethodCallResult(desc)
        // STACK: result
        goTo(endLabel)
        visitLabel(handlerExceptionStartLabel)
        dup()
        invokeStatic(Injections::onMethodCallException)
        throwException()
        visitLabel(endLabel)
        // STACK: result
    }

    private fun processMethodCallResult(desc: String) = adapter.run {
        // STACK: result?
        val resultType = Type.getReturnType(desc)
        if (resultType == VOID_TYPE) {
            // STACK: <empty>
            invokeStatic(Injections::onMethodCallReturnVoid)
            // STACK: <empty>
        } else {
            // STACK: result
            val resultLocal = newLocal(resultType)
            copyLocal(resultLocal)
            box(resultType)
            invokeStatic(Injections::onMethodCallReturn)
            loadLocal(resultLocal)
            // STACK: result
        }
    }

    private fun isIgnoredMethod(owner: String, methodName: String) =
        owner.startsWith("sun/nio/ch/lincheck/") ||
        owner.startsWith("org/jetbrains/kotlinx/lincheck/") ||
        owner == "kotlin/jvm/internal/Intrinsics" ||
        owner == "java/util/Objects" ||
        owner == "java/lang/String" ||
        owner == "java/lang/Boolean" ||
        owner == "java/lang/Long" ||
        owner == "java/lang/Integer" ||
        owner == "java/lang/Short" ||
        owner == "java/lang/Byte" ||
        owner == "java/lang/Double" ||
        owner == "java/lang/Float" ||
        owner == "java/util/Locale" ||
        owner == "org/slf4j/helpers/Util" ||
        owner == "java/util/Properties"

}