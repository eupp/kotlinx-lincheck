/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.*
import kotlin.coroutines.Continuation
import java.util.*

/**
 * Helps to assign number to an object and to create its beautiful representation to provide to the trace.
 */
object ObjectLabelFactory {

    private val objectNumeration = Collections.synchronizedMap(WeakHashMap<Class<Any>, MutableMap<Any, Int>>())

    internal fun adornedStringRepresentation(obj: Any?): String = when {
        // null is displayed as is
        obj == null -> "null"

        // chars and strings are wrapped in quotes.
        obj is Char   -> "\'$obj\'"
        obj is String -> "\"$obj\""

        // immutable types (including primitive types) have trivial `toString` implementation
        obj.isImmutable -> obj.toString()

        // for enum types, we display their name
        obj is Enum<*>  -> obj.name

        // simplified representation for continuations
        // (we usually do not really care about details).
        obj is Continuation<*> -> "<cont>"

        // special representation for anonymous classes
        obj.javaClass.isAnonymousClass -> obj.javaClass.simpleNameForAnonymous

        // finally, all other objects are represented as `className#objectNumber`
        else -> objectName(obj) + "#" + getObjectNumber(obj.javaClass, obj)
    }

    internal fun getObjectNumber(clazz: Class<Any>, obj: Any): Int = objectNumeration
        .computeIfAbsent(clazz) { IdentityHashMap() }
        .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

    internal fun cleanObjectNumeration() {
        objectNumeration.clear()
    }

    private val Class<*>.simpleNameForAnonymous: String
        get() {
            // Split by the package separator and return the result if this is not an inner class.
            val withoutPackage = name.substringAfterLast('.')
            if (!withoutPackage.contains("$")) return withoutPackage
            // Extract the last named inner class followed by any "$<number>" patterns using regex.
            val regex = """(.*\$)?([^\$.\d]+(\$\d+)*)""".toRegex()
            val matchResult = regex.matchEntire(withoutPackage)
            return matchResult?.groups?.get(2)?.value ?: withoutPackage
        }

    private fun objectName(obj: Any): String {
        return when (obj) {
            is IntArray -> "IntArray"
            is ShortArray -> "ShortArray"
            is CharArray -> "CharArray"
            is ByteArray -> "ByteArray"
            is BooleanArray -> "BooleanArray"
            is DoubleArray -> "DoubleArray"
            is FloatArray -> "FloatArray"
            is LongArray -> "LongArray"
            is Array<*> -> "Array<${obj.javaClass.componentType.simpleName}>"
            else -> obj.javaClass.simpleName
        }
    }

}