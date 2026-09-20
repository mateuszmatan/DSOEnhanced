import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile

class DynamicVariableVisitor extends ClassCodeVisitorSupport {
    SourceUnit source
    String file
    List<String> problems

    protected SourceUnit getSourceUnit() { source }

    void visitVariableExpression(VariableExpression expression) {
        if (expression.accessedVariable instanceof DynamicVariable) {
            problems << "${file}:${expression.lineNumber} '${expression.name}' is neither a local variable, a parameter nor a field of the class".toString()
        }
        super.visitVariableExpression(expression)
    }
}

CompilerConfiguration config = new CompilerConfiguration()
ImportCustomizer imports = new ImportCustomizer()
imports.addImports('com.cloudbees.groovy.cps.NonCPS')
config.addCompilationCustomizers(imports)
CompilationUnit unit = new CompilationUnit(config)
Map<SourceUnit, String> sources = [:]
new File(root, 'src').eachFileRecurse { File f ->
    if (f.name.endsWith('.groovy')) sources[unit.addSource(f)] = root.toPath().relativize(f.toPath()).toString()
}
new File(root, 'test/sandbox/stub').eachFileRecurse { File f -> if (f.name.endsWith('.groovy')) unit.addSource(f) }
unit.compile(Phases.SEMANTIC_ANALYSIS)

List<String> problems = []
sources.each { SourceUnit source, String file ->
    source.AST.classes.each { ClassNode cls ->
        if (cls.isScript()) return
        new DynamicVariableVisitor(source: source, file: file, problems: problems).visitClass(cls)
    }
}

if (problems) {
    println "UNDEFINED VARIABLE CHECK FAILED - these names resolve to a dynamic property that the class does not declare:"
    problems.unique().each { println "  ${it}" }
    System.exit(1)
}
println "UNDEFINED VARIABLE CHECK PASSED - every name used in the library classes is declared"
