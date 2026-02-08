/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import java.util.concurrent.ConcurrentHashMap

interface TreeFilter<T> {
    val tree: Tree<T>
    val filteredTree: Tree<T>

    val remapping: Map<Tree.Node<T>, Tree.Node<T>> // tree -> filteredTree

    val currentNode: Tree.Node<T>? // from tree

    fun process(): Sequence<Result<T>>

    sealed class Result<out T> {
        object Thunk : Result<Nothing>()
        class Found<T>(val node: Tree.Node<T>) : Result<T>()
    }
    // T <: U => Result<T> <: Result<U>
}


private class TreeFilterImpl<T>(
    tree: Tree<T>,
    val predicate: (Tree.Node<T>) -> Boolean,
) : TreeFilter<T> {
    override val filteredTree: Tree<T> = Tree(null)

    private val _remapping = ConcurrentHashMap<Tree.Node<T>, FilteredNode<T>>()
    override val remapping: Map<Tree.Node<T>, Tree.Node<T>> get() = _remapping

    private val cursor = TreeCursor.atRoot(tree)

    override val currentNode: Tree.Node<T>?
        get() = cursor?.currentNode

    private class FilteredNode<T>() : Tree.MutableNode<T> {

    }

    override fun process(): Sequence<TreeFilter.Result<T>> =
        cursor?.walkForward()?.map { node ->
            if (predicate(node)) TreeFilter.Result.Found(node) else TreeFilter.Result.Thunk
        } ?: emptySequence()

    private fun processNode(node: Tree.Node<T>) {

    }
}