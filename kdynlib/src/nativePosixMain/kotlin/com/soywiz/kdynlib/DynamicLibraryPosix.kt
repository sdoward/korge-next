package com.soywiz.kdynlib

import kotlinx.cinterop.*
import platform.posix.RTLD_LAZY
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.dlclose

private val DEBUG_DYNAMIC_LIB = platform.posix.getenv("DEBUG_DYNAMIC_LIB")?.toKString() == "true"

public actual open class DynamicLibrary actual constructor(val name: String) : DynamicSymbolResolver {
    val handle = dlopen(name, RTLD_LAZY)
    init {
        if (DEBUG_DYNAMIC_LIB) println("Loaded '$name'...$handle")
        if (handle == null) println("Couldn't load '$name' library")
    }
    public actual val isAvailable get() = handle != null
    override fun getSymbol(name: String): VoidPtr? {
        if (DEBUG_DYNAMIC_LIB) println("Requesting ${this.name}.$name...")
        val out: VoidPtr? = if (handle == null) null else dlsym(handle, name)?.rawValue
        if (DEBUG_DYNAMIC_LIB) println("Got ${this.name}.$name...$out")
        return out
    }
    public actual fun close() {
        dlclose(handle)
    }
    override fun toString(): String = "${this::class.simpleName}($name, $handle)"
}
