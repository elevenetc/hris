package org.jetbrains.hris.api.utils

import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.util.*

@Suppress("NOTHING_TO_INLINE")
inline fun Parameters.getLongOrFail(name: String): Long {
    return getOrFail(name).toLongOrNull() ?: throw MissingRequestParameterException(name)
}