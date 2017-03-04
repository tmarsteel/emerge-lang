package compiler.parser.postproc

import compiler.InternalCompilerError
import compiler.ast.*
import compiler.ast.context.CTContext
import compiler.ast.context.MutableCTContext
import compiler.matching.ResultCertainty
import compiler.parser.Reporting
import compiler.parser.rule.MatchingResult
import compiler.parser.rule.Rule
import compiler.transact.Position
import compiler.transact.TransactionalSequence
import java.util.*

fun ModulePostProcessor(rule: Rule<List<MatchingResult<*>>>): (Array<out String>) -> Rule<ModuleDefiner> {
    return { name ->
        val converter = ModuleASTConverter(name)

        rule
            .flatten()
            .map({ converter(it) })
    }
}

/**
 * Holds references to parsed elements; can add these elements to any given [CTContext]
 */
public class ModuleDefiner(val moduleName: Array<String>, val parsedContext: CTContext)
{

}

private class ModuleASTConverter(val name: Array<out String>) {
    operator fun invoke(inResult: MatchingResult<TransactionalSequence<Any, Position>>): MatchingResult<ModuleDefiner>
    {
        val input = inResult.result ?: return inResult as MatchingResult<ModuleDefiner> // null can haz any type that i want :)

        val context = MutableCTContext()
        val reportings: MutableSet<Reporting> = HashSet()
        var moduleDeclaration: ModuleDeclaration? = null

        input.forEachRemainingIndexed { index, declaration ->
            declaration as? Declaration ?: throw InternalCompilerError("What tha heck went wrong here?!")

            if (declaration is ModuleDeclaration) {
                if (moduleDeclaration == null) {
                    if (index != 0) {
                        reportings.add(Reporting.error(
                            "The module declaration must be the first declaration in the source file",
                            declaration.declaredAt
                        ))
                    }

                    moduleDeclaration = declaration
                }
                else {
                    reportings.add(Reporting.error(
                        "Duplicate module declaration",
                        declaration.declaredAt
                    ))
                }
            }
            else if (declaration is ImportDeclaration) {
                context.addImport(declaration)
            }
            else if (declaration is VariableDeclaration) {
                context.addVariable(declaration)
            }
            else if (declaration is FunctionDeclaration) {
                // context.addFunction(declaration)
            }
            else {
                reportings.add(Reporting.error(
                    "Unsupported declaration $declaration",
                    declaration.declaredAt
                ))
            }
        }

        if (moduleDeclaration == null) {
            reportings.add(Reporting.error("No module declaration found.", (input.items[0] as Declaration).declaredAt))
        }
        else {
            if (!Arrays.equals(moduleDeclaration!!.name, name)) {
                reportings.add(Reporting.error("Module declaration does not match the target name ${name.joinToString(".")}", moduleDeclaration!!.declaredAt))
            }
        }

        return MatchingResult(
            certainty = ResultCertainty.DEFINITIVE,
            result = ModuleDefiner(
                name.asList().toTypedArray(),
                context
            ),
            errors = inResult.errors.plus(reportings)
        )
    }
}