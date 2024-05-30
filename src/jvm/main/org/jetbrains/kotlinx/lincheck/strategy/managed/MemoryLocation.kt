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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterInfo
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.util.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import java.lang.invoke.VarHandle
import java.lang.reflect.*
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.reflect.KClass
import java.lang.reflect.Array as ReflectArray


typealias ValueMapper = (Type, ValueID) -> OpaqueValue?

interface MemoryLocation {
    val objID: ObjectID

    // TODO: decide if we really want to expose ASM Type here,
    //   or we should use some other type:
    //   - kClass (there is a problem with boxed and primitive types being represented by the same kClass)
    //   - custom enum class (?)
    val type: Type

    fun read(valueMapper: ValueMapper): Any?
    fun write(value: Any?, valueMapper: ValueMapper)
}

val MemoryLocation.kClass: KClass<*>
    get() = type.getKClass()

fun ObjectTracker.getFieldAccessMemoryLocation(obj: Any?, className: String, fieldName: String, type: Type,
                                               isStatic: Boolean, isFinal: Boolean): MemoryLocation {
    if (isStatic) {
        return StaticFieldMemoryLocation(className.canonicalClassName, fieldName, type)
    }
    val clazz = obj!!.javaClass
    val id = getObjectId(obj)
    return ObjectFieldMemoryLocation(clazz, id, className.canonicalClassName, fieldName, type)
}

fun ObjectTracker.getArrayAccessMemoryLocation(array: Any, index: Int, type: Type): MemoryLocation {
    val clazz = array.javaClass
    val id = getObjectId(array)
    return ArrayElementMemoryLocation(clazz, id, index, type)
}

fun ObjectTracker.getAtomicAccessMemoryLocation(reflection: Any?, params: Array<Any?>): MemoryLocation? {
    var obj: Any? = null
    var isArrayAccess = false
    var className = ""
    var fieldName = ""
    var index = -1
    var type = OBJECT_TYPE
    when {
         reflection is AtomicReferenceFieldUpdater<*, *> -> {
             val info = getAtomicFieldUpdaterInfo(reflection)!!
             obj = params[0]
             className = info.className
             fieldName = info.fieldName
             type = OBJECT_TYPE
        }

        reflection is AtomicIntegerFieldUpdater<*> -> {
            val info = getAtomicFieldUpdaterInfo(reflection)!!
            obj = params[0]
            className = info.className
            fieldName = info.fieldName
            type = Type.INT_TYPE
        }

        reflection is AtomicLongFieldUpdater<*> -> {
            val info = getAtomicFieldUpdaterInfo(reflection)!!
            obj = params[0]
            className = info.className
            fieldName = info.fieldName
            type = Type.LONG_TYPE
        }

        reflection is VarHandle -> {
            val info = VarHandleNames.varHandleMethodType(reflection, params)
            check(info !is VarHandleMethodType.TreatAsDefaultMethod)
            isArrayAccess = (info is VarHandleMethodType.ArrayVarHandleMethod)
            obj = info.instance
            className = info.className!!
            fieldName = info.fieldName.orEmpty()
            index = info.index
        }

        isUnsafe(reflection) -> {
            val info = UnsafeNames.getMethodCallType(params)
            check(info !is UnsafeName.TreatAsDefaultMethod)
            isArrayAccess = (info is UnsafeName.UnsafeArrayMethod)
            obj = info.instance
            className = info.className!!
            fieldName = info.fieldName.orEmpty()
            index = info.index
        }

        else -> return null
    }
    return if (isArrayAccess)
        getArrayAccessMemoryLocation(obj!!, index, type)
    else
        getFieldAccessMemoryLocation(obj, className, fieldName, type,
            isStatic = (obj == null),
            isFinal = false, // TODO: fixme?
        )
}

class StaticFieldMemoryLocation(
    val className: String,
    val fieldName: String,
    override val type: Type,
) : MemoryLocation {

    override val objID: ObjectID = STATIC_OBJECT_ID

    private val field: Field by lazy {
        resolveClass(className = className)
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return field.get(null)
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        field.set(null, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is StaticFieldMemoryLocation)
                && (className == other.className)
                && (fieldName == other.fieldName)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String =
        "$className::$fieldName"

}

class ObjectFieldMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    val className: String,
    val fieldName: String,
    override val type: Type,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val simpleClassName: String = clazz.simpleName

    private val field: Field by lazy {
        val resolvedClass = resolveClass(clazz, className = className)
        resolveField(resolvedClass, className, fieldName)
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return field.get(valueMapper(OBJECT_TYPE, objID)?.unwrap())
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        field.set(valueMapper(OBJECT_TYPE, objID)?.unwrap(), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ObjectFieldMemoryLocation)
                && (objID == other.objID)
                && (className == other.className)
                && (fieldName == other.fieldName)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String {
        return "${objRepr(simpleClassName, objID)}::$fieldName"
    }

}

class ArrayElementMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    val index: Int,
    override val type: Type,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val isPlainArray = clazz.isArray

    private val getMethod: Method? by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use getOpaque() for atomic arrays here?
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use setOpaque() for atomic arrays here?
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        if (isPlainArray) {
            return ReflectArray.get(valueMapper(OBJECT_TYPE, objID)?.unwrap(), index)
        }
        return getMethod!!.invoke(valueMapper(OBJECT_TYPE, objID)?.unwrap(), index)
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        if (isPlainArray) {
            ReflectArray.set(valueMapper(OBJECT_TYPE, objID)?.unwrap(), index, value)
            return
        }
        setMethod!!.invoke(valueMapper(OBJECT_TYPE, objID)?.unwrap(), index, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ArrayElementMemoryLocation)
                && (objID == other.objID)
                && (index == other.index)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + index
        return result
    }

    override fun toString(): String {
        return "${objRepr(className, objID)}[$index]"
    }

}

class AtomicPrimitiveMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    override val type: Type,
) : MemoryLocation {

    init {
        require(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val getMethod by lazy {
        // TODO: can we use getOpaque() here?
        resolveClass(clazz).methods
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() here?
        resolveClass(clazz).methods
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return getMethod.invoke(valueMapper(OBJECT_TYPE, objID)?.unwrap())
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        setMethod.invoke(valueMapper(OBJECT_TYPE, objID)?.unwrap(), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is AtomicPrimitiveMemoryLocation)
                && (objID == other.objID)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        return objID.hashCode()
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return objRepr(className, objID)
    }

}

internal fun objRepr(className: String, objID: ObjectID): String {
    return when (objID) {
        NULL_OBJECT_ID -> "null"
        else -> "$className@$objID"
    }
}

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)

private fun resolveClass(clazz: Class<*>? = null, className: String? = null): Class<*> {
    if (className == null) {
        check(clazz != null)
        return clazz
    }
    if (clazz == null) {
        return Class.forName(className)
    }
    if (matchClassName(clazz, className)) {
        return clazz
    }
    var superClass = clazz.superclass
    while (superClass != null) {
        if (matchClassName(superClass, className))
            return superClass
        superClass = superClass.superclass
    }
    throw IllegalStateException("Cannot find class $className for object of class ${clazz.name}!")
}

private fun resolveField(clazz: Class<*>, className: String, fieldName: String): Field {
    var currentClass: Class<*>? = clazz
    do {
        currentClass?.fields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass?.declaredFields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass = currentClass?.superclass
    } while (currentClass != null)
    throw IllegalStateException("Cannot find field $className::$fieldName for class $clazz!")
}