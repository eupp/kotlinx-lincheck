/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectTracker
import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumber
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.coroutines.Continuation

/**
 * Traverses an object to enumerate it and all nested objects.
 * Enumeration is required for the Plugin as we want to see on the diagram if some object was replaced by a new one.
 * Uses the same a numeration map as TraceReporter via [getObjectNumber] method, so objects have the
 * same numbers, as they have in the trace.
 */
internal fun ObjectTracker.enumerateObjects(obj: Any): Map<Any, Int> {
    val objectNumberMap = hashMapOf<Any, Int>()
    enumerateObjects(obj, objectNumberMap)
    return objectNumberMap
}

/**
 * Recursively traverses an object to enumerate it and all nested objects.
 *
 * @param root object to traverse
 * @param objectNumberMap result enumeration map
 */
private fun ObjectTracker.enumerateObjects(root: Any, objectNumberMap: MutableMap<Any, Int>) {
    traverseObjectGraph(root,
        config = ObjectGraphTraversalConfig(
            traverseEnumObjects = false,
            promoteAtomicObjects = true,
        ),
        onObject = { obj ->
            objectNumberMap[obj] = getObjectNumber(obj)
            shouldAnalyseObjectRecursively(obj)
        },
    )
}

/**
 * Determines should we dig recursively into this object's fields.
 */
private fun ObjectTracker.shouldAnalyseObjectRecursively(obj: Any): Boolean {
    if (obj is CharSequence) {
        return false
    }
    if (obj is Continuation<*>) {
        return false
    }
    return true
}
