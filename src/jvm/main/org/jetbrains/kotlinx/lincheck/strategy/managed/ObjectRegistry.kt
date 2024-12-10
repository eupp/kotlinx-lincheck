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

import org.jetbrains.kotlinx.lincheck.util.updateInplace
import java.lang.ref.WeakReference

class ObjectEntry(
    val objNumber: Int,
    val objHashCode: Int,
    val objReference: WeakReference<Any>,
)

val ObjectEntry.objId: ObjectID get() =
    (objNumber.toLong() shl 32) + objHashCode.toLong()

fun ObjectID.getObjectNumber(): Int =
    (this ushr 32).toInt()

fun ObjectID.getObjectHashCode(): Int =
    this.toInt()


class ObjectRegistry {

    private var objectCounter = 0

    private val objectIndex = HashMap<IdentityHashCode, MutableList<ObjectEntry>>()

    // capacity is used to trigger garbage collection of `objectIndex`
    private var objectIndexCapacity = INITIAL_OBJECT_INDEX_CAPACITY

    fun register(obj: Any?): ObjectID {
        if (objectIndex.size >= objectIndexCapacity) {
            garbageCollection()
        }
        // TODO: disallow null object?
        // TODO: should we encode and handle static object?
        // TODO: what about primitives and other immutable types?
        val entry = ObjectEntry(
            objNumber = ++objectCounter,
            // TODO: should use primitiveOrIdentityHashCode instead?
            objHashCode = System.identityHashCode(obj),
            objReference = WeakReference(obj),
        )
        objectIndex.updateInplace(entry.objHashCode, default = mutableListOf()) {
            cleanup()
            add(entry)
        }
        return entry.objId
    }

    operator fun get(id: ObjectID): ObjectEntry? {
        val objNumber = id.getObjectNumber()
        val objHashCode = id.getObjectHashCode()
        val entries = objectIndex[objHashCode] ?: return null
        entries.cleanup()
        return entries.find { it.objNumber == objNumber }
    }

    operator fun get(obj: Any): ObjectEntry? {
        val objHashCode = System.identityHashCode(obj)
        val entries = objectIndex[objHashCode] ?: return null
        entries.cleanup()
        return entries.find { it.objReference.get() === obj }
    }

    fun retain(predicate: (ObjectEntry) -> Boolean) {
        objectIndex.values.retainAll { entries ->
            entries.retainAll(predicate)
            entries.isNotEmpty()
        }
    }

    /**
     * Performs garbage collection for the object registry by
     * removing from the index entries that are associated with garbage-collected objects.
     */
    private fun garbageCollection() {
        // remove entries corresponding to garbage-collected objects
        retain { it.objReference.get() != null }
        // decrease capacity if the index size is too low
        if (objectIndex.size < objectIndexCapacity / 4) {
            objectIndexCapacity /= 2
        }
        // increase capacity if the index size is too large
        if (objectIndex.size > objectIndexCapacity / 2) {
            objectIndexCapacity *= 2
        }
    }

    /**
     * Cleans up the current list of `ObjectEntry` instances
     * by removing entries that reference garbage-collected objects.
     */
    private fun MutableList<ObjectEntry>.cleanup() {
        retainAll { it.objReference.get() != null }
    }

}

private typealias IdentityHashCode = Int

private const val INITIAL_OBJECT_INDEX_CAPACITY = 1024