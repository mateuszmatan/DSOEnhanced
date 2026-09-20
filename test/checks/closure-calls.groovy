import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
List<String> problems = []

class StaticClosureCallVisitor extends ClassCodeVisitorSupport {
    SourceUnit source
    String file
    String methodName
    int closureDepth = 0
    List<String> problems

    protected SourceUnit getSourceUnit() { source }

    void visitClosureExpression(ClosureExpression expression) {
        closureDepth++
        super.visitClosureExpression(expression)
        closureDepth--
    }

    void visitMethodCallExpression(MethodCallExpression call) {
        if (closureDepth > 0 && call.isImplicitThis()) {
            problems << "${file}:${call.lineNumber} static method ${methodName}() calls ${call.methodAsString}() without a receiver inside a closure".toString()
        }
        super.visitMethodCallExpression(call)
    }
}

new File(root, 'src').eachFileRecurse { File f ->
    if (!f.name.endsWith('.groovy')) return
    CompilationUnit unit = new CompilationUnit()
    SourceUnit source = unit.addSource(f)
    unit.compile(Phases.CONVERSION)
    source.AST.classes.each { ClassNode cls ->
        cls.methods.findAll { MethodNode m -> m.isStatic() }.each { MethodNode m ->
            StaticClosureCallVisitor visitor = new StaticClosureCallVisitor(
                    source: source, file: root.toPath().relativize(f.toPath()).toString(), methodName: m.name, problems: problems)
            m.code?.visit(visitor)
        }
    }
}

if (problems) {
    println "CLOSURE CALL CHECK FAILED - the sandbox rejects these with 'DefaultGroovyMethods invokeMethod':"
    problems.each { println "  ${it}" }
    println "  Qualify the call with the class name or replace the closure with a for loop."
    System.exit(1)
}
println "CLOSURE CALL CHECK PASSED - no unqualified call inside a closure of a static method"
