import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.syntax.Types

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile

class ClosureFieldWriteVisitor extends ClassCodeVisitorSupport {
    SourceUnit source
    String file
    ClassNode owner
    int closureDepth = 0
    List<String> problems

    protected SourceUnit getSourceUnit() { source }

    void visitClosureExpression(ClosureExpression expression) {
        closureDepth++
        super.visitClosureExpression(expression)
        closureDepth--
    }

    private String fieldName(Expression target) {
        if (target instanceof VariableExpression) {
            def variable = ((VariableExpression) target).accessedVariable
            return (variable instanceof FieldNode || variable instanceof PropertyNode) ? variable.name : null
        }
        if (target instanceof PropertyExpression || target instanceof AttributeExpression) {
            Expression object = ((PropertyExpression) target).objectExpression
            if (object instanceof VariableExpression && ((VariableExpression) object).isThisExpression()) return ((PropertyExpression) target).propertyAsString
        }
        return null
    }

    void visitBinaryExpression(BinaryExpression expression) {
        if (closureDepth > 0 && Types.ofType(expression.operation.type, Types.ASSIGNMENT_OPERATOR)) {
            String name = fieldName(expression.leftExpression)
            if (name) problems << "${file}:${expression.lineNumber} assigns the field '${name}' of ${owner.nameWithoutPackage} inside a closure".toString()
        }
        super.visitBinaryExpression(expression)
    }
}

CompilerConfiguration config = new CompilerConfiguration()
ImportCustomizer imports = new ImportCustomizer()
imports.addImports('com.cloudbees.groovy.cps.NonCPS')
config.addCompilationCustomizers(imports)
CompilationUnit unit = new CompilationUnit(config)
Map<SourceUnit, String> sources = [:]
List<File> files = []
new File(root, 'src').eachFileRecurse { File f -> if (f.name.endsWith('.groovy')) files << f }
new File(root, 'vars').eachFile { File f -> if (f.name.endsWith('.groovy')) files << f }
files.sort().each { File f -> sources[unit.addSource(f)] = root.toPath().relativize(f.toPath()).toString() }
new File(root, 'test/sandbox/stub').eachFileRecurse { File f -> if (f.name.endsWith('.groovy')) unit.addSource(f) }
unit.compile(Phases.SEMANTIC_ANALYSIS)

List<String> problems = []
sources.each { SourceUnit source, String file ->
    source.AST.classes.each { ClassNode cls ->
        new ClosureFieldWriteVisitor(source: source, file: file, owner: cls, problems: problems).visitClass(cls)
    }
}

if (problems) {
    println "CLOSURE FIELD WRITE CHECK FAILED - the sandbox generates invalid bytecode (VerifyError) for these assignments:"
    problems.unique().each { println "  ${it}" }
    println "  Assign a local variable inside the closure and copy it to the field after the closure."
    System.exit(1)
}
println "CLOSURE FIELD WRITE CHECK PASSED - no closure assigns a field of its class"
