import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
List<String> problems = []
Map<String, Map<String, Boolean>> transformed = [:]
List<List> parsed = []

Closure nonCps = { MethodNode m -> m.annotations.any { it.classNode.nameWithoutPackage == 'NonCPS' } }

new File(root, 'src').eachFileRecurse { File f ->
    if (!f.name.endsWith('.groovy')) return
    CompilationUnit unit = new CompilationUnit()
    SourceUnit source = unit.addSource(f)
    unit.compile(Phases.CONVERSION)
    source.AST.classes.each { ClassNode cls ->
        Map<String, Boolean> methods = transformed.computeIfAbsent(cls.nameWithoutPackage) { [:] }
        cls.methods.each { MethodNode m -> methods[m.name] = (methods[m.name] ?: false) || !nonCps(m) }
        parsed << [cls, root.toPath().relativize(f.toPath()).toString()]
    }
}

parsed.each { List entry ->
    ClassNode cls = entry[0] as ClassNode
    String file = entry[1] as String
    cls.methods.findAll { MethodNode m -> nonCps(m) }.each { MethodNode m ->
        m.code?.visit(new CodeVisitorSupport() {
            void visitMethodCallExpression(MethodCallExpression call) {
                def receiver = call.objectExpression
                String target = null
                if (call.isImplicitThis() || (receiver instanceof VariableExpression && receiver.name == 'this')) target = cls.nameWithoutPackage
                else if (receiver instanceof VariableExpression && transformed.containsKey(receiver.name)) target = receiver.name
                else if (receiver instanceof ClassExpression) target = receiver.type.nameWithoutPackage
                if (target && transformed[target]?.get(call.methodAsString)) {
                    problems << "${file}:${call.lineNumber} @NonCPS ${cls.nameWithoutPackage}.${m.name}() calls ${target}.${call.methodAsString}(), which is CPS-transformed".toString()
                }
                super.visitMethodCallExpression(call)
            }

            void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                if (transformed[cls.nameWithoutPackage]?.get(call.method)) {
                    problems << "${file}:${call.lineNumber} @NonCPS ${cls.nameWithoutPackage}.${m.name}() calls ${call.method}(), which is CPS-transformed".toString()
                }
                super.visitStaticMethodCallExpression(call)
            }
        })
    }
}

if (problems) {
    println "NONCPS CALL CHECK FAILED - Jenkins runs these calls with the wrong result ('expected to call ... but wound up catching ...'):"
    problems.each { println "  ${it}" }
    println "  Mark the called method @NonCPS as well, or call it from CPS code."
    System.exit(1)
}
println "NONCPS CALL CHECK PASSED - no @NonCPS method calls a CPS-transformed library method"
