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
import java.lang.ref.WeakReference
import kotlin.coroutines.Continuation

/**
 * Tracks object allocations and changes in object graph topology.
 */
interface ObjectTracker {

    operator fun get(id: ObjectID): ObjectEntry?

    operator fun get(obj: Any): ObjectEntry?

    /**
     * Registers a newly created object in the object tracker.
     *
     * @param obj the object to be registered
     */
    fun registerNewObject(obj: Any): ObjectID

    fun registerObjectIfAbsent(obj: Any): ObjectID

    /**
     * This method is used to register a link between two objects in the object tracker.
     * The link is established from the object specified by the [fromObject] parameter
     * to the object specified by the [toObject] parameter.
     *
     * @param fromObject the object from which the link originates.
     * @param toObject the object to which the link points.
     */
    fun registerObjectLink(fromObject: Any, toObject: Any?)

    /**
     * Determines whether accesses to the fields of the given object should be tracked.
     *
     * @param obj the object to check for tracking.
     * @return true if the object's accesses should be tracked, false otherwise.
     */
    fun shouldTrackObjectAccess(obj: Any): Boolean

    fun retain(predicate: (ObjectEntry) -> Boolean)

    /**
     * Resets the state of the object tracker.
     */
    fun reset()
}

open class ObjectEntry(
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

fun ObjectTracker.getObjectNumber(obj: Any): Int =
    get(obj)?.objNumber ?: -1

fun ObjectTracker.getObjectRepresentation(obj: Any?) = when {
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
    obj.javaClass.isAnonymousClass -> obj.javaClass.anonymousClassSimpleName

    // finally, all other objects are represented as `className#objectNumber`
    else -> {
        val className = objectClassNameRepresentation(obj)
        val objectNumber = registerObjectIfAbsent(obj).getObjectNumber()
        "$className#$objectNumber"
    }
}

private val Class<*>.anonymousClassSimpleName: String get() {
    // Split by the package separator and return the result if this is not an inner class.
    val withoutPackage = name.substringAfterLast('.')
    if (!withoutPackage.contains("$")) return withoutPackage
    // Extract the last named inner class followed by any "$<number>" patterns using regex.
    val regex = """(.*\$)?([^\$.\d]+(\$\d+)*)""".toRegex()
    val matchResult = regex.matchEntire(withoutPackage)
    return matchResult?.groups?.get(2)?.value ?: withoutPackage
}

private fun objectClassNameRepresentation(obj: Any): String = when (obj) {
    is IntArray     -> "IntArray"
    is ShortArray   -> "ShortArray"
    is CharArray    -> "CharArray"
    is ByteArray    -> "ByteArray"
    is BooleanArray -> "BooleanArray"
    is DoubleArray  -> "DoubleArray"
    is FloatArray   -> "FloatArray"
    is LongArray    -> "LongArray"
    is Array<*>     -> "Array<${obj.javaClass.componentType.simpleName}>"
    else            -> obj.javaClass.simpleName
}

abstract class AbstractObjectTracker : ObjectTracker {

    // counter of all registered objects
    private var objectCounter = 0

    // index of all registered objects
    private val objectIndex = HashMap<IdentityHashCode, MutableList<ObjectEntry>>()

    // capacity is used to trigger garbage collection of `objectIndex`
    private var objectIndexCapacity = INITIAL_OBJECT_INDEX_CAPACITY

    override fun registerNewObject(obj: Any): ObjectID {
        check(obj !== StaticObject)
        // TODO: check object is not immutable
        if (objectIndex.size >= objectIndexCapacity) {
            garbageCollection()
        }
        val entry = createObjectEntry(
            objNumber = ++objectCounter,
            objHashCode = System.identityHashCode(obj),
            objReference = WeakReference(obj),
        )
        objectIndex.updateInplace(entry.objHashCode, default = mutableListOf()) {
            cleanup()
            add(entry)
        }
        return entry.objId
    }

    override fun registerObjectIfAbsent(obj: Any): ObjectID {
        check(obj !== StaticObject)
        // TODO: check object is not immutable
        return get(obj)?.objId ?: registerNewObject(obj)
    }

    override operator fun get(id: ObjectID): ObjectEntry? {
        val objNumber = id.getObjectNumber()
        val objHashCode = id.getObjectHashCode()
        val entries = objectIndex[objHashCode] ?: return null
        entries.cleanup()
        return entries.find { it.objNumber == objNumber }
    }

    override operator fun get(obj: Any): ObjectEntry? {
        val objHashCode = System.identityHashCode(obj)
        val entries = objectIndex[objHashCode] ?: return null
        entries.cleanup()
        return entries.find { it.objReference.get() === obj }
    }

    override fun retain(predicate: (ObjectEntry) -> Boolean) {
        objectIndex.values.retainAll { entries ->
            entries.retainAll(predicate)
            entries.isNotEmpty()
        }
    }

    override fun reset() {
        objectCounter = 0
        objectIndex.clear()
        objectIndexCapacity = INITIAL_OBJECT_INDEX_CAPACITY
    }

    protected open fun createObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objReference: WeakReference<Any>
    ): ObjectEntry {
        return ObjectEntry(objNumber, objHashCode, objReference)
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
