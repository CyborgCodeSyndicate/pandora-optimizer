import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import java.nio.file.*;
import java.util.*;
public class FormatCompassAnnotations {
    static int offset(String text, Position p) {
        int at=0;for(int line=1;line<p.line;line++)at=text.indexOf('\n',at)+1;
        return at+p.column-1;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);JavaParser parser=new JavaParser();
        try(var files=Files.walk(root)) {
            for(Path path:files.filter(p->p.toString().endsWith(".java")&&!p.toString().contains("/target/")&&!p.toString().contains("\\target\\")).toList()) {
                String text=Files.readString(path);
                if(!text.contains("@AiCompassOptions"))continue;
                CompilationUnit unit=parser.parse(text).getResult().orElseThrow();
                List<NormalAnnotationExpr> annotations=unit.findAll(NormalAnnotationExpr.class).stream()
                    .filter(a->a.getNameAsString().equals("AiCompassOptions")).sorted(Comparator.comparingInt((NormalAnnotationExpr a)->a.getBegin().orElseThrow().line).reversed()).toList();
                List<String> imports=new ArrayList<>();
                for(var annotation:annotations) {
                    int start=offset(text,annotation.getBegin().orElseThrow()),end=offset(text,annotation.getEnd().orElseThrow())+1;
                    int lineStart=text.lastIndexOf('\n',start)+1;
                    String indent=text.substring(lineStart,start).replaceAll("[^ ].*","");
                    StringBuilder output=new StringBuilder("@AiCompassOptions(\n");
                    for(int index=0;index<annotation.getPairs().size();index++) {
                        var pair=annotation.getPairs().get(index);
                        String value=pair.getValue().toString().replaceAll("\\s*\\n\\s*"," ");
                        if(pair.getValue() instanceof ClassExpr c) {
                            String qualified=c.getType().toString(),simple=qualified.substring(qualified.lastIndexOf('.')+1);
                            if(qualified.contains(".")) {
                                String pkg=unit.getPackageDeclaration().map(p->p.getNameAsString()).orElse("");
                                if(!qualified.equals(pkg+"."+simple)&&unit.getImports().stream().noneMatch(i->i.getNameAsString().equals(qualified)))
                                    imports.add(qualified);
                                value=simple+".class";
                            }
                        }
                        output.append(indent).append("      ").append(pair.getNameAsString()).append(" = ");
                        if(pair.getValue() instanceof ArrayInitializerExpr array && pair.getNameAsString().equals("meta")) {
                            output.append("{\n");
                            for(int i=0;i<array.getValues().size();i++)output.append(indent).append("         ").append(array.getValues().get(i).toString().replaceAll("\\s*\\n\\s*"," ")).append(i+1<array.getValues().size()?",":"").append("\n");
                            output.append(indent).append("      }");
                        }else output.append(value);
                        output.append(index+1<annotation.getPairs().size()?",":"").append("\n");
                    }
                    output.append(indent).append(")");
                    text=text.substring(0,start)+output+text.substring(end);
                }
                for(String name:new TreeSet<>(imports)) {
                    String line="import "+name+";";
                    if(!text.contains(line))text=text.replaceFirst("(package [^;]+;)", "$1\n\n"+line);
                }
                text=text.replaceAll("(import [^;]+;)\\n(/\\*\\*)","$1\n\n$2");
                Files.writeString(path,text);
            }
        }
    }
}
