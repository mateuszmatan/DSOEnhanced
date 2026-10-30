package com.bbh.remediation

import com.bbh.remediation.model.VersionCatalogs
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class CatalogDiscovery implements Serializable {

    @NonCPS
    static List<String> listed(def value) {
        List raw = value instanceof List ? (value as List) : (value ? [value] : [])
        return raw.collect { BuildUtils.relativePath((it ?: '').toString()) }.findAll { it }.unique() as List<String>
    }

    @NonCPS
    static boolean outside(String path) {
        return path.startsWith('/') || path.contains('\\') || path ==~ /^[A-Za-z]:.*/ || path.tokenize('/').contains('..')
    }

    @NonCPS
    static List<String> toRead(List<String> files) {
        List<String> out = []
        List<String> poms = []
        for (String f : files) {
            if (isScript(f) || isBuildSrc(f)) out << f
            if (isPom(f)) poms << f
        }
        return poms.size() > 1 ? out + poms : out
    }

    @NonCPS
    static List<String> wanted(List<String> files, Map texts, List<String> listed) {
        Map facts = facts(files, texts)
        List<String> out = []
        for (Map target : ((facts.imports as List) + (facts.applied as List))) {
            if (!files.contains(target.path)) out << (target.path as String)
        }
        for (String path : listed) {
            if (!outside(path) && !files.contains(path)) out << path
        }
        if (facts.buildSrc) out.addAll(['buildSrc/*.kt', 'buildSrc/*.java', 'buildSrc/*.groovy'])
        return out.unique().collect { './' + (it as String).replace('**/', '*').replace('**', '*') } as List<String>
    }

    @NonCPS
    static Map discover(List<String> scanned, List<String> extra, Map texts, List<String> listed) {
        List<String> all = [] + scanned
        for (String f : extra) {
            if (!isBuildSrc(f) && !all.contains(f)) all << f
        }
        Map facts = facts(all, texts)
        List<String> settingsDirs = facts.settingsDirs as List<String>
        Map entries = [:]
        for (Map i : (facts.imports as List<Map>)) {
            if (all.contains(i.path)) add(entries, i.path as String, kindOf(i.path as String), i.name as String, i.root as String)
        }
        for (Map i : ((facts.dsl as List<Map>) + (facts.applied as List<Map>) + (facts.platforms as List<Map>))) {
            if (all.contains(i.path)) add(entries, i.path as String, 'gradle', i.name as String, i.root as String)
        }
        for (String f : all) {
            if (!f.endsWith('.versions.toml')) continue
            String root = parentOf(parentOf(f))
            boolean owned = f == (root ? root + '/' : '') + 'gradle/libs.versions.toml' && settingsDirs.contains(root)
            add(entries, f, 'toml', owned ? 'libs' : null, owned ? root : null)
        }
        List<String> notes = []
        for (String path : listed) {
            if (outside(path)) {
                notes << "version catalog ${path} is outside the repository".toString()
                continue
            }
            List<String> matches = all.findAll { (it as String) ==~ VersionCatalogs.globRegex(path) } as List<String>
            if (!matches) notes << "version catalog ${path} not found in the repository".toString()
            for (String m : matches) {
                boolean plain = !entries.containsKey(m) && fileName(m) in ['build.gradle', 'build.gradle.kts', 'gradle.properties']
                add(entries, m, kindOf(m), null, plain ? parentOf(m) : null)
            }
        }
        sharedPoms(all, texts, entries)
        for (def entry : entries.values()) {
            Map e = entry as Map
            if (e.kind == 'toml' && !e.names) (e.names as List) << VersionCatalogs.baseName(e.path as String)
        }
        return [files    : all.findAll { scanned.contains(it) || entries.containsKey(it) },
                entries  : entries,
                notes    : notes,
                externals: facts.externals,
                holders  : holders(extra, texts)]
    }

    @NonCPS
    static Map consumers(Map entries) {
        Map out = [:]
        for (def entry : entries.values()) out[(entry as Map).path] = (entry as Map).consumers
        return out
    }

    @NonCPS
    private static Map facts(List<String> files, Map texts) {
        List<String> settingsDirs = []
        for (String f : files) {
            if (isSettings(f)) settingsDirs << parentOf(f)
        }
        Map out = [settingsDirs: settingsDirs, imports: [], dsl: [], applied: [], platforms: [], externals: [:], buildSrc: false]
        for (String f : files) {
            String text = texts[f] as String
            if (text == null || !isScript(f)) continue
            String folder = parentOf(f)
            String root = rootOf(f, settingsDirs)
            if (isSettings(f)) {
                for (Map block : VersionCatalogs.settingsBlocks(text)) {
                    for (String file : (block.files as List<String>)) {
                        String path = VersionCatalogs.resolve(folder, file.replaceFirst(/^\$\{?(?:rootDir|settingsDir|rootProject\.projectDir)\}?\//, ''))
                        if (path != null) (out.imports as List) << [path: path, name: block.name, root: folder]
                    }
                    for (String artifact : (block.artifacts as List<String>)) (out.externals as Map)[block.name] = artifact
                    if (!block.files && !block.artifacts) (out.dsl as List) << [path: f, name: block.name, root: folder]
                }
            }
            def applied = text =~ /apply\s*\(?\s*from\s*[:=]\s*(rootProject\.)?(?:file\s*\(\s*)?['"]([^'"]+)['"]/
            while (applied.find()) {
                String raw = applied.group(2)
                if (raw.contains('://')) continue
                String relative = raw.replaceFirst(/^\$\{?(?:rootDir|rootProject\.projectDir)\}?\//, '')
                boolean fromRoot = applied.group(1) != null || relative != raw
                String path = VersionCatalogs.resolve(fromRoot ? (root ?: '') : folder, relative)
                if (path != null) (out.applied as List) << [path: path, root: root != null ? root : folder]
            }
            if (text =~ /['"`]java-platform['"`]/) (out.platforms as List) << [path: f, root: root]
            if (text =~ /\$\{?[A-Z]\w*\.\w+/) out.buildSrc = true
        }
        return out
    }

    @NonCPS
    private static void sharedPoms(List<String> all, Map texts, Map entries) {
        Map models = [:]
        for (String f : all) {
            if ((isPom(f) || entries[f]?.kind == 'maven') && texts[f] != null) models[f] = pom(texts[f] as String)
        }
        Map shared = [:]
        for (def entry : entries.values()) {
            if ((entry as Map).kind == 'maven') shared[(entry as Map).path] = []
        }
        for (def p : models.keySet()) {
            Map model = models[p] as Map
            List<String> targets = []
            if (model.parent) targets << VersionCatalogs.resolve(parentOf(p as String), pomPath(model.parent as String))
            for (def imported : (model.imports as List)) {
                def target = models.keySet().find { (models[it] as Map).coordinates == imported }
                if (target) targets << (target as String)
            }
            for (String target : targets) {
                if (target != null && target != p && models.containsKey(target)) {
                    shared[target] = ((shared[target] ?: []) as List) + [p]
                }
            }
        }
        for (def s : shared.keySet()) {
            add(entries, s as String, 'maven', null, aggregator(s as String, shared[s] as List, models, all.contains('pom.xml')))
        }
    }

    @NonCPS
    private static String aggregator(String shared, List consumers, Map models, boolean rootPom) {
        String folder = parentOf(shared)
        while (true) {
            String candidate = (folder ? folder + '/' : '') + 'pom.xml'
            if (models.containsKey(candidate)) {
                List reach = reach(folder, models, [])
                List folders = consumers.collect { (it as String).replaceFirst('/?[^/]*$', '') }
                if (reach.contains(parentOf(shared)) && folders.any { reach.contains(it) }) return folder
            }
            if (!folder) break
            folder = parentOf(folder)
        }
        return rootPom ? '' : parentOf(shared)
    }

    @NonCPS
    private static List reach(String folder, Map models, List seen) {
        if (seen.contains(folder)) return seen
        seen << folder
        Map model = models[(folder ? folder + '/' : '') + 'pom.xml'] as Map
        for (def module : ((model?.modules ?: []) as List)) {
            String child = VersionCatalogs.resolve(folder, pomPath(module as String))
            if (child != null) reach(parentOf(child), models, seen)
        }
        return seen
    }

    @NonCPS
    static Map pom(String text) {
        String xml = (text ?: '').replaceAll(/(?s)<!--.*?-->/, '')
        String parent = element(xml, 'parent')
        String own = xml.replaceAll(/(?s)<(parent|dependencies|dependencyManagement|build|profiles|reporting|modules|properties)\s*>.*?<\/\1\s*>/, '')
        List imports = []
        def dependency = xml =~ /(?s)<dependency\s*>(.*?)<\/dependency\s*>/
        while (dependency.find()) {
            String block = dependency.group(1)
            if (element(block, 'scope') == 'import') imports << "${element(block, 'groupId')}:${element(block, 'artifactId')}".toString()
        }
        String relativePath = parent == null || parent =~ /<relativePath\s*\/>/ ? null : (element(parent, 'relativePath') ?: '../pom.xml')
        return [coordinates: "${element(own, 'groupId') ?: element(parent, 'groupId')}:${element(own, 'artifactId')}".toString(),
                parent     : relativePath,
                modules    : VersionCatalogs.groups(element(xml, 'modules'), /<module>\s*([^<]+?)\s*<\/module>/),
                imports    : imports]
    }

    @NonCPS
    private static Map holders(List<String> files, Map texts) {
        Map out = [:]
        for (String f : files) {
            if (!isBuildSrc(f) || texts[f] == null) continue
            def holder = (texts[f] as String) =~ /(?m)^\s*(?:(?:private|public|protected|internal|const|static|final)\s+)*(?:val|var|def|String)\s+(\w+)\s*(?::\s*String\s*)?=\s*['"]([^'"\$]+)['"]/
            while (holder.find()) {
                if (!out.containsKey(holder.group(1))) out[holder.group(1)] = f
            }
        }
        return out
    }

    @NonCPS
    private static void add(Map entries, String path, String kind, String name, String root) {
        Map entry = (entries[path] ?: [path: path, kind: kind, names: [], consumers: []]) as Map
        if (name && !(entry.names as List).contains(name)) (entry.names as List) << name
        if (root != null && !(entry.consumers as List).contains(root)) (entry.consumers as List) << root
        entries[path] = entry
    }

    @NonCPS
    private static String rootOf(String path, List<String> settingsDirs) {
        String folder = parentOf(path)
        String best = null
        for (String dir : settingsDirs) {
            boolean within = dir == '' || folder == dir || folder.startsWith(dir + '/')
            if (within && (best == null || dir.length() > best.length())) best = dir
        }
        return best
    }

    @NonCPS
    private static String element(String xml, String name) {
        def matcher = (xml ?: '') =~ ('(?s)<' + name + '\\s*>\\s*(.*?)\\s*</' + name + '\\s*>')
        return matcher.find() ? matcher.group(1) : null
    }

    @NonCPS
    private static String pomPath(String path) {
        return path.endsWith('.xml') ? path : path + '/pom.xml'
    }

    @NonCPS
    private static String kindOf(String path) {
        return path.endsWith('.toml') ? 'toml' : (path.endsWith('.xml') ? 'maven' : 'gradle')
    }

    @NonCPS
    private static boolean isSettings(String path) {
        return fileName(path) in ['settings.gradle', 'settings.gradle.kts']
    }

    @NonCPS
    private static boolean isScript(String path) {
        return path.endsWith('.gradle') || path.endsWith('.gradle.kts')
    }

    @NonCPS
    private static boolean isPom(String path) {
        return fileName(path) == 'pom.xml'
    }

    @NonCPS
    private static boolean isBuildSrc(String path) {
        return path.startsWith('buildSrc/') && !isScript(path)
    }

    @NonCPS
    private static String fileName(String path) {
        return (path ?: '').replaceFirst('^.*/', '')
    }

    @NonCPS
    private static String parentOf(String path) {
        int index = (path ?: '').lastIndexOf('/')
        return index >= 0 ? path.substring(0, index) : ''
    }
}
