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

import org.objectweb.asm.Type


typealias ValueID = Long
typealias ObjectID = Long

enum class ValueType {
    BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, OBJECT;
}

internal const val NULL_OBJECT_ID = 0L
internal const val STATIC_OBJECT_ID = -1L
internal const val INVALID_OBJECT_ID = -2L

/**
 * Special auxiliary object used as an owner of static fields (instead of `null`).
 */
internal object StaticObject : Any()

