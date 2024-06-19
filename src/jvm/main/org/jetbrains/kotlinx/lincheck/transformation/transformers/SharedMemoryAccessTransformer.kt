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

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.*

/**
 * [SharedMemoryAccessTransformer] tracks reads and writes to plain or volatile shared variables,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class SharedMemoryAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    private val interceptReadAccesses: Boolean = false,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    lateinit var analyzer: AnalyzerAdapter

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (isCoroutineInternalClass(owner) || isCoroutineStateMachineClass(owner)) {
            visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }
        when (opcode) {
            GETSTATIC -> {
                // STACK: <empty>
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: <empty>
                        pushNull()
                        push(owner)
                        push(fieldName)
                        push(desc)
                        loadNewCodeLocationId()
                        push(true) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: null, className, fieldName, typeDescriptor, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeReadField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read static field")
                            },
                            elseClause = {})
                        // STACK: <empty>
                        if (interceptReadAccesses) {
                            invokeStatic(Injections::interceptReadResult)
                            unbox(getType(desc))
                        } else {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        }
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            GETFIELD -> {
                // STACK: obj
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        push(owner)
                        push(fieldName)
                        push(desc)
                        loadNewCodeLocationId()
                        push(false) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: obj, obj, className, fieldName, typeDescriptor, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeReadField)
                        // STACK: obj, isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read field")
                            },
                            elseClause = {}
                        )
                        // STACK: obj
                        if (interceptReadAccesses) {
                            pop()
                            invokeStatic(Injections::interceptReadResult)
                            unbox(getType(desc))
                        } else {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        }
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            PUTSTATIC -> {
                // STACK: value
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        copyLocal(valueLocal)
                        // STACK: value
                        pushNull()
                        push(owner)
                        push(fieldName)
                        push(desc)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(true) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: value, null, className, fieldName, typeDescriptor, value, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write static field")
                            },
                            elseClause = {}
                        )
                        // STACK: value
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <empty>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            PUTFIELD -> {
                // STACK: obj, value
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        push(owner)
                        push(fieldName)
                        push(desc)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(false) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: obj, obj, className, fieldName, typeDescriptor, value, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write field")
                            },
                            elseClause = {}
                        )
                        // STACK: obj
                        loadLocal(valueLocal)
                        // STACK: obj, value
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <empty>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            else -> {
                // All opcodes are covered above. However, in case a new one is added, Lincheck should not fail.
                visitFieldInsn(opcode, owner, fieldName, desc)
            }
        }
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                invokeIfInTestingCode(
                    original = {
                        visitInsn(opcode)
                    },
                    code = {
                        // STACK: array, index
                        val arrayElementType = getArrayElementType(opcode)
                        // if the array element type is unknown, we cannot intercept the load
                        // (because the byte-code verification phase fails in such a case)
                        // TODO: add logging for such case?
                        val interceptArrayReadAccess = interceptReadAccesses && arrayElementType != null
                        // STACK: array, index
                        dup2()
                        // STACK: array, index, array, index
                        //
                        // in case if the array element type is unknown,
                        // pass void type to inform the strategy and avoid read-interception
                        push((arrayElementType ?: VOID_TYPE).descriptor)
                        loadNewCodeLocationId()
                        // STACK: array, index, array, index, typeDescriptor, codeLocation
                        invokeStatic(Injections::beforeReadArray)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read array")
                            },
                            elseClause = {}
                        )
                        // STACK: array, index
                        if (interceptArrayReadAccess) {
                            pop()
                            pop()
                            invokeStatic(Injections::interceptReadResult)
                            unbox(arrayElementType)
                        } else {
                            visitInsn(opcode)
                        }
                        // STACK: value
                        //
                        // in case if the array element type is unknown,
                        // pass object type since the read value is going to be boxed anyway
                        invokeAfterRead(arrayElementType ?: OBJECT_TYPE)
                        // STACK: value
                    }
                )
            }

            AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                invokeIfInTestingCode(
                    original = {
                        visitInsn(opcode)
                    },
                    code = {
                        // STACK: array, index, value
                        val arrayElementType = getArrayElementType(opcode) ?: OBJECT_TYPE
                        val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: array, index
                        dup2()
                        // STACK: array, index, array, index
                        push(arrayElementType.descriptor)
                        loadLocal(valueLocal)
                        box(arrayElementType)
                        loadNewCodeLocationId()
                        // STACK: array, index, array, index, typeDescriptor, value, codeLocation
                        invokeStatic(Injections::beforeWriteArray)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write array")
                            },
                            elseClause = {}
                        )
                        // STACK: array, index
                        loadLocal(valueLocal)
                        // STACK: array, index, value
                        visitInsn(opcode)
                        // STACK: <EMPTY>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            else -> {
                visitInsn(opcode)
            }
        }
    }

    private fun GeneratorAdapter.invokeAfterRead(valueType: Type) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        loadLocal(resultLocal)
        // STACK: value, value
        box(valueType)
        invokeStatic(Injections::afterRead)
        // STACK: value
    }

    private fun getArrayElementType(opcode: Int): Type? = when (opcode) {
        // Load
        AALOAD -> getArrayAccessTypeFromStack(2) // OBJECT_TYPE
        IALOAD -> INT_TYPE
        FALOAD -> FLOAT_TYPE
        BALOAD -> BOOLEAN_TYPE
        CALOAD -> CHAR_TYPE
        SALOAD -> SHORT_TYPE
        LALOAD -> LONG_TYPE
        DALOAD -> DOUBLE_TYPE
        // Store
        AASTORE -> getArrayAccessTypeFromStack(3) // OBJECT_TYPE
        IASTORE -> INT_TYPE
        FASTORE -> FLOAT_TYPE
        BASTORE -> BOOLEAN_TYPE
        CASTORE -> CHAR_TYPE
        SASTORE -> SHORT_TYPE
        LASTORE -> LONG_TYPE
        DASTORE -> DOUBLE_TYPE
        else -> throw IllegalStateException("Unexpected opcode: $opcode")
    }

   /*
    * Tries to obtain the type of array elements by inspecting the type of the array itself.
    * To do this, the method queries the analyzer to get the type of accessed array
    * which should lie on the stack.
    * If the analyzer does not know the type, then return null
    * (according to the ASM docs, this can happen, for example, when the visited instruction is unreachable).
    */
    private fun getArrayAccessTypeFromStack(position: Int): Type? {
        if (analyzer.stack == null) return null
        val arrayDesc = analyzer.stack[analyzer.stack.size - position]
        check(arrayDesc is String)
        val arrayType = getType(arrayDesc)
        check(arrayType.sort == ARRAY)
        check(arrayType.dimensions > 0)
        return getType(arrayDesc.substring(1))
    }
}