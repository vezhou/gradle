/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.impl.steps;

import org.gradle.internal.Try;
import org.gradle.internal.cleanup.BuildOutputCleanupRegistry;
import org.gradle.internal.execution.ExecutionOutcome;
import org.gradle.internal.execution.OutputChangeListener;
import org.gradle.internal.execution.Result;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.util.GFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class RemoveOutputsStep<C extends Context> implements Step<C, Result> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoveOutputsStep.class);

    private final OutputChangeListener outputChangeListener;
    private final BuildOutputCleanupRegistry buildOutputCleanupRegistry;
    private final Step<? super C, ? extends Result> delegate;

    public RemoveOutputsStep(OutputChangeListener outputChangeListener, BuildOutputCleanupRegistry buildOutputCleanupRegistry, Step<? super C, ? extends Result> delegate) {
        this.outputChangeListener = outputChangeListener;
        this.buildOutputCleanupRegistry = buildOutputCleanupRegistry;
        this.delegate = delegate;
    }

    @Override
    public Result execute(C context) {
        UnitOfWork work = context.getWork();
        boolean deletedFiles;
        if (work.deleteOutputsBeforeExecution()) {
            deletedFiles = work.getAfterPreviousExecutionState().map(afterPreviousExecutionState -> {
                boolean hasOverlappingOutputs = work.hasOverlappingOutputs();
                if (hasOverlappingOutputs) {
                    LOGGER.info("No leftover directories for {} will be deleted since overlapping outputs were detected.", work.getDisplayName());
                }
                outputChangeListener.beforeOutputChange();
                boolean deletedFile = false;
                boolean debugEnabled = LOGGER.isDebugEnabled();

                for (FileCollectionFingerprint outputFingerprints : afterPreviousExecutionState.getOutputFileProperties().values()) {
                    for (String outputPath : outputFingerprints.getFingerprints().keySet()) {
                        File file = new File(outputPath);
                        if (file.exists() && buildOutputCleanupRegistry.isOutputOwnedByBuild(file)) {
                            if (hasOverlappingOutputs && file.isDirectory()) {
                                continue;
                            }
                            if (debugEnabled) {
                                LOGGER.debug("Deleting stale output file '{}'.", file.getAbsolutePath());
                            }
                            GFileUtils.forceDelete(file);
                            deletedFile = true;
                        }
                    }
                }
                return deletedFile;
            }).orElse(false);
        } else {
            deletedFiles = false;
        }
        Result result = delegate.execute(context);
        return new Result() {
            @Override
            public Try<ExecutionOutcome> getOutcome() {
                return result.getOutcome().map(outcome -> deletedFiles
                    ? ExecutionOutcome.EXECUTED
                    : outcome
                );
            }
        };
    }
}
