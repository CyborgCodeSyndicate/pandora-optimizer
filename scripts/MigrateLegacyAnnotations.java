import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Migrate legacy annotations while preserving source formatting and annotation payloads. */
public class MigrateLegacyAnnotations {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath();
        JsonNode rules=new ObjectMapper().readTree(Path.of(args[1]).toFile());
        JavaParser parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        int files=0,compasses=0,options=0,removedUsage=0,convertedRules=0;
        List<String> ruleOwners=new ArrayList<>();
        rules.fieldNames().forEachRemaining(name->{
            String owner=name.substring(0,name.lastIndexOf('.'));
            if(!ruleOwners.contains(owner))ruleOwners.add(owner);
        });
        try(var paths=Files.walk(root)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".java")&&!p.toString().contains(root.getFileSystem().getSeparator()+"target"+root.getFileSystem().getSeparator())).toList()) {
                String original=Files.readString(path);
                if(!original.contains("@Pandora"))continue;
                CompilationUnit unit=parser.parse(original).getResult().orElseThrow();
                LexicalPreservingPrinter.setup(unit);
                Map<String,String> imports=new HashMap<>();
                unit.getImports().forEach(i->imports.put(i.getName().getIdentifier(),i.getNameAsString()));
                for(AnnotationExpr annotation:new ArrayList<>(unit.findAll(AnnotationExpr.class))) {
                    String name=annotation.getNameAsString();
                    if(name.equals("Pandora")) { annotation.setName("AiCompass");compasses++; }
                    else if(name.equals("PandoraOptions.Meta"))annotation.setName("AiCompassOptions.Meta");
                    else if(name.equals("PandoraOptions")) {
                        annotation.setName("AiCompassOptions");
                        if(annotation instanceof NormalAnnotationExpr normal) {
                            for(var pair:new ArrayList<>(normal.getPairs())) {
                                if(pair.getNameAsString().equals("exampleFilesPath")) {normal.getPairs().remove(pair);removedUsage++;}
                                else if(pair.getNameAsString().equals("availableOptionsRule")) {
                                    String value=pair.getValue().toString().replace(".class","");
                                    String outer=value.substring(0,value.indexOf('.'));
                                    String qualified=imports.getOrDefault(outer,outer)+value.substring(outer.length());
                                    JsonNode rule=rules.get(qualified);
                                    if(rule==null)throw new IllegalArgumentException("Unknown rule "+qualified+" in "+path);
                                    normal.getPairs().remove(pair);
                                    normal.addPair(rule.path("kind").asText(),StaticJavaParser.parseExpression(rule.path("target").asText()+".class"));
                                    if(rule.path("render").asText().equals("QUALIFIED"))normal.addPair("render",new StringLiteralExpr("QUALIFIED"));
                                    convertedRules++;
                                }
                            }
                            if(normal.getPairs().isEmpty())annotation.remove();else options++;
                        }
                    }
                }
                for(var i:new ArrayList<>(unit.getImports())) {
                    String name=i.getNameAsString();
                    if(ruleOwners.contains(name))i.remove();
                    else if(name.equals("io.cyborgcode.pandora.annotation.Pandora"))i.setName("io.cyborgcode.pandora.annotations.AiCompass");
                    else if(name.equals("io.cyborgcode.pandora.annotation.PandoraOptions"))i.setName("io.cyborgcode.pandora.annotations.AiCompassOptions");
                    else if(name.equals("io.cyborgcode.pandora.model.CreationKind"))i.setName("io.cyborgcode.pandora.annotations.CreationKind");
                    else if(name.equals("io.cyborgcode.pandora.model.Preference"))i.setName("io.cyborgcode.pandora.annotations.Preference");
                }
                Files.writeString(path,LexicalPreservingPrinter.print(unit));files++;
            }
        }
        // The rule classes contained only the now-declarative discovery strategies.
        int removed=0;
        for(String owner:ruleOwners) {
            String suffix=owner.replace('.','/')+".java";
            try(var paths=Files.walk(root)) {
                for(Path p:paths.filter(p->p.toString().replace('\\','/').endsWith("/src/main/java/"+suffix)).toList()) {
                    if(!p.toRealPath().startsWith(root))throw new IllegalStateException("Path escape");
                    Files.delete(p);removed++;
                }
            }
        }
        System.out.printf("Migrated %d files, %d Compass declarations, %d retained option declarations, %d rules, %d removed usage references and %d obsolete helpers.%n",files,compasses,options,convertedRules,removedUsage,removed);
    }
}
