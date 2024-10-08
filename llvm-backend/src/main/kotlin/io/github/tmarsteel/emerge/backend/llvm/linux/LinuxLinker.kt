package io.github.tmarsteel.emerge.backend.llvm.linux

import io.github.tmarsteel.emerge.backend.api.CodeGenerationException
import io.github.tmarsteel.emerge.backend.llvm.ToolDiscoverer
import io.github.tmarsteel.emerge.backend.llvm.runSyncCapturing
import java.nio.file.Path

class LinuxLinker(val linkerBinary: Path) {
    fun linkObjectFilesToELF(
        objectFiles: List<Path>,
        outputFile: Path,
        dynamicallyLinkAtRuntime: List<String> = emptyList(),
        runtimeDynamicLinker: UnixPath = UnixPath("/usr/lib64/ld-linux-x86-64.so.2"),
        libraryPaths: List<Path> = emptyList(),
    ) {
        val command = mutableListOf(
            linkerBinary.toString(),
            "-o", outputFile.toString(),
            "--dynamic-linker=${runtimeDynamicLinker.path}",
        )

        libraryPaths.forEach {
            command += "--library-path"
            command += it.toString()
        }
        dynamicallyLinkAtRuntime.forEach {
            command += "--library"
            command += it
        }

        objectFiles.forEach {
            command += it.toString()
        }

        val result = runSyncCapturing(command)
        if (result.exitCode == 0) {
            return
        }

        throw LinuxLinkerException(
            "linker failed; command: $command\nerror:\n" + result.standardErrorAsString(),
            result.exitCode,
        )
    }

    companion object {
        fun fromLlvmInstallationDirectory(llvmInstallationDirectory: Path): LinuxLinker {
            return LinuxLinker(
                ToolDiscoverer.INSTANCE.discover(
                    llvmInstallationDirectory.resolve("bin").resolve("ld.lld").toString(),
                    "ld.lld-18",
                    "ld",
                )
            )
        }
    }
}

class LinuxLinkerException(message: String, val exitCode: Int) : CodeGenerationException(message)

/**
 * Some type-safety for unix paths, because [java.nio.file.Path] can't be used reliably; unix-specific
 * implementations are private to the JVM.
 */
data class UnixPath(val path: String)