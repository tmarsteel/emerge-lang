package io.github.tmarsteel.emerge.backend.llvm.dsl

import io.github.tmarsteel.emerge.backend.llvm.jna.Llvm

object LlvmBooleanType : LlvmFixedIntegerType(1)
object LlvmI8Type : LlvmFixedIntegerType(8)
object LlvmI16Type : LlvmFixedIntegerType(16)
object LlvmI32Type : LlvmFixedIntegerType(32)
object LlvmI64Type : LlvmFixedIntegerType(64)

fun LlvmContext.i1(value: Boolean): LlvmValue<LlvmBooleanType> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmBooleanType.getRawInContext(this), if (value) 1 else 0, 0),
        LlvmBooleanType,
    )
}

fun LlvmContext.i8(value: Byte): LlvmValue<LlvmI8Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI8Type.getRawInContext(this), value.toLong(), 0),
        LlvmI8Type
    )
}

fun LlvmContext.i8(value: UByte): LlvmValue<LlvmI8Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI8Type.getRawInContext(this), value.toLong(), 0),
        LlvmI8Type,
    )
}

fun LlvmContext.i16(value: Short): LlvmValue<LlvmI16Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI16Type.getRawInContext(this), value.toLong(), 0),
        LlvmI16Type
    )
}

fun LlvmContext.i16(value: UShort): LlvmValue<LlvmI16Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI16Type.getRawInContext(this), value.toLong(), 0),
        LlvmI16Type
    )
}

fun LlvmContext.i32(value: Int): LlvmValue<LlvmI32Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI32Type.getRawInContext(this), value.toLong(), 0),
        LlvmI32Type,
    )
}

fun LlvmContext.i32(value: UInt): LlvmValue<LlvmI32Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI32Type.getRawInContext(this), value.toLong(), 0),
        LlvmI32Type,
    )
}

fun LlvmContext.i64(value: Long): LlvmValue<LlvmI64Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI64Type.getRawInContext(this), value, 0),
        LlvmI64Type
    )
}

fun LlvmContext.i64(value: ULong): LlvmValue<LlvmI64Type> {
    return LlvmConstant(
        Llvm.LLVMConstInt(LlvmI64Type.getRawInContext(this), value.toLong(), 0),
        LlvmI64Type
    )
}