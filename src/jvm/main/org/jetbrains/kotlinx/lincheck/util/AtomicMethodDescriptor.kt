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

internal fun isAtomicFieldUpdaterClass(className: String) =
    (className.startsWith("java/util/concurrent/atomic") && className.endsWith("FieldUpdater"))

internal fun isAtomicFieldUpdaterMethod(className: String, methodName: String) =
    isAtomicFieldUpdaterClass(className) && (methodName in atomicFieldUpdaterMethods)

internal fun isVarHandleClass(className: String) =
    (className == "java/lang/invoke/VarHandle")

internal fun isVarHandleMethod(className: String, methodName: String) =
    isVarHandleClass(className) && (methodName in varHandleMethods)

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

internal fun getAtomicMethodDescriptor(className: String, methodName: String): AtomicMethodDescriptor? {
    if (!isAtomicFieldUpdaterMethod(className, methodName) &&
        !isVarHandleMethod(className, methodName)) {
        return null
    }
    val kind = AtomicMethodKind.fromName(methodName) ?: unreachable()
    return AtomicMethodDescriptor(kind)
}