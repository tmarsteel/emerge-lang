package io.github.tmarsteel.emerge.backend.llvm.dsl

import org.bytedeco.llvm.LLVM.LLVMContextRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMTargetDataRef
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM

interface LlvmContext {
    val ref: LLVMContextRef
    val module: LLVMModuleRef
    val targetData: LLVMTargetDataRef
    val rawPointer: LLVMTypeRef
    val opaquePointer: LlvmPointerType<LlvmVoidType>
    val globalsScope: NameScope

    fun <T : LlvmType> nullValue(type: T): LlvmConstant<T> = LlvmConstant(
        LLVM.LLVMConstNull(type.getRawInContext(this)),
        type,
    )

    fun <T : LlvmType> undefValue(type: T): LlvmConstant<T> = LlvmConstant(
        LLVM.LLVMGetUndef(type.getRawInContext(this)),
        type,
    )

    fun addModuleInitFunction(initializer: LlvmFunction<LlvmVoidType>)

    fun <T : LlvmType> addGlobal(initialValue: LlvmConstant<T>, mode: LlvmGlobal.ThreadLocalMode): LlvmGlobal<T> {
        val rawRef = LLVM.LLVMAddGlobal(module, initialValue.type.getRawInContext(this), globalsScope.next())
        val allocation = LlvmGlobal(rawRef, initialValue.type)
        LLVM.LLVMSetThreadLocalMode(rawRef, mode.llvmKindValue)
        LLVM.LLVMSetInitializer(rawRef, initialValue.raw)
        return allocation
    }

    /**
     * Must be called at least once before using the LLVM IR (e.g. through LLVM.LLVMPrintModuleToFile)
     */
    fun complete()

    companion object {
        fun createDoAndDispose(targetTriple: String, action: (LlvmContext) -> Unit) {
            return LlvmContextImpl(targetTriple).use(action)
        }
    }
}

private class LlvmContextImpl(val targetTriple: String) : LlvmContext, AutoCloseable {
    override val ref = LLVM.LLVMContextCreate()
    override val module = LLVM.LLVMModuleCreateWithName("app")
    init {
        LLVM.LLVMSetTarget(module, targetTriple)
    }
    override val targetData = LLVM.LLVMGetModuleDataLayout(module)
    override val rawPointer = LLVM.LLVMPointerTypeInContext(ref, 0)
    override val opaquePointer: LlvmPointerType<LlvmVoidType> = LlvmPointerType(LlvmVoidType)
    override val globalsScope = NameScope("global")

    private val initializerFunctions = ArrayList<LlvmFunction<LlvmVoidType>>()
    override fun addModuleInitFunction(initializer: LlvmFunction<LlvmVoidType>) {
        initializerFunctions.add(initializer)
    }
    override fun complete() {
        val arrayType = LlvmArrayType(initializerFunctions.size.toLong(), LlvmGlobalCtorEntry)
        val ctorsGlobal = LLVM.LLVMAddGlobal(module, arrayType.getRawInContext(this), "llvm.global_ctors")
        val ctorsData = arrayType.buildConstantIn(this, initializerFunctions.mapIndexed { initializerIndex, initializer ->
            LlvmGlobalCtorEntry.buildConstantIn(this) {
                setValue(LlvmGlobalCtorEntry.priority, i32(initializerIndex))
                setValue(LlvmGlobalCtorEntry.function, initializer.address)
                setNull(LlvmGlobalCtorEntry.data)
            }
        })
        LLVM.LLVMSetInitializer(ctorsGlobal, ctorsData.raw)
        LLVM.LLVMSetLinkage(ctorsGlobal, LLVM.LLVMAppendingLinkage)
    }

    override fun close() {
        LLVM.LLVMDisposeModule(module)
        LLVM.LLVMContextDispose(ref)
    }
}