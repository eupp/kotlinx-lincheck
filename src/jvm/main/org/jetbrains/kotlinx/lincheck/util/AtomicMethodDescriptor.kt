/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import java.util.concurrent.atomic.*

internal data class AtomicMethodDescriptor(
    val kind: AtomicMethodKind,
)

internal enum class AtomicMethodKind {
    GET, SET,
    GET_AND_SET,
    COMPARE_AND_SET,
    WEAK_COMPARE_AND_SET,
    COMPARE_AND_EXCHANGE,
    GET_AND_ADD, ADD_AND_GET,
    GET_AND_INCREMENT, INCREMENT_AND_GET,
    GET_AND_DECREMENT, DECREMENT_AND_GET;

    companion object {
        fun fromName(name: String): AtomicMethodKind? = when {
            "get"                in name -> GET
            "set"                in name -> SET
            "lazySet"            in name -> SET
            "put"                in name -> SET
            "getAndSet"          in name -> GET_AND_SET
            "compareAndSet"      in name -> COMPARE_AND_SET
            "weakCompareAndSet"  in name -> WEAK_COMPARE_AND_SET
            "compareAndExchange" in name -> COMPARE_AND_EXCHANGE
            "getAndAdd"          in name -> GET_AND_ADD
            "addAndGet"          in name -> ADD_AND_GET
            "getAndIncrement"    in name -> GET_AND_INCREMENT
            "incrementAndGet"    in name -> INCREMENT_AND_GET
            "getAndDecrement"    in name -> GET_AND_DECREMENT
            "decrementAndGet"    in name -> DECREMENT_AND_GET
            else                         -> null
        }
    }
}

internal fun getAtomicMethodDescriptor(className: String, methodName: String): AtomicMethodDescriptor? {
    if (!isAtomicFieldUpdaterMethod(className, methodName) &&
        !isVarHandleMethod(className, methodName) &&
        !isUnsafeMethod(className, methodName)) {
        return null
    }
    val kind = AtomicMethodKind.fromName(methodName) ?: unreachable()
    return AtomicMethodDescriptor(kind)
}

internal fun isAtomicFieldUpdaterClass(className: String) =
    (className.startsWith("java/util/concurrent/atomic") && className.endsWith("FieldUpdater"))

internal fun isAtomicFieldUpdaterMethod(className: String, methodName: String) =
    isAtomicFieldUpdaterClass(className) && (methodName in atomicFieldUpdaterMethods)

internal fun isVarHandleClass(className: String) =
    (className == "java/lang/invoke/VarHandle")

internal fun isVarHandleMethod(className: String, methodName: String) =
    isVarHandleClass(className) && (methodName in varHandleMethods)

internal fun isUnsafeClass(className: String) =
    className == "sun/misc/Unsafe" ||
    className == "jdk/internal/misc/Unsafe"

internal fun isUnsafeMethod(className: String, methodName: String) =
    isUnsafeClass(className) && (methodName in unsafeMethods)

internal fun isAtomicReference(receiver: Any?) =
    receiver is AtomicReference<*> ||
            receiver is AtomicLong ||
            receiver is AtomicInteger ||
            receiver is AtomicBoolean ||
            receiver is AtomicIntegerArray ||
            receiver is AtomicReferenceArray<*> ||
            receiver is AtomicLongArray

internal fun isUnsafe(receiver: Any?): Boolean {
    if (receiver == null) return false
    val className = receiver::class.java.name
    return className == "sun.misc.Unsafe" || className == "jdk.internal.misc.Unsafe"
}

private val atomicFieldUpdaterMethods = setOf(
    "get",
    "set", "lazySet",
    "getAndSet",
    "compareAndSet",
    "weakCompareAndSet",
    "getAndAdd", "addAndGet",
    "getAndIncrement", "incrementAndGet",
    "getAndDecrement", "decrementAndGet",
)

private val varHandleMethods = setOf(
    "get", "getVolatile", "getAcquire", "getOpaque",
    "set", "setVolatile", "setRelease", "setOpaque",
    "getAndSet", "getAndSetAcquire, getAndSetRelease",
    "compareAndSet",
    "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetAcquire", "weakCompareAndSetRelease",
    "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease",
    "getAndAdd", "getAndAddAcquire", "getAndAddRelease",
)

private val unsafeMethods: Set<String> = run {
    val typeNames = listOf(
        "Boolean", "Char", "Byte", "Short", "Int", "Long", "Float", "Double", "Reference", "Object"
    )
    val getAccessModes = listOf("", "Opaque", "Acquire", "Volatile")
    val putAccessModes = listOf("", "Opaque", "Release", "Volatile")
    val casAccessModes = listOf("", "Plain", "Acquire", "Release")
    val exchangeAccessModes = listOf("", "Acquire", "Release")
    val incrementAccessModes = listOf("", "Acquire", "Release")
    listOf(
        // get
        typeNames.flatMap { typeName -> getAccessModes.map { accessMode ->
            "get$typeName$accessMode"
        }},
        // put
        typeNames.flatMap { typeName -> putAccessModes.map { accessMode ->
            "put$typeName$accessMode"
        }},
        // getAndSet
        typeNames.flatMap { typeName -> exchangeAccessModes.map { accessMode ->
            "getAndSet$typeName$accessMode"
        }},
        // compareAndSet
        typeNames.map { typeName ->
            "compareAndSet$typeName"
        },
        // weakCompareAndSet
        typeNames.flatMap { typeName -> casAccessModes.map { accessMode ->
            "weakCompareAndSet$typeName$accessMode"
        }},
        // compareAndExchange
        typeNames.flatMap { typeName -> exchangeAccessModes.map { accessMode ->
            "compareAndExchange$typeName$accessMode"
        }},
        // getAndAdd
        typeNames
            .filter { it != "Reference" && it != "Object" }
            .flatMap { typeName -> incrementAccessModes.map { accessMode ->
                "getAndAdd$typeName$accessMode"
            }}
    ).flatten().toSet()
}