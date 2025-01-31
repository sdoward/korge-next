// WARNING: File autogenerated DO NOT modify
// https://www.khronos.org/registry/OpenGL/api/GLES2/gl2.h
@file:Suppress("unused", "RedundantUnitReturnType", "PropertyName")

package com.soywiz.kgl

import com.soywiz.kmem.*
import kotlinx.cinterop.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.format.*
import platform.posix.*

@SharedImmutable val gllib = DynamicLibrary("libGL.so")
@SharedImmutable val glXGetProcAddress by gllib.func<(name: CPointer<ByteVar>) -> CPointer<out CPointed>?>()

internal actual fun glGetProcAddressAnyOrNull(name: String): COpaquePointer? = memScoped {
    glXGetProcAddress(name.cstr.placeTo(this)) ?: gllib.getSymbol(name)
}

actual class KmlGlNative actual constructor() : NativeBaseKmlGl() {
}
