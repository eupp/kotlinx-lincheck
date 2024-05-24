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

import org.jetbrains.kotlinx.lincheck.transformation.isAtomicFieldUpdater

internal data class AtomicMethodDescriptor(
    val kind: AtomicMethodKind,
)

internal enum class AtomicMethodKind {
    GET, SET,
    GET_AND_SET,
    COMPARE_AND_SET,
    WEAK_COMPARE_AND_SET,
    GET_AND_ADD, ADD_AND_GET,
    GET_AND_INCREMENT, INCREMENT_AND_GET,
    GET_AND_DECREMENT, DECREMENT_AND_GET;

    companion object {
        fun fromName(name: String): AtomicMethodKind? = when (name) {
            "get"               -> GET
            "set"               -> SET
            "getAndSet"         -> GET_AND_SET
            "compareAndSet"     -> COMPARE_AND_SET
            "weakCompareAndSet" -> WEAK_COMPARE_AND_SET
            "getAndAdd"         -> GET_AND_ADD
            "addAndGet"         -> ADD_AND_GET
            "getAndIncrement"   -> GET_AND_INCREMENT
            "incrementAndGet"   -> INCREMENT_AND_GET
            "getAndDecrement"   -> GET_AND_DECREMENT
            "decrementAndGet"   -> DECREMENT_AND_GET
            else                -> null
        }
    }
}

internal fun getAtomicMethodDescriptor(className: String, methodName: String): AtomicMethodDescriptor? {
    when {
        isAtomicFieldUpdater(className) -> {
            val kind = AtomicMethodKind.fromName(methodName) ?: return null
            return AtomicMethodDescriptor(kind)
        }
    }
    return null
}