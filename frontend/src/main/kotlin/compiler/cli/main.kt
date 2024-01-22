package compiler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import compiler.CoreIntrinsicsModule
import compiler.InternalCompilerError
import compiler.PackageName
import compiler.binding.context.SoftwareContext
import compiler.lexer.SourceSet
import compiler.lexer.lex
import compiler.parser.SourceFileRule
import compiler.parser.grammar.StandaloneFunctionDeclaration
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.CodeGenerationException
import io.github.tmarsteel.emerge.backend.api.EmergeBackend
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ServiceLoader
import java.util.stream.Collectors
import kotlin.time.toKotlinDuration

private val backends: Map<String, EmergeBackend> = ServiceLoader.load(EmergeBackend::class.java)
    .stream()
    .map { it.get() }
    .collect(Collectors.toMap(
        { it.targetName },
        { it },
        { a, b -> throw InternalCompilerError("Found two backends with name ${a.targetName}: ${a::class.qualifiedName} and ${b::class.qualifiedName}")}
    ))

object CompileCommand : CliktCommand() {
    private val moduleName: PackageName by argument("module name").packageName()
    private val srcDir: Path by argument("source directory")
        .path(mustExist = true, canBeFile = false, canBeDir = true, mustBeReadable = true)
    private val outDir: Path by argument("output directory")
        .path(mustExist = false, canBeFile = false, mustBeWritable = true)
        .defaultLazy(defaultForHelp = "<source dir>/../emerge-out") {
            srcDir.toAbsolutePath().parent.resolve("emerge-out")
        }
    private val target: EmergeBackend by option("--target").selectFrom(backends).required()
    override fun run() {
        val swCtx = SoftwareContext()
        CoreIntrinsicsModule.addTo(swCtx)
        val moduleCtx = swCtx.registerModule(moduleName)

        val measureClock = Clock.systemUTC()
        val startedAt = measureClock.instant()
        val sourceInMemoryAt: Instant

        val parseResults = SourceSet.load(srcDir, moduleName)
            .also {
                sourceInMemoryAt = measureClock.instant()
            }
            .map {
                SourceFileRule.match(lex(it), it.packageName)
            }

        val lexicalCompleteAt = measureClock.instant()
        parseResults.flatMap { it.reportings }.forEach(this::echo)

        if (parseResults.any { it.reportings.containsErrors }) {
            throw PrintMessage(
                "Could not parse the source-code",
                statusCode = 1,
                printError = true,
            )
        }
        if (parseResults.isEmpty()) {
            throw PrintMessage(
                "Found no source files in $srcDir",
                statusCode = 1,
                printError = true,
            )
        }

        parseResults.forEach {
            val bound = it.item!!.bindTo(moduleCtx)
            moduleCtx.addSourceFile(bound)
        }

        val semanticResults = swCtx.doSemanticAnalysis()
        val semanticCompleteAt = measureClock.instant()

        semanticResults.forEach(this::echo)

        if (semanticResults.any { it.level >= Reporting.Level.ERROR}) {
            throw PrintMessage(
                "The provided program is not valid",
                statusCode = 1,
                printError = true,
            )
        }

        val backendStartedAt = measureClock.instant()
        try {
            target.emit(swCtx.toBackendIr(), outDir)
        } catch (ex: CodeGenerationException) {
            echo("The backend failed to generate code.")
            throw ex
        }
        val backendDoneAt = measureClock.instant()

        echo("----------")
        echo("loading sources into memory: ${elapsedBetween(startedAt, sourceInMemoryAt)}")
        echo("lexical analysis: ${elapsedBetween(sourceInMemoryAt, lexicalCompleteAt)}")
        echo("semantic analysis: ${elapsedBetween(lexicalCompleteAt, semanticCompleteAt)}")
        echo("backend: ${elapsedBetween(backendStartedAt, backendDoneAt)}")
        echo("total time: ${elapsedBetween(startedAt, semanticCompleteAt)}")
    }

    private fun echo(reporting: Reporting) {
        echo(reporting.toString())
        echo()
        echo()
    }
}

fun main(args: Array<String>) {
    if (backends.isEmpty()) {
        throw InternalCompilerError("No backends found!")
    }
    CompileCommand.main(args)
}

private val Iterable<Reporting>.containsErrors
    get() = map(Reporting::level).any { it.level >= Reporting.Level.ERROR.level }

private fun elapsedBetween(start: Instant, end: Instant): String {
    val duration = Duration.between(start, end).toKotlinDuration()
    // there is no function that automatically chooses the right unit AND limits fractional digits
    // so we'll use toString() to pick the unit, and some regex magic to limit to 3 fractional digits, not rounding
    return duration.toString()
        .replace(Regex("(?<=\\.\\d{3})\\d+"), "")
        .replace(".000", "")
}