package br.com.miniautorizador;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.IfTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class JavaFlowRestrictionTest {

    @Test
    void codigoJavaDeProducaoETestesNaoUsaComandosDeFluxoProibidos() throws IOException {
        Path projectDirectory = Path.of(System.getProperty("user.dir"));
        List<Path> javaSources = new ArrayList<>();
        javaSources.addAll(javaSourcesUnder(projectDirectory.resolve("src/main/java")));
        javaSources.addAll(javaSourcesUnder(projectDirectory.resolve("src/test/java")));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK disponível para analisar os tokens Java").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(javaSources);
            JavacTask parser = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                    compilationUnits);
            ForbiddenFlowScanner scanner = new ForbiddenFlowScanner();
            parser.parse().forEach(compilationUnit -> scanner.scan(compilationUnit,
                    Path.of(compilationUnit.getSourceFile().toUri())));

            assertThat(scanner.violations)
                    .as("Tokens Java proibidos: if, break e continue")
                    .isEmpty();
        }
    }

    private List<Path> javaSourcesUnder(Path sourceRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static final class ForbiddenFlowScanner extends TreeScanner<Void, Path> {

        private final List<String> violations = new ArrayList<>();

        @Override
        public Void visitIf(IfTree node, Path sourcePath) {
            violations.add(sourcePath + ":if");
            return super.visitIf(node, sourcePath);
        }

        @Override
        public Void visitBreak(BreakTree node, Path sourcePath) {
            violations.add(sourcePath + ":break");
            return super.visitBreak(node, sourcePath);
        }

        @Override
        public Void visitContinue(ContinueTree node, Path sourcePath) {
            violations.add(sourcePath + ":continue");
            return super.visitContinue(node, sourcePath);
        }
    }
}
