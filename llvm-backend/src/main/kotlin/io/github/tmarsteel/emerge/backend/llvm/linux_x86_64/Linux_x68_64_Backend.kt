package io.github.tmarsteel.emerge.backend.llvm.linux_x86_64

import io.github.tmarsteel.emerge.backend.SystemPropertyDelegate.Companion.systemProperty
import io.github.tmarsteel.emerge.backend.api.CodeGenerationException
import io.github.tmarsteel.emerge.backend.api.DotName
import io.github.tmarsteel.emerge.backend.api.EmergeBackend
import io.github.tmarsteel.emerge.backend.api.ModuleSourceRef
import io.github.tmarsteel.emerge.backend.api.ir.IrImplementedFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrSoftwareContext
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmCompiler
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunction
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmTarget
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmVoidType
import io.github.tmarsteel.emerge.backend.llvm.dsl.PassBuilderOptions
import io.github.tmarsteel.emerge.backend.llvm.getLlvmMessage
import io.github.tmarsteel.emerge.backend.llvm.intrinsics.EmergeLlvmContext
import io.github.tmarsteel.emerge.backend.llvm.linux.EmergeEntrypoint
import io.github.tmarsteel.emerge.backend.llvm.linux.LinuxLinker
import io.github.tmarsteel.emerge.backend.llvm.llvmRef
import io.github.tmarsteel.emerge.backend.llvm.packagesSeq
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.global.LLVM
import java.nio.file.Path
import java.nio.file.Paths

class Linux_x68_64_Backend : EmergeBackend {
    override val targetName = "linux-x86_64"

    override val targetSpecificModules: Collection<ModuleSourceRef> = setOf(
        ModuleSourceRef(FFI_C_SOURCES_PATH, DotName(listOf("emerge", "ffi", "c"))),
        ModuleSourceRef(LINUX_LIBC_SOURCES_PATH, DotName(listOf("emerge", "linux", "libc"))),
        ModuleSourceRef(LINUX_PLATFORM_PATH, DotName(listOf("emerge", "platform"))),
    )

    override fun emit(softwareContext: IrSoftwareContext, directory: Path) {
        val bitcodeFilePath = directory.resolve("out.bc").toAbsolutePath()
        writeSoftwareToBitcodeFile(softwareContext, bitcodeFilePath)

        /* we cannot use LLVMTargetMachineEmitToFile because:
        it insits on using .ctor sections for static initializers, but these are LONG deprecated
        the c runtime only looks for init_array sections. For some reason, LLVMTargetMachineEmitToFile
        wants to use .ctors. In C++, one can configure this in the TargetOptions class by setting
        useInitArray to true. Though, there currently is no way to modify the C++ TargetOptions object
        that gets passed to TargetMachine::create via the C interface. I might submit a PR when i feel like
        it. If things like LLVMCreateTargetOptions and LLVMTargetOptionsSetUseInitArray get added to llvm-c,
        this roundtrip through the filesystem can be replaced by a direct call to LLVMTargetMachineEmitToFile.

        I've also read online that LLVMTargetMachineEmitToFile ignores the datalayout string and instead uses
        the default for the target-triple. Not a problem as of writing, since its not customized, but also a bug
        i don't want to have to re-discover when chasing a segfault or something like that...
         */

        val objectFilePath = directory.resolve("out.o")
        LlvmCompiler.compileBitcodeFile(
            bitcodeFilePath,
            objectFilePath,
        )

        val executablePath = directory.resolve("runnable").toAbsolutePath()
        LinuxLinker.linkObjectFilesToELF(
            listOf(
                // what other object files we need is just found out from clang -v
                // this is a rabbit hole to go down, especially why clang uses gcc binaries
                // but there are more important things right now. This should work on most debian distros.
                Paths.get("/usr/lib/x86_64-linux-gnu/Scrt1.o"),
                Paths.get("/lib/x86_64-linux-gnu/crti.o"),
                Paths.get("/usr/lib/gcc/x86_64-linux-gnu/12/crtbeginS.o"),
                objectFilePath,
                Paths.get("/usr/lib/gcc/x86_64-linux-gnu/12/crtendS.o"),
                Paths.get("/lib/x86_64-linux-gnu/crtn.o"),
            ),
            executablePath,
            dynamicallyLinkAtRuntime = listOf("c"),
        )
    }

    private fun writeSoftwareToBitcodeFile(softwareContext: IrSoftwareContext, bitcodeFilePath: Path) {
        LLVM.LLVMInitializeAllTargetInfos()
        LLVM.LLVMInitializeAllTargets()
        LLVM.LLVMInitializeAllTargetMCs()
        LLVM.LLVMInitializeAllAsmPrinters()
        LLVM.LLVMInitializeAllAsmParsers()

        EmergeLlvmContext.createDoAndDispose(LlvmTarget.fromTriple("x86_64-pc-linux-unknown")) { llvmContext ->
            softwareContext.packagesSeq.forEach { pkg ->
                pkg.classes.forEach(llvmContext::registerStruct)
            }
            softwareContext.modules.flatMap { it.packages }.forEach { pkg ->
                pkg.functions
                    .flatMap { it.overloads }
                    .forEach {
                        val fn = llvmContext.registerFunction(it)
                        storeCoreFunctionReference(llvmContext, it.fqn, fn)
                    }
            }
            softwareContext.modules.flatMap { it.packages }
                .flatMap { it.variables }
                .forEach {
                    llvmContext.registerGlobal(it.declaration)
                }
            softwareContext.modules.flatMap { it.packages }
                .flatMap { it.variables }
                .forEach {
                    llvmContext.defineGlobalInitializer(it.declaration, it.initializer)
                }
            softwareContext.modules.flatMap { it.packages }.forEach { pkg ->
                pkg.functions
                    .flatMap { it.overloads }
                    .filterIsInstance<IrImplementedFunction>()
                    .forEach(llvmContext::defineFunctionBody)
            }

            // assure the entrypoint is in the object file
            llvmContext.registerIntrinsic(EmergeEntrypoint)
            llvmContext.complete()

            val errorMessageBuffer = BytePointer(1024 * 10)

            errorMessageBuffer.position(0)
            errorMessageBuffer.limit(errorMessageBuffer.capacity())
            if (LLVM.LLVMPrintModuleToFile(
                llvmContext.module,
                bitcodeFilePath.parent.resolve("out.ll").toString(),
                errorMessageBuffer,
            ) != 0) {
                // null-terminated, this makes the .getString() function behave correctly
                errorMessageBuffer.limit(0)
                throw CodeGenerationException(errorMessageBuffer.string)
            }

            errorMessageBuffer.position(0)
            errorMessageBuffer.limit(errorMessageBuffer.capacity())
            if (LLVM.LLVMVerifyModule(llvmContext.module, LLVM.LLVMReturnStatusAction, errorMessageBuffer) != 0) {
                // null-terminated, this makes the .getString() function behave correctly
                errorMessageBuffer.limit(0)
                throw CodeGenerationException(errorMessageBuffer.string)
            }

            PassBuilderOptions().use { pbo ->
                val error = LLVM.LLVMRunPasses(
                    llvmContext.module,
                    "default<O0>",
                    llvmContext.targetMachine.ref,
                    pbo.ref,
                )
                if (error != null) {
                    throw CodeGenerationException("LLVM passes failed: ${getLlvmMessage(LLVM.LLVMGetErrorMessage(error))}")
                }
            }

            if (LLVM.LLVMWriteBitcodeToFile(llvmContext.module, bitcodeFilePath.toString()) != 0) {
                throw CodeGenerationException("Failed to write LLVM bitcode to $bitcodeFilePath")
            }
        }
    }

    private fun createExecutableFromObjectFile(objectFilePath: Path, executablePath: Path) {

    }

    private fun findMainFunction(softwareContext: IrSoftwareContext): LlvmFunction<*> {
        return softwareContext.modules
            .flatMap { it.packages }
            .flatMap { it.functions }
            .flatMap { it.overloads }
            .single { it.fqn.last == "main" }
            .llvmRef!!
    }

    private fun storeCoreFunctionReference(context: EmergeLlvmContext, functionName: DotName, fn: LlvmFunction<*>) {
        when (functionName) {
            ALLOCATOR_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.allocateFunction = fn as LlvmFunction<LlvmPointerType<LlvmVoidType>>
            }
            FREE_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.freeFunction = fn as LlvmFunction<LlvmVoidType>
            }
            EXIT_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.exitFunction = fn as LlvmFunction<LlvmVoidType>
            }
        }
    }

    companion object {
        val FFI_C_SOURCES_PATH by systemProperty("emerge.compiler.native.c-ffi-sources", Paths::get)
        val LINUX_LIBC_SOURCES_PATH by systemProperty("emerge.compiler.native.libc-wrapper.sources", Paths::get)
        val LINUX_PLATFORM_PATH by systemProperty("emerge.compiler.native.linux-platform.sources", Paths::get)

        private val ALLOCATOR_FUNCTION_NAME = DotName(listOf("emerge", "linux", "libc", "malloc"))
        private val FREE_FUNCTION_NAME = DotName(listOf("emerge", "linux", "libc", "free"))
        private val EXIT_FUNCTION_NAME = DotName(listOf("emerge", "linux", "libc", "exit"))
    }
}

