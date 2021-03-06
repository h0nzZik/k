// Copyright (c) 2015-2019 K Team. All Rights Reserved.

package org.kframework.kprove;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.kframework.unparser.PrintOptions;
import org.kframework.main.GlobalOptions;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.file.FileUtil;
import org.kframework.utils.inject.RequestScoped;
import org.kframework.utils.options.BackendOptions;
import org.kframework.utils.options.DefinitionLoadingOptions;
import org.kframework.utils.options.SMTOptions;

import java.io.File;
import java.util.Collections;
import java.util.List;

@RequestScoped
public class KProveOptions {

    @ParametersDelegate
    public transient GlobalOptions global = new GlobalOptions();

    @ParametersDelegate
    public DefinitionLoadingOptions definitionLoading = new DefinitionLoadingOptions();

    @Parameter(description="<file>")
    private List<String> parameters;

    private File specFile;

    public synchronized File specFile(FileUtil files) {
        if (specFile == null) {
            if (parameters == null || parameters.size() == 0) {
                throw KEMException.criticalError("You have to provide exactly one main file in order to do outer parsing.");
            }
            specFile = files.resolveWorkingDirectory(parameters.get(0));
        }
        return specFile;
    }

    @ParametersDelegate
    public BackendOptions backend = new BackendOptions();

    @ParametersDelegate
    public SMTOptions smt = new SMTOptions();

    @ParametersDelegate
    public PrintOptions print = new PrintOptions();

    @Parameter(names={"--spec-module", "-sm"}, description="Name of module containing specification to prove")
    public String specModule;

    @Parameter(names={"--def-module", "-m"}, description="Name of module containing definition to prove under")
    public String defModule;

    @Parameter(names={"--save-proof-definition-to"}, description="Save the binary version of full definition used " +
            "for proving this spec. This can be used by other external tools, e.g., kast. The parameter should be " +
            "the path to a directory not containing other -kompiled directories. A new directory named " +
            "proof-spec-kompiled will be created in that directory, containing the compiled definition.")
    public String saveProofDefinitionTo = null;

    @Parameter(names="--depth", description="The maximum number of computational steps to prove")
    public Integer depth;

    @Parameter(names = "--boundary-cells", description = "The comma-separated list of cells used to mark the boundary " +
            "of evaluation. If option is specified, execution ends when these cells in the current term match same " +
            "cells in the target term. (Except for step 1, for which boundary checking is disabled.)" +
            "If the whole current term matches target, execution is successful. " +
            "Otherwise it fails on the present path. If option is not specified, full target term implication is checked " +
            "on every step. In most specifications boundary is marked by \"k\".")
    public List<String> boundaryCells = Collections.emptyList();

    @Parameter(names="--concrete-rules", description="List of rule labels to be considered concrete, in addition to " +
            "rules marked with `[concrete]` attribute")
    public List<String> extraConcreteRuleLabels = Collections.emptyList();

    @Parameter(names="--debugger", description="Launch proof in an interactive debugger. Currently only supported by the Haskell backend.")
    public boolean debugger;

    @Parameter(names="--debug-script", description="Run script passed in specified file when the debugger starts. Used with --debugger.")
    public String debugScript;
}
