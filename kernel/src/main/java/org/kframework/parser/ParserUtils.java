// Copyright (c) 2015-2019 K Team. All Rights Reserved.
package org.kframework.parser;

import org.apache.commons.io.FileUtils;
import org.kframework.attributes.Att;
import org.kframework.attributes.Source;
import org.kframework.definition.Module;
import org.kframework.definition.ModuleTransformer;
import org.kframework.kil.Definition;
import org.kframework.kil.DefinitionItem;
import org.kframework.kil.Require;
import org.kframework.kil.loader.Context;
import org.kframework.kompile.Kompile;
import org.kframework.kore.K;
import org.kframework.kore.Sort;
import org.kframework.kore.convertors.KILtoKORE;
import org.kframework.main.GlobalOptions;
import org.kframework.parser.inner.ApplySynonyms;
import org.kframework.parser.inner.CollectProductionsVisitor;
import org.kframework.parser.inner.ParseInModule;
import org.kframework.parser.outer.ExtractFencedKCodeFromMarkdown;
import org.kframework.parser.outer.Outer;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;
import org.kframework.utils.options.OuterParsingOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.kframework.Collections.*;
import static org.kframework.kore.KORE.*;

/**
 * A few functions that are a common pattern when calling the new parser.
 */
public class ParserUtils {

    private final KExceptionManager kem;
    private final GlobalOptions options;
    private final FileUtil files;
    private final ExtractFencedKCodeFromMarkdown mdExtractor;

    public ParserUtils(FileUtil files, KExceptionManager kem) {
        this(files, kem, new GlobalOptions(), new OuterParsingOptions());
    }

    public ParserUtils(FileUtil files, KExceptionManager kem, GlobalOptions options, OuterParsingOptions outerParsingOptions) {
        this.kem = kem;
        this.options = options;
        this.files = files;
        mdExtractor = new ExtractFencedKCodeFromMarkdown(this.kem, outerParsingOptions.mdSelector);
    }

    public static K parseWithFile(String theTextToParse,
                                  String mainModule,
                                  Sort startSymbol,
                                  File definitionFile) {
        String definitionText;
        try {
            definitionText = FileUtils.readFileToString(definitionFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return parseWithString(theTextToParse, mainModule, startSymbol, Source.apply(definitionFile.getAbsolutePath()), definitionText);
    }

    public static K parseWithString(String theTextToParse,
                                    String mainModule,
                                    Sort startSymbol,
                                    Source source,
                                    String definitionText) {
        Module kastModule = parseMainModuleOuterSyntax(definitionText, source, mainModule);
        return parseWithModule(theTextToParse, startSymbol, source, kastModule);
    }

    public static K parseWithModule(String theTextToParse,
                                    Sort startSymbol,
                                    Source source,
                                    org.kframework.definition.Module kastModule) {
        ParseInModule parser = new ParseInModule(kastModule);
        return parseWithModule(theTextToParse, startSymbol, source, parser);
    }

    public static K parseWithModule(String theTextToParse,
                                    Sort startSymbol,
                                    Source source,
                                    ParseInModule kastModule) {
        return kastModule.parseString(theTextToParse, startSymbol, source)._1().right().get();
    }

    /**
     * Takes a definition in e-kore textual format and a main module name, and returns the KORE
     * representation of that module. Current implementation uses JavaCC and goes through KIL.
     *
     * @param definitionText textual representation of the modules.
     * @param mainModule     main module name.
     * @return KORE representation of the main module.
     */
    public static Module parseMainModuleOuterSyntax(String definitionText, Source source, String mainModule) {
        Definition def = new Definition();
        def.setItems(Outer.parse(source, definitionText, null));
        def.setMainModule(mainModule);
        def.setMainSyntaxModule(mainModule);

        Context context = new Context();
        new CollectProductionsVisitor(false, context).visit(def);

        KILtoKORE kilToKore = new KILtoKORE(context);
        return kilToKore.apply(def).getModule(mainModule).get();
    }

    public List<org.kframework.kil.Module> slurp(
            String definitionText,
            Source source,
            File currentDirectory,
            List<File> lookupDirectories,
            Set<File> requiredFiles) {
        if (source.source().endsWith(".md")) {
            definitionText = mdExtractor.extract(definitionText, source);
            if (options.debug()) { // save .k files in temp directory
                String fname = new File(source.source()).getName();
                fname = fname.substring(0, fname.lastIndexOf(".md")) + ".k";
                File file = files.resolveTemp(".md2.k/" + fname);
                // if multiple files exists with the same name, append an index
                // and add a comment at the end of the file with the full path
                // Note: the comment is not sent to the parser
                int index = 2;
                while (file.exists())
                    file = files.resolveTemp(".md2.k/" + fname + "_" + index++);
                FileUtil.save(file, definitionText + "\n// " + source.source() + "\n");
            }
        }
        List<DefinitionItem> items = Outer.parse(source, definitionText, null);
        if (options.verbose) {
            System.out.println("Importing: " + source);
        }
        List<org.kframework.kil.Module> results = new ArrayList<>();

        for (DefinitionItem di : items) {
            if (di instanceof org.kframework.kil.Module)
                results.add((org.kframework.kil.Module) di);
            else if (di instanceof Require) {
                // resolve location of the new file

                String definitionFileName = ((Require) di).getValue();

                if (definitionFileName.equals("ffi.k") || definitionFileName.equals("json.k") ||
                    definitionFileName.equals("rat.k") || definitionFileName.equals("substitution.k")) {
                    kem.registerCompilerWarning(ExceptionType.FUTURE_ERROR,
                        "Requiring a K file in the K builtin directory via " +
                        "a deprecated filename. Please replace \"" + definitionFileName +
                        "\" with \"" + definitionFileName.substring(0, definitionFileName.length() - 2) + ".md\".", di);
                    definitionFileName = definitionFileName.substring(0, definitionFileName.length() - 2) + ".md";
                }

                String finalDefinitionFile = definitionFileName;

                ArrayList<File> allLookupDirectories = new ArrayList<>(lookupDirectories);
                allLookupDirectories.add(1, currentDirectory); //after builtin directory but before anything else

                Optional<File> definitionFile = allLookupDirectories.stream()
                        .map(lookupDirectory -> {
                            if (new File(finalDefinitionFile).isAbsolute()) {
                                return new File(finalDefinitionFile);
                            } else {
                                return new File(lookupDirectory, finalDefinitionFile);
                            }
                        })
                        .filter(file -> file.exists()).findFirst();

                if (definitionFile.isPresent()) {
                    File canonical = definitionFile.get().getAbsoluteFile();
                    try {
                        canonical = canonical.getCanonicalFile();
                    } catch (IOException e) {}
                    if (!requiredFiles.contains(canonical)) {
                        requiredFiles.add(canonical);
                        results.addAll(slurp(loadDefinitionText(canonical),
                                Source.apply(canonical.getAbsolutePath()),
                                canonical.getParentFile(),
                                lookupDirectories, requiredFiles));
                    }
                }
                else
                    throw KEMException.criticalError("Could not find file: " +
                            finalDefinitionFile + "\nLookup directories:" + allLookupDirectories, di);
            }
        }
        return results;
    }

    private String loadDefinitionText(File definitionFile) {
        try {
            return FileUtils.readFileToString(files.resolveWorkingDirectory(definitionFile));
        } catch (IOException e) {
            throw KEMException.criticalError(e.getMessage(), e);
        }
    }

    public Set<Module> loadModules(
            Set<Module> previousModules,
            Context context,
            String definitionText,
            Source source,
            File currentDirectory,
            List<File> lookupDirectories,
            Set<File> requiredFiles,
            boolean kore,
            boolean preprocess,
            boolean leftAssoc) {

        List<org.kframework.kil.Module> kilModules =
                slurp(definitionText, source, currentDirectory, lookupDirectories, requiredFiles);

        Definition def = new Definition();
        def.setItems((List<DefinitionItem>) (Object) kilModules);

        new CollectProductionsVisitor(kore, context).visit(def);

        Map<String, List<org.kframework.kil.Module>> groupedModules = kilModules.stream()
          .collect(Collectors.groupingBy(org.kframework.kil.Module::getName));

        List<String> duplicateModules = groupedModules
          .entrySet().stream()
          .filter(e -> e.getValue().size() > 1)
          .map(Map.Entry::getKey)
          .collect(Collectors.toList());

        int errors = 0;
        for (String moduleName : duplicateModules) {
          org.kframework.kil.Module firstMod = groupedModules.get(moduleName).get(0);
          org.kframework.kil.Module secondMod = groupedModules.get(moduleName).get(1);
          KEMException ex = KEMException.outerParserError("Module " + moduleName + " previously declared at " + firstMod.getSource() + " and " + firstMod.getLocation(), secondMod.getSource(), secondMod.getLocation());
          errors++;
          kem.addKException(ex.getKException());
        }

        if (errors > 0) {
          throw KEMException.outerParserError("Had " + errors + " outer parsing errors.");
        }

        if (preprocess) {
          System.out.println(def.toString());
        }

        KILtoKORE kilToKore = new KILtoKORE(context, false, kore, leftAssoc);

        HashMap<String, Module> koreModules = new HashMap<>();
        koreModules.putAll(previousModules.stream().collect(Collectors.toMap(Module::name, m -> m)));
        HashSet<org.kframework.kil.Module> kilModulesSet = new HashSet<>(kilModules);

        Set<Module> finalModules = kilModules.stream().map(m -> kilToKore.apply(m, kilModulesSet, koreModules)).flatMap(m -> Stream.concat(Stream.of(m), Stream.of(koreModules.get(m.name() + "$SYNTAX")))).collect(Collectors.toSet());
        Set<Module> result = new HashSet<>();
        ModuleTransformer applySynonyms = ModuleTransformer.fromSentenceTransformer(new ApplySynonyms()::apply, "Apply sort synonyms");
        for (Module mod : finalModules) {
            result.add(applySynonyms.apply(mod));
        }
        return result;
    }

    public org.kframework.definition.Definition loadDefinition(
            String mainModuleName,
            Set<Module> previousModules,
            String definitionText,
            Source source,
            File currentDirectory,
            List<File> lookupDirectories,
            boolean kore,
            boolean preprocess,
            boolean leftAssoc) {
        Set<Module> modules = loadModules(previousModules, new Context(), definitionText, source, currentDirectory, lookupDirectories, new HashSet<>(), kore, preprocess, leftAssoc);
        Set<Module> allModules = new HashSet<>(modules);
        allModules.addAll(previousModules);
        Module mainModule = getMainModule(mainModuleName, allModules);
        return org.kframework.definition.Definition.apply(mainModule, immutable(allModules), Att.empty());
    }

    public org.kframework.definition.Definition loadDefinition(
            String mainModuleName,
            String syntaxModuleName,
            String definitionText,
            File source,
            File currentDirectory,
            List<File> lookupDirectories,
            boolean autoImportDomains,
            boolean kore,
            boolean preprocess,
            boolean leftAssoc) {
        return loadDefinition(mainModuleName, syntaxModuleName, definitionText,
                Source.apply(source.getAbsolutePath()),
                currentDirectory, lookupDirectories, autoImportDomains, kore, preprocess, leftAssoc);
    }

    public org.kframework.definition.Definition loadDefinition(
            String mainModuleName,
            String syntaxModuleName,
            String definitionText,
            Source source,
            File currentDirectory,
            List<File> lookupDirectories,
            boolean autoImportDomains,
            boolean kore,
            boolean preprocess,
            boolean leftAssoc) {
        Set<Module> previousModules = new HashSet<>();
        Set<File> requiredFiles = new HashSet<>();
        Context context = new Context();
        if (autoImportDomains)
            previousModules.addAll(loadModules(new HashSet<>(), context, Kompile.REQUIRE_PRELUDE_K, Source.apply("Auto imported prelude"), currentDirectory, lookupDirectories, requiredFiles, kore, preprocess, leftAssoc));
        Set<Module> modules = loadModules(previousModules, context, definitionText, source, currentDirectory, lookupDirectories, requiredFiles, kore, preprocess, leftAssoc);
        if (preprocess) {
          System.exit(0);
        }
        modules.addAll(previousModules); // add the previous modules, since load modules is not additive
        Module mainModule = getMainModule(mainModuleName, modules);
        Optional<Module> opt;
        opt = modules.stream().filter(m -> m.name().equals(syntaxModuleName)).findFirst();
        Module syntaxModule;
        if (!opt.isPresent()) {
            kem.registerCompilerWarning(ExceptionType.MISSING_SYNTAX_MODULE, "Could not find main syntax module with name " + syntaxModuleName
                    + " in definition.  Use --syntax-module to specify one. Using " + mainModuleName + " as default.");
            syntaxModule = mainModule;
        } else {
            syntaxModule = opt.get();
        }

        return org.kframework.definition.Definition.apply(mainModule, immutable(modules), Att().add(Att.SYNTAX_MODULE(), syntaxModule.name()));
    }

    private Module getMainModule(String mainModuleName, Set<Module> modules) {
        Optional<Module> opt = modules.stream().filter(m -> m.name().equals(mainModuleName)).findFirst();
        if (!opt.isPresent()) {
            throw KEMException.compilerError("Could not find main module with name " + mainModuleName
                    + " in definition. Use --main-module to specify one.");
        }
        return opt.get();
    }
}
