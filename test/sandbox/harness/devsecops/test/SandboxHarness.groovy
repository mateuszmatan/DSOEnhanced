package devsecops.test

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.ClassLoaderWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist

import java.util.concurrent.Callable

class SandboxHarness {

    final GroovyClassLoader loader
    final Whitelist pipelineWhitelist
    final Whitelist libraryWhitelist

    SandboxHarness(File srcDir, File stubDir, File fixturesDir = null) {
        CompilerConfiguration config = GroovySandbox.createSecureCompilerConfiguration()
        ImportCustomizer imports = new ImportCustomizer()
        imports.addImports('com.cloudbees.groovy.cps.NonCPS')
        config.addCompilationCustomizers(imports)
        config.scriptBaseClass = FakeCpsScript.name
        loader = new GroovyClassLoader(SandboxHarness.classLoader, config)
        loader.addClasspath(srcDir.absolutePath)
        loader.addClasspath(stubDir.absolutePath)
        if (fixturesDir != null) loader.addClasspath(fixturesDir.absolutePath)

        Whitelist generic = StaticWhitelist.from(StaticWhitelist.getResource('generic-whitelist'))
        Whitelist jenkins = StaticWhitelist.from(StaticWhitelist.getResource('jenkins-whitelist'))
        pipelineWhitelist = new ProxyWhitelist(generic, jenkins, new PipelineDslWhitelist())
        libraryWhitelist = new ProxyWhitelist(pipelineWhitelist, new ClassLoaderWhitelist(loader), new LibraryClassesWhitelist(), new VarsWhitelist())
    }

    Map<String, Object> loadVars(File varsDir, FakeScript jenkins) {
        Map<String, Object> globals = [:]
        varsDir.listFiles().findAll { File f -> f.name.endsWith('.groovy') }.sort { File f -> f.name }.each { File f ->
            Class type = loader.parseClass(f)
            FakeCpsScript var = (FakeCpsScript) run { type.newInstance() }
            var.attach(jenkins, globals)
            globals[f.name - '.groovy'] = var
        }
        return globals
    }

    Class type(String name) {
        return loader.loadClass(name)
    }

    Object create(String name, Object... args) {
        return run { type(name).newInstance(args) }
    }

    Object run(Closure body) {
        return runWith(libraryWhitelist, body)
    }

    Object runWith(Whitelist whitelist, Closure body) {
        return GroovySandbox.runInSandbox(body as Callable, whitelist)
    }

    static String rejectionOf(Throwable t) {
        Throwable cause = t
        while (cause != null) {
            if (cause instanceof RejectedAccessException) return cause.message
            cause = cause.cause
        }
        return null
    }
}
