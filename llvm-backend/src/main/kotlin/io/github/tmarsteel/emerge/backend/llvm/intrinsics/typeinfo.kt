package io.github.tmarsteel.emerge.backend.llvm.intrinsics

import com.google.common.collect.MapMaker
import io.github.tmarsteel.emerge.backend.llvm.dsl.BasicBlockBuilder.Companion.retVoid
import io.github.tmarsteel.emerge.backend.llvm.dsl.GetElementPointerStep.Companion.index
import io.github.tmarsteel.emerge.backend.llvm.dsl.GetElementPointerStep.Companion.member
import io.github.tmarsteel.emerge.backend.llvm.dsl.KotlinLlvmFunction
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmArrayType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmConstant
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmContext
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunctionAddressType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmGlobal
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType.Companion.pointerTo
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmStructType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmVoidType
import io.github.tmarsteel.emerge.backend.llvm.dsl.buildConstantIn
import org.bytedeco.llvm.global.LLVM

internal val staticObjectFinalizer: KotlinLlvmFunction<LlvmContext, LlvmVoidType> = KotlinLlvmFunction.define(
    "emerge.platform.finalizeStaticObject",
    LlvmVoidType,
) {
    val self by param(PointerToAnyEmergeValue)
    body {
        // by definition a noop. Static values cannot be finalized. Erroring on static value finalization is not
        // possible because sharing static data across threads fucks up the reference counter (data races).
        retVoid()
    }
}

internal object TypeinfoType : LlvmStructType("typeinfo") {
    val shiftRightAmount by structMember(EmergeWordType)
    val supertypes by structMember(PointerToEmergeArrayOfPointersToTypeInfoType)
    val anyValueVirtuals by structMember(EmergeAnyValueVirtualsType)
    val vtableBlob by structMember(LlvmArrayType(0L, LlvmFunctionAddressType))
}

/**
 * Getter function for [EmergeArrayOfPointersToTypeInfoType]
 */
private val getter_EmergeArrayOfPointersToTypeInfoType: KotlinLlvmFunction<EmergeLlvmContext, LlvmPointerType<TypeinfoType>> = KotlinLlvmFunction.define(
    "emerge.platform.valueArrayOfPointersToTypeinfo_Get",
    pointerTo(TypeinfoType)
) {
    val self by param(PointerToEmergeArrayOfPointersToTypeInfoType)
    val index by param(EmergeWordType)
    body {
        // TODO: bounds check!
        val raw = getelementptr(self)
            .member { elements }
            .index(index)
            .get()
            .dereference()

        ret(raw)
    }
}

/**
 * Setter function for [EmergeArrayOfPointersToTypeInfoType]
 */
private val setter_EmergeArrayOfPointersToTypeInfoType: KotlinLlvmFunction<EmergeLlvmContext, LlvmVoidType> = KotlinLlvmFunction.define(
    "emerge.platform.valueArrayOfPointersToTypeinfo_Set",
    LlvmVoidType
) {
    val self by param(PointerToEmergeArrayOfPointersToTypeInfoType)
    val index by param(EmergeWordType)
    val value by param(pointerTo(TypeinfoType))
    body {
        // TODO: bounds check!
        val targetPointer = getelementptr(self)
            .member { elements }
            .index(index)
            .get()

        store(value, targetPointer)

        retVoid()
    }
}

internal val PointerToEmergeArrayOfPointersToTypeInfoType by lazy {
    pointerTo(
        EmergeArrayType(
            pointerTo(TypeinfoType),
            StaticAndDynamicTypeInfo.define(
                "valuearray_pointers_to_typeinfo",
                emptyList(),
                valueArrayFinalize
            ) {
                listOf(
                    word(EmergeArrayType.VIRTUAL_FUNCTION_HASH_GET_ELEMENT) to getter_EmergeArrayOfPointersToTypeInfoType,
                    word(EmergeArrayType.VIRTUAL_FUNCTION_HASH_SET_ELEMENT) to setter_EmergeArrayOfPointersToTypeInfoType,
                )
            },
            "pointer_to_typeinfo",
        )
    )
}

internal class StaticAndDynamicTypeInfo private constructor(
    val context: EmergeLlvmContext,
    val dynamic: LlvmGlobal<TypeinfoType>,
    val static: LlvmGlobal<TypeinfoType>,
) {
    interface Provider {
        fun provide(context: EmergeLlvmContext): StaticAndDynamicTypeInfo
    }

    private class ProviderImpl(
        val typeName: String,
        val supertypes: List<LlvmConstant<LlvmPointerType<TypeinfoType>>>,
        val finalizerFunction: KotlinLlvmFunction<EmergeLlvmContext, LlvmVoidType>,
        val virtualFunctions: EmergeLlvmContext.() -> List<Pair<LlvmConstant<EmergeWordType>, KotlinLlvmFunction<*, *>>>,
    ) : Provider {
        private val byContext: MutableMap<LlvmContext, StaticAndDynamicTypeInfo> = MapMaker().weakKeys().makeMap()
        override fun provide(context: EmergeLlvmContext): StaticAndDynamicTypeInfo {
            byContext[context]?.let { return it }
            val dynamicGlobal = context.addGlobal(context.undefValue(TypeinfoType), LlvmGlobal.ThreadLocalMode.SHARED)
            val staticGlobal = context.addGlobal(context.undefValue(TypeinfoType), LlvmGlobal.ThreadLocalMode.SHARED)
            val bundle = StaticAndDynamicTypeInfo(context, dynamicGlobal, staticGlobal)
            // register now to break loops
            byContext[context] = bundle

            val (dynamicConstant, staticConstant) = build(context)
            LLVM.LLVMSetInitializer(dynamicGlobal.raw, dynamicConstant.raw)
            LLVM.LLVMSetInitializer(staticGlobal.raw, staticConstant.raw)

            return bundle
        }

        private fun build(context: EmergeLlvmContext): Pair<LlvmConstant<TypeinfoType>, LlvmConstant<TypeinfoType>> {
            val dynamicSupertypesData = PointerToEmergeArrayOfPointersToTypeInfoType.pointed.buildConstantIn(context, supertypes, { it })
            val dynamicSupertypesGlobal = context.addGlobal(dynamicSupertypesData, LlvmGlobal.ThreadLocalMode.SHARED)
                .reinterpretAs(PointerToEmergeArrayOfPointersToTypeInfoType)

            val vtableBlob = TypeinfoType.vtableBlob.type.buildConstantIn(context, emptyList()) // TODO: build vtable
            val shiftRightAmount = context.word(0)// TODO: build vtable

            val typeinfoDynamicData = TypeinfoType.buildConstantIn(context) {
                setValue(TypeinfoType.shiftRightAmount, shiftRightAmount)
                setValue(TypeinfoType.supertypes, dynamicSupertypesGlobal)
                setValue(TypeinfoType.anyValueVirtuals, EmergeAnyValueVirtualsType.buildConstantIn(context) {
                    setValue(EmergeAnyValueVirtualsType.finalizeFunction, context.registerIntrinsic(finalizerFunction).address)
                })
                setValue(TypeinfoType.vtableBlob, vtableBlob)
            }

            val staticSupertypesData = PointerToEmergeArrayOfPointersToTypeInfoType.pointed.buildConstantIn(context, supertypes, { it })
            val staticSupertypesGlobal = context.addGlobal(staticSupertypesData, LlvmGlobal.ThreadLocalMode.SHARED)
                .reinterpretAs(PointerToEmergeArrayOfPointersToTypeInfoType)

            val typeinfoStaticData = TypeinfoType.buildConstantIn(context) {
                setValue(TypeinfoType.shiftRightAmount, shiftRightAmount)
                setValue(TypeinfoType.supertypes, staticSupertypesGlobal)
                setValue(TypeinfoType.anyValueVirtuals, EmergeAnyValueVirtualsType.buildConstantIn(context) {
                    setValue(EmergeAnyValueVirtualsType.finalizeFunction, context.registerIntrinsic(staticObjectFinalizer).address)
                })
                setValue(TypeinfoType.vtableBlob, vtableBlob)
            }

            return Pair(typeinfoDynamicData, typeinfoStaticData)
        }
    }

    companion object {
        fun define(
            typeName: String,
            supertypes: List<LlvmConstant<LlvmPointerType<TypeinfoType>>>,
            finalizerFunction: KotlinLlvmFunction<EmergeLlvmContext, LlvmVoidType>,
            virtualFunctions: EmergeLlvmContext.() -> List<Pair<LlvmConstant<EmergeWordType>, KotlinLlvmFunction<*, *>>>,
        ): Provider = ProviderImpl(typeName, supertypes, finalizerFunction, virtualFunctions)
    }
}