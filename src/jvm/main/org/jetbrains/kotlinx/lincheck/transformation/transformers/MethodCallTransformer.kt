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
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
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
        if (name == "<init>" || isIgnoredMethod(className = owner)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        if (isCoroutineInternalClass(owner)) {
            invokeInIgnoredSection {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }
        if (isCoroutineResumptionSyntheticAccessor(owner, name)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
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
        // STACK: owner?, arguments
        val endLabel = newLabel()
        val methodCallStartLabel = newLabel()
        val argumentLocals = storeArguments(desc)
        val ownerLocal = when {
            opcode != INVOKESTATIC -> newLocal(OBJECT_TYPE).also { storeLocal(it) }
            else -> null
        }
        // STACK: <empty>
        processMethodCallEnter(desc, owner, name, ownerLocal, argumentLocals)
        val methodCallEndLabel = newLabel()
        val handlerExceptionStartLabel = newLabel()
        visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
        visitLabel(methodCallStartLabel)
        ownerLocal?.let { loadLocal(it) }
        loadLocals(argumentLocals)
        // STACK: owner?, arguments
        visitMethodInsn(opcode, owner, name, desc, itf)
        visitLabel(methodCallEndLabel)
        // STACK: result?
        processMethodCallReturn(desc, owner, name, ownerLocal)
        // STACK: result?
        goTo(endLabel)
        // STACK: exception
        visitLabel(handlerExceptionStartLabel)
        processMethodCallException(owner, name, ownerLocal)
        visitLabel(endLabel)
        // STACK: result?
    }

    private fun processMethodCallEnter(desc: String, owner: String, name: String, ownerLocal: Int?,
                                       argumentLocals: IntArray) = adapter.run {
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] : <empty>
        pushOwnerInstance(ownerLocal)
        push(owner)
        push(name)
        loadNewCodeLocationId()
        // STACK [INVOKEVIRTUAL]: owner  , owner, className, methodName, codeLocation
        // STACK [INVOKESTATIC] : <empty>, null, className, methodName, codeLocation
        adapter.push(MethodIds.getMethodId(owner, name, desc))
        // STACK [INVOKEVIRTUAL]: owner  , owner, className, methodName, codeLocation, methodId
        // STACK [INVOKESTATIC] : <empty>, null, className, methodName, codeLocation, methodId
        pushArray(argumentLocals)
        // STACK: ..., argumentsArray
        invokeStatic(Injections::beforeMethodCall)
        invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
    }

    private fun processMethodCallReturn(desc: String, owner: String, name: String, ownerLocal: Int?) = adapter.run {
        // STACK: result?
        val resultType = Type.getReturnType(desc)
        if (resultType == VOID_TYPE) {
            // STACK: <empty>
            pushOwnerInstance(ownerLocal)
            push(owner)
            push(name)
            // STACK: owner?, className, methodName
            invokeStatic(Injections::onMethodCallReturnVoid)
            // STACK: <empty>
        } else {
            // STACK: result
            val resultLocal = newLocal(resultType)
            storeLocal(resultLocal)
            // STACK: <empty>
            pushOwnerInstance(ownerLocal)
            push(owner)
            push(name)
            // STACK: owner?, className, methodName
            loadLocal(resultLocal)
            box(resultType)
            // STACK: owner?, className, methodName, result
            invokeStatic(Injections::onMethodCallReturn)
            loadLocal(resultLocal)
            // STACK: result
        }
        // STACK: result?
    }

    private fun processMethodCallException(owner: String, name: String, ownerLocal: Int?) = adapter.run {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        pushOwnerInstance(ownerLocal)
        push(owner)
        push(name)
        loadLocal(exceptionLocal)
        // STACK: owner?, className, methodName, exception
        invokeStatic(Injections::onMethodCallException)
        loadLocal(exceptionLocal)
        throwException()
        // STACK: <empty>
    }

    private fun pushOwnerInstance(ownerLocal: Int?) = adapter.run {
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] : <empty>
        if (ownerLocal != null) {
            loadLocal(ownerLocal)
        } else {
            pushNull()
        }
        // STACK [INVOKEVIRTUAL]: owner  , owner
        // STACK [INVOKESTATIC] : <empty>, null
    }

    private fun isIgnoredMethod(className: String) =
        className.startsWith("sun/nio/ch/lincheck/") ||
        className.startsWith("org/jetbrains/kotlinx/lincheck/") ||
        className == "kotlin/jvm/internal/Intrinsics" ||
        className == "java/util/Objects" ||
        className == "java/lang/String" ||
        className == "java/lang/Boolean" ||
        className == "java/lang/Long" ||
        className == "java/lang/Integer" ||
        className == "java/lang/Short" ||
        className == "java/lang/Byte" ||
        className == "java/lang/Double" ||
        className == "java/lang/Float" ||
        className == "java/util/Locale" ||
        className == "org/slf4j/helpers/Util" ||
        className == "java/util/Properties"

    @Suppress("UNUSED_PARAMETER")
    private fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

}