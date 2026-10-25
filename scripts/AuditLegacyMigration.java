import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class AuditLegacyMigration {
    static String body(CompilationUnit unit) {
        for(var annotation:new ArrayList<>(unit.findAll(AnnotationExpr.class)))annotation.remove();
        unit.getImports().clear();
        unit.walk(node -> {
            node.removeComment();
            for(var comment:new ArrayList<>(node.getOrphanComments()))node.removeOrphanComment(comment);
        });
        return unit.toString(new com.github.javaparser.printer.configuration.PrettyPrinterConfiguration()
            .setPrintComments(false).setPrintJavadoc(false));
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);JavaParser parser=new JavaParser();int files=0;
        List<String> failures=new ArrayList<>();
        Process list=new ProcessBuilder("git","diff","--name-only","--diff-filter=M").directory(root.toFile()).start();
        String names=new String(list.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        list.waitFor();
        for(String file:names.split("\\R")) {
            if(!file.endsWith(".java"))continue;
            Process old=new ProcessBuilder("git","show","origin/pandora-fixes-2:"+file).directory(root.toFile()).start();
            String text=new String(old.getInputStream().readAllBytes(),StandardCharsets.UTF_8);old.waitFor();
            var before=parser.parse(text).getResult().orElseThrow();
            var after=parser.parse(root.resolve(file)).getResult().orElseThrow();
            var expected=before.findAll(AnnotationExpr.class).stream().filter(a->a.getNameAsString().equals("Pandora")).map(a->a.toString().replaceFirst("@Pandora","@AiCompass")).sorted().toList();
            var actual=after.findAll(AnnotationExpr.class).stream().filter(a->a.getNameAsString().equals("AiCompass")).map(Object::toString).sorted().toList();
            if(!expected.equals(actual))failures.add(file+": annotation payload changed");
            if(!body(before).equals(body(after)))failures.add(file+": executable declaration changed");
            files++;
        }
        if(!failures.isEmpty())throw new IllegalStateException(failures.toString());
        System.out.println("PASS: "+files+" files retain identical executable ASTs and primary annotation payloads.");
    }
}
