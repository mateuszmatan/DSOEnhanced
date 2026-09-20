import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer

import java.util.zip.ZipFile

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File libs = new File(args.length > 1 ? args[1] : System.getenv('DEVSECOPS_LIBS') ?: '.')
File jar = new File(libs, 'script-security.jar')
if (!jar.file) {
    println "JDK WHITELIST CHECK FAILED - ${jar} not found, run test/run-all.sh to download it"
    System.exit(1)
}

List<List<String>> entries = []
ZipFile zip = new ZipFile(jar)
['generic-whitelist', 'jenkins-whitelist'].each { String name ->
    zip.getInputStream(zip.getEntry("org/jenkinsci/plugins/scriptsecurity/sandbox/whitelists/${name}")).text.eachLine { String line ->
        String clean = line.replaceAll('#.*$', '').trim()
        if (clean) entries << clean.tokenize(' ')
    }
}
zip.close()

boolean aritySatisfied(List<String> params, int arity) {
    if (params.size() == arity) return true
    return params && params.last().endsWith('[]') && arity >= params.size() - 1
}

Closure permitted = { String kind, String type, String member, int arity ->
    entries.any { List<String> e ->
        if (kind == 'staticField') return e[0] == 'staticField' && e[1] == type && e[2] == member
        if (kind == 'new') return e[0] == 'new' && e[1] == type && aritySatisfied(e.drop(2), arity)
        if (e[0] != 'staticMethod') return false
        if (e[1] == type && e[2] == member && aritySatisfied(e.drop(3), arity)) return true
        return e[1] == 'org.codehaus.groovy.runtime.DefaultGroovyStaticMethods' && e[2] == member && e[3] == type && aritySatisfied(e.drop(4), arity)
    }
}

class ForeignAccessVisitor extends ClassCodeVisitorSupport {
    SourceUnit source
    Closure record

    protected SourceUnit getSourceUnit() { source }

    private static int arity(Expression arguments) {
        return arguments instanceof TupleExpression ? ((TupleExpression) arguments).expressions.size() : 1
    }

    void visitPropertyExpression(PropertyExpression expression) {
        if (expression.objectExpression instanceof ClassExpression && expression.propertyAsString != 'class') {
            record('staticField', expression.objectExpression.type, expression.propertyAsString, -1, expression.lineNumber)
        }
        super.visitPropertyExpression(expression)
    }

    void visitMethodCallExpression(MethodCallExpression call) {
        if (call.objectExpression instanceof ClassExpression) {
            record('staticMethod', call.objectExpression.type, call.methodAsString, arity(call.arguments), call.lineNumber)
        }
        super.visitMethodCallExpression(call)
    }

    void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        record('staticMethod', call.ownerType, call.method, arity(call.arguments), call.lineNumber)
        super.visitStaticMethodCallExpression(call)
    }

    void visitConstructorCallExpression(ConstructorCallExpression call) {
        if (!call.isSuperCall() && !call.isThisCall()) record('new', call.type, '<init>', arity(call.arguments), call.lineNumber)
        super.visitConstructorCallExpression(call)
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

Set<String> libraryTypes = [] as Set
sources.keySet().each { SourceUnit s -> s.AST.classes.each { ClassNode c -> libraryTypes << c.name } }

List<String> problems = []
sources.each { SourceUnit source, String file ->
    Closure record = { String kind, ClassNode type, String member, int arity, int line ->
        String name = type.name
        if (line < 1) return
        if (libraryTypes.contains(name) || name.startsWith('com.bbh.') || name == 'com.cloudbees.groovy.cps.NonCPS') return
        if (!permitted(kind, name, member, arity)) {
            String what = kind == 'new' ? "new ${name}(${arity} argument(s))" :
                    kind == 'staticField' ? "static field ${name}.${member}" : "static method ${name}.${member}(${arity} argument(s))"
            problems << "${file}:${line} ${what}".toString()
        }
    }
    source.AST.classes.each { ClassNode cls ->
        ForeignAccessVisitor visitor = new ForeignAccessVisitor(source: source, record: record)
        visitor.visitClass(cls)
    }
}

if (problems) {
    println "JDK WHITELIST CHECK FAILED - the Jenkins sandbox rejects these without an administrator approval:"
    problems.unique().each { println "  ${it}" }
    System.exit(1)
}
println "JDK WHITELIST CHECK PASSED - every JDK constructor, static method and static field is on the script-security whitelist"
