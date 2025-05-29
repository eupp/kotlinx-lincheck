/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.annotations;

import org.jetbrains.kotlinx.lincheck.util.LoggingLevel;

import java.lang.annotation.*;

/**
 * This annotation should be added to a test class to specify the logging level.
 * By default, {@link LoggingLevel#WARN} is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface LogLevel {
    LoggingLevel value();
}
