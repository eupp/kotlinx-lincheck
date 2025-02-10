/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import java.io.*

interface Runner : Closeable {
    /**
     * Runs the next invocation.
     */
    fun run(): InvocationResult

    /**
     * Closes the resources used in this runner.
     */
    override fun close() {}
}
