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
 * Tracks objects and changes in object graph topology.
 *
 * The object tracker allows registering objects within the managed strategy,
 * assigning to each object a unique serial number and unique id.
 *
 * The unique serial number is a 32-bit integer number.
 * Assuming the deterministic execution, the serial numbers are guaranteed to be generated in the same order,
 * providing a persistent objects numeration between different re-runs of the same program execution.
 *
 * The unique object id is a 64-bit integer number, constructed from
 * the object's serial number and its identity hash code.
 *
 * The registered objects are associated with the registry entries [ObjectEntry],
 * keeping object's serial number, its identity hash code, a weak reference to the object,
 * and, potentially, other meta-data (defined by the concrete implementations of the interface).
 * The tracker allows retrieving the registry entry either by the object reference itself
 * or by unique object id.
 */
interface ObjectTracker {

    /**
     * Retrieves the registry entry associated with the given object id.
     *
     * @param id the object id to retrieve the corresponding entry for.
     * @return the corresponding [ObjectEntry],
     *   or null if no entry is associated with the given object id.
     */
    operator fun get(id: ObjectID): ObjectEntry?

    /**
     * Retrieves the registry entry associated with the given object.
     *
     * @param obj the object to retrieve the corresponding entry for.
     * @return the corresponding [ObjectEntry],
     *   or null if no entry is associated with the given object.
     */
    operator fun get(obj: Any): ObjectEntry?

    /**
     * Registers a newly created object in the object tracker.
     *
     * @param obj the object to be registered.
     * @return the registry entry for the new object.
     */
    fun registerNewObject(obj: Any): ObjectEntry

    /**
     * Registers an externally created object in the object tracker.
     * An external object is an object created outside the analyzed code,
     * but that nonetheless leaked into the analyzed code and thus needs to be tracked as well.
     *
     * @param obj the external object to be registered.
     * @return the registered object entry for the external object.
     */
    fun registerExternalObject(obj: Any): ObjectEntry

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

    /**
     * Retains only the entries in the object tracker that match the given predicate.
     *
     * @param predicate a condition that determines which entries should be retained.
     */
    fun retain(predicate: (ObjectEntry) -> Boolean)

    /**
     * Resets the state of the object tracker, removing all registered object entries.
     */
    fun reset()
}

/**
 * Registers an object in the tracker if it is not already present.
 * If the object is already registered, returns its corresponding entry.
 *
 * @param obj the object to be registered if absent.
 * @return the registered entry associated with the given object.
 */
fun ObjectTracker.registerObjectIfAbsent(obj: Any): ObjectEntry =
    get(obj) ?: registerExternalObject(obj)


/**
 * Represents an entry for the tracked object.
 *
 * @property objNumber A unique serial number for the object.
 * @property objHashCode The identity hash code of the object.
 * @property objReference A weak reference to the associated object.
 */
open class ObjectEntry(
    val objNumber: Int,
    val objHashCode: Int,
    val objReference: WeakReference<Any>,
)

/**
 * A unique identifier of an object.
 *
 * The identifier is a 64-bit integer number, formed by combining its serial number and hash code.
 * The serial number [ObjectEntry.objNumber] is stored in the higher 32-bits of the id, while
 * the identity hash code [ObjectEntry.objHashCode] is stored in the lower 32-bits.
 */
val ObjectEntry.objId: ObjectID get() =
    (objNumber.toLong() shl 32) + objHashCode.toLong()

/**
 * Extracts and returns the object number from the given object id.
 */
fun ObjectID.getObjectNumber(): Int =
    (this ushr 32).toInt()

/**
 * Extracts and returns the identity hash code of the object from the given object id.
 *
 * @return The integer hash code derived from the ObjectID.
 */
fun ObjectID.getObjectHashCode(): Int =
    this.toInt()

/**
 * Retrieves the unique serial object number for the given object.
 *
 * @param obj the object for which the object number is to be retrieved.
 * @return the unique object number if the object is registered in the tracker,
 *   or -1 if no entry is associated with the given object.
 */
fun ObjectTracker.getObjectNumber(obj: Any): Int =
    get(obj)?.objNumber ?: -1

/**
 * Generates a string representation of an object.
 *
 * Provides an adorned string representation for null values, primitive types, strings, enums,
 * and some other types of objects.
 * All other objects are represented in the format `className#objectNumber`.
 *
 * @param obj the object to generate a textual representation for.
 * @return a string representation of the specified object.
 */
fun ObjectTracker.getObjectRepresentation(obj: Any?) = when {
    // null is displayed as is
    obj == null -> "null"

    // unit is displayed simply as "Unit"
    obj === Unit -> "Unit"

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
        val objectNumber = registerObjectIfAbsent(obj).objNumber
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

/**
 * Abstract base class for tracking objects.
 *
 * It provides an implementation for registering, retrieving, updating,
 * and managing objects and their entries in the registry.
 */
abstract class AbstractObjectTracker : ObjectTracker {

    // counter of all registered objects
    private var objectCounter = 0

    // index of all registered objects
    private val objectIndex = HashMap<IdentityHashCode, MutableList<ObjectEntry>>()

    // capacity is used to trigger garbage collection of `objectIndex`
    private var objectIndexCapacity = INITIAL_OBJECT_INDEX_CAPACITY

    override fun registerNewObject(obj: Any): ObjectEntry =
        registerObject(obj, ObjectKind.NEW)

    override fun registerExternalObject(obj: Any): ObjectEntry =
        registerObject(obj, ObjectKind.EXTERNAL)

    private fun registerObject(obj: Any, kind: ObjectKind): ObjectEntry {
        check(obj !== StaticObject)
        check(!obj.isImmutable)
        if (objectIndex.size >= objectIndexCapacity) {
            garbageCollection()
        }
        val entry = createObjectEntry(
            objNumber = ++objectCounter,
            objHashCode = System.identityHashCode(obj),
            objReference = WeakReference(obj),
            kind = kind,
        )
        objectIndex.updateInplace(entry.objHashCode, default = mutableListOf()) {
            cleanup()
            add(entry)
        }
        return entry
    }

    /**
     * Represents the kind of object being tracked.
     *
     * Can be either:
     *   - [NEW] - Newly created object registered in the tracker.
     *   - [EXTERNAL] - External object registered in the tracker.
     */
    protected enum class ObjectKind { NEW, EXTERNAL }

    /**
     * Method responsible for creating an instance of [ObjectEntry] for tracking an object in the system.
     * Derived classes may override this method to create their own customized instances of [ObjectEntry]
     * with additional meta-data.
     */
    protected open fun createObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objReference: WeakReference<Any>,
        kind: ObjectKind = ObjectKind.NEW,
    ): ObjectEntry {
        return ObjectEntry(objNumber, objHashCode, objReference)
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

    /*
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

    /*
     * Cleans up the current list of `ObjectEntry` instances
     * by removing entries that reference garbage-collected objects.
     */
    private fun MutableList<ObjectEntry>.cleanup() {
        retainAll { it.objReference.get() != null }
    }

}

private typealias IdentityHashCode = Int

private const val INITIAL_OBJECT_INDEX_CAPACITY = 1024
