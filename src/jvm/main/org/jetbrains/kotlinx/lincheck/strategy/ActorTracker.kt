/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.util.*

typealias ActorId = Int

interface ActorTracker {

    fun onActorStart(threadId: ThreadId, actorId: ActorId, actor: Actor)

    fun onActorReturn(threadId: ThreadId, actorId: ActorId, actor: Actor, result: Any?)

    fun onActorThrow(threadId: ThreadId, actorId: ActorId, actor: Actor, throwable: Throwable)

    fun onActorSuspension(threadId: ThreadId, actorId: ActorId, actor: Actor)

    fun onActorResumption(threadId: ThreadId, actorId: ActorId, actor: Actor)

    fun onActorCancellation(threadId: ThreadId, actorId: ActorId, actor: Actor)

}