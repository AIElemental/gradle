/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.projectmodule;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.internal.Describables;
import org.gradle.internal.component.local.model.LocalComponentMetadata;
import org.gradle.internal.model.CalculatedValueContainer;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.model.ValueCalculator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLocalComponentRegistry implements LocalComponentRegistry {
    private final ProjectStateRegistry projectStateRegistry;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final Map<ProjectComponentIdentifier, CalculatedValueContainer<LocalComponentMetadata, ?>> projects = new ConcurrentHashMap<>();
    private final List<LocalComponentProvider> providers;

    public DefaultLocalComponentRegistry(
        ProjectStateRegistry projectStateRegistry,
        CalculatedValueContainerFactory calculatedValueContainerFactory,
        List<LocalComponentProvider> providers
    ) {
        this.projectStateRegistry = projectStateRegistry;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
        this.providers = providers;
    }

    @Override
    public LocalComponentMetadata getComponent(ProjectComponentIdentifier projectIdentifier) {
        CalculatedValueContainer<LocalComponentMetadata, ?> valueContainer = projects.computeIfAbsent(projectIdentifier, projectComponentIdentifier -> {
            ProjectState projectState = projectStateRegistry.stateFor(projectIdentifier);
            return calculatedValueContainerFactory.create(Describables.of("metadata of", projectIdentifier), new MetadataSupplier(projectState));
        });
        // Calculate the value after adding the entry to the map, so that the value container can take care of thread synchronization
        valueContainer.finalizeIfNotAlready();
        return valueContainer.get();
    }

    private class MetadataSupplier implements ValueCalculator<LocalComponentMetadata> {
        private final ProjectState projectState;

        public MetadataSupplier(ProjectState projectState) {
            this.projectState = projectState;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }

        @Override
        public boolean usesMutableProjectState() {
            return false;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return null;
        }

        @Override
        public LocalComponentMetadata calculateValue(NodeExecutionContext context) {
            for (LocalComponentProvider provider : providers) {
                LocalComponentMetadata componentMetaData = provider.getComponent(projectState);
                if (componentMetaData != null) {
                    return componentMetaData;
                }
            }
            throw new IllegalArgumentException(projectState.getComponentIdentifier() + " not found.");
        }
    }
}
