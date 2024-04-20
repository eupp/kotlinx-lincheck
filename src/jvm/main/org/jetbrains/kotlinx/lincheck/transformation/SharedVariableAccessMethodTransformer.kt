/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.transformation.CoroutineInternalCallTracker.isCoroutineInternalClass
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*

/**
 * [SharedVariableAccessMethodTransformer] tracks reads and writes to plain or volatile shared variables,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class SharedVariableAccessMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    lateinit var analyzer: AnalyzerAdapter

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (isCoroutineInternalClass(owner) || isCoroutineStateMachineClass(owner)) {
            visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }
        if (FinalFields.isFinalField(owner, fieldName)) {
            if (opcode == GETSTATIC) {
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: <empty>
                        push(owner)
                        // STACK: className: String, fieldName: String, codeLocation: Int
                        invokeStatic(Injections::beforeReadFinalFieldStatic)
                        // STACK: owner: Object
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                    }
                )
                return
            } else {
                visitFieldInsn(opcode, owner, fieldName, desc)
                return
            }
        }
        when (opcode) {
            GETSTATIC -> {
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: <empty>
                        push(owner)
                        push(fieldName)
                        loadNewCodeLocationId()
                        // STACK: className: String, fieldName: String, codeLocation: Int
                        invokeStatic(Injections::beforeReadFieldStatic)
                        invokeBeforeEventIfPluginEnabled("read static field")
                        // STACK: owner: Object
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            GETFIELD -> {
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: owner: Object
                        dup()
                        // STACK: owner: Object, owner: Object
                        push(owner)
                        push(fieldName)
                        loadNewCodeLocationId()
                        // STACK: owner: Object, owner: Object, className: String, fieldName: String, codeLocation: Int
                        invokeStatic(Injections::beforeReadField)
                        ifStatement(condition = { /* already on stack */ }, ifClause = {
                            invokeBeforeEventIfPluginEnabled("read field")
                        }, elseClause = {})
                        // STACK: owner: Object
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            PUTSTATIC -> {
                // STACK: value: Object
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        storeTopToLocal(valueLocal)
                        // STACK: value: Object
                        push(owner)
                        push(fieldName)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        // STACK: value: Object, className: String, fieldName: String, value: Object, codeLocation: Int
                        invokeStatic(Injections::beforeWriteFieldStatic)
                        invokeBeforeEventIfPluginEnabled("write static field")
                        // STACK: value: Object
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <EMPTY>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            PUTFIELD -> {
                // STACK: owner: Object, value: Object
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: owner: Object
                        dup()
                        // STACK: owner: Object, owner: Object
                        push(className)
                        push(fieldName)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        // STACK: owner: Object, owner: Object, fieldName: String, fieldName: String, value: Object, codeLocation: Int
                        invokeStatic(Injections::beforeWriteField)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write field")
                            },
                            elseClause = {}
                        )
                        // STACK: owner: Object
                        loadLocal(valueLocal)
                        // STACK: owner: Object, value: Object
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <EMPTY>
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
                        // STACK: array: Array, index: Int
                        val arrayElementType = getArrayElementType(opcode)
                        dup2()
                        // STACK: array: Array, index: Int, array: Array, index: Int
                        loadNewCodeLocationId()
                        // STACK: array: Array, index: Int, array: Array, index: Int, codeLocation: Int
                        invokeStatic(Injections::beforeReadArray)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read array")
                            },
                            elseClause = {}
                        )
                        // STACK: array: Array, index: Int
                        visitInsn(opcode)
                        // STACK: value
                        invokeAfterRead(arrayElementType)
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
                        // STACK: array: Array, index: Int, value: Object
                        val arrayElementType = getArrayElementType(opcode)
                        val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: array: Array, index: Int
                        dup2()
                        // STACK: array: Array, index: Int, array: Array, index: Int
                        loadLocal(valueLocal)
                        box(arrayElementType)
                        loadNewCodeLocationId()
                        // STACK: array: Array, index: Int, array: Array, index: Int, value: Object, codeLocation: Int
                        invokeStatic(Injections::beforeWriteArray)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write array")
                            },
                            elseClause = {}
                        )
                        // STACK: array: Array, index: Int
                        loadLocal(valueLocal)
                        // STACK: array: Array, index: Int, value: Object
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
        storeTopToLocal(resultLocal)
        loadLocal(resultLocal)
        // STACK: value, value
        box(valueType)
        invokeStatic(Injections::afterRead)
        // STACK: value
    }

    private fun getArrayElementType(opcode: Int): Type = when (opcode) {
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
    private fun getArrayAccessTypeFromStack(position: Int): Type {
        if (analyzer.stack == null) return OBJECT_TYPE // better than throwing an exception
        val arrayDesc = analyzer.stack[analyzer.stack.size - position]
        check(arrayDesc is String)
        val arrayType = getType(arrayDesc)
        check(arrayType.sort == ARRAY)
        check(arrayType.dimensions > 0)
        return getType(arrayDesc.substring(1))
    }
}