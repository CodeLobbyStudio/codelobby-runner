import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

/** Runner-owned compiler driver, executed only inside the compilation sandbox. */
public final class CompileSuite {
    public static void main(String[] args) throws Exception {
        String mainClass = args[0];
        int hostCount = Integer.parseInt(args[1]);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Set<String> hostClasses = new HashSet<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var hostPaths = new ArrayList<String>();
            for (int i = 0; i < hostCount; i++) {
                hostPaths.add(args[i + 2]);
            }
            // Parsing does not resolve calls to participant methods or execute source code.
            var parser = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none"), null,
                    files.getJavaFileObjectsFromStrings(hostPaths));
            for (var unit : parser.parse()) {
                String prefix = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
                for (var declaration : unit.getTypeDecls()) {
                    collectClasses(declaration, prefix, hostClasses);
                }
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    || !hostClasses.contains(mainClass)) {
                configurationError();
            }
            var allPaths = List.of(args).subList(2, args.length);
            boolean compiled = compiler.getTask(new PrintWriter(System.err, true), files, null,
                    List.of("--release", "21", "-encoding", "UTF-8", "-proc:none",
                            "-classpath", ".codelobby/classes", "-d", ".codelobby/classes"),
                    null, files.getJavaFileObjectsFromStrings(allPaths)).call();
            if (!compiled) {
                System.exit(1);
            }
        }
        // Load without initialization. No participant or host static initializer is run here.
        try (var loader = new URLClassLoader(new java.net.URL[] {
                Path.of(".codelobby/classes").toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var entry = Class.forName(mainClass, false, loader).getMethod("main", String[].class);
            if (!Modifier.isStatic(entry.getModifiers()) || entry.getReturnType() != void.class
                    || !hostClasses.contains(entry.getDeclaringClass().getName())) {
                configurationError();
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            configurationError();
        }
    }

    private static void collectClasses(Tree declaration, String prefix, Set<String> names) {
        if (declaration instanceof ClassTree type) {
            String name = prefix + type.getSimpleName();
            names.add(name);
            for (var member : type.getMembers()) {
                collectClasses(member, name + "$", names);
            }
        }
    }

    private static void configurationError() throws Exception {
        Files.writeString(Path.of(".codelobby/configuration-error"), "INVALID_HOST_TEST_CONFIGURATION");
        System.exit(1);
    }
}
