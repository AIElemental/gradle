/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.configurationcache

import org.gradle.api.internal.BuildDefinition
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.DefaultLocalComponentRegistry
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentProvider
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.configuration.ProjectsPreparer
import org.gradle.configuration.ScriptPluginFactory
import org.gradle.configuration.project.BuildScriptProcessor
import org.gradle.configuration.project.ConfigureActionsProjectEvaluator
import org.gradle.configuration.project.DelayedConfigurationActions
import org.gradle.configuration.project.LifecycleProjectEvaluator
import org.gradle.configuration.project.PluginsProjectConfigureActions
import org.gradle.configuration.project.ProjectEvaluator
import org.gradle.configurationcache.build.ConfigurationCacheIncludedBuildState
import org.gradle.configurationcache.build.NoOpBuildModelController
import org.gradle.configurationcache.extensions.get
import org.gradle.configurationcache.fingerprint.ConfigurationCacheFingerprintController
import org.gradle.execution.DefaultTaskSchedulingPreparer
import org.gradle.execution.ExcludedTaskFilteringProjectsPreparer
import org.gradle.initialization.BuildCancellationToken
import org.gradle.initialization.SettingsPreparer
import org.gradle.initialization.TaskExecutionPreparer
import org.gradle.initialization.VintageBuildModelController
import org.gradle.internal.build.BuildLifecycleController
import org.gradle.internal.build.BuildLifecycleControllerFactory
import org.gradle.internal.build.BuildModelController
import org.gradle.internal.build.BuildModelControllerServices
import org.gradle.internal.build.BuildState
import org.gradle.internal.buildtree.BuildModelParameters
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.model.StateTransitionControllerFactory
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.service.CachingServiceLocator
import org.gradle.internal.service.scopes.BuildScopeServices
import org.gradle.internal.service.scopes.ServiceRegistryFactory
import org.gradle.invocation.DefaultGradle


class DefaultBuildModelControllerServices(
    private val buildModelParameters: BuildModelParameters,
) : BuildModelControllerServices {
    override fun servicesForBuild(buildDefinition: BuildDefinition, owner: BuildState, parentBuild: BuildState?): BuildModelControllerServices.Supplier {
        return BuildModelControllerServices.Supplier { registration, services ->
            registration.add(BuildDefinition::class.java, buildDefinition)
            registration.add(BuildState::class.java, owner)
            registration.addProvider(ServicesProvider(buildDefinition, parentBuild, services))
            if (buildModelParameters.isProjectScopeModelCache) {
                registration.addProvider(CacheProjectServicesProvider())
            } else {
                registration.addProvider(VintageProjectServicesProvider())
            }
            if (buildModelParameters.isConfigurationCache) {
                registration.addProvider(ConfigurationCacheServicesProvider())
            } else {
                registration.addProvider(VintageServicesProvider())
            }
        }
    }

    private
    class ServicesProvider(
        private val buildDefinition: BuildDefinition,
        private val parentBuild: BuildState?,
        private val buildScopeServices: BuildScopeServices
    ) {
        fun createGradleModel(instantiator: Instantiator, serviceRegistryFactory: ServiceRegistryFactory): GradleInternal? {
            return instantiator.newInstance(
                DefaultGradle::class.java,
                parentBuild,
                buildDefinition.startParameter,
                serviceRegistryFactory
            )
        }

        fun createBuildLifecycleController(buildLifecycleControllerFactory: BuildLifecycleControllerFactory): BuildLifecycleController {
            return buildLifecycleControllerFactory.newInstance(buildDefinition, buildScopeServices)
        }
    }

    private
    class ConfigurationCacheServicesProvider {
        fun createBuildModelController(
            build: BuildState,
            gradle: GradleInternal,
            stateTransitionControllerFactory: StateTransitionControllerFactory,
            cache: BuildTreeConfigurationCache
        ): BuildModelController {
            if (build is ConfigurationCacheIncludedBuildState) {
                return NoOpBuildModelController(gradle)
            }
            val vintageController = VintageServicesProvider().createBuildModelController(build, gradle, stateTransitionControllerFactory)
            return ConfigurationCacheAwareBuildModelController(gradle, vintageController, cache)
        }
    }

    private
    class VintageServicesProvider {
        fun createBuildModelController(
            build: BuildState,
            gradle: GradleInternal,
            stateTransitionControllerFactory: StateTransitionControllerFactory
        ): BuildModelController {
            if (build is ConfigurationCacheIncludedBuildState) {
                return NoOpBuildModelController(gradle)
            }
            val projectsPreparer: ProjectsPreparer = gradle.services.get()
            val taskSchedulingPreparer = DefaultTaskSchedulingPreparer(ExcludedTaskFilteringProjectsPreparer(gradle.services.get()))
            val settingsPreparer: SettingsPreparer = gradle.services.get()
            val taskExecutionPreparer: TaskExecutionPreparer = gradle.services.get()
            return VintageBuildModelController(gradle, projectsPreparer, taskSchedulingPreparer, settingsPreparer, taskExecutionPreparer, stateTransitionControllerFactory)
        }
    }

    private
    class CacheProjectServicesProvider {
        fun createProjectEvaluator(
            buildOperationExecutor: BuildOperationExecutor,
            cachingServiceLocator: CachingServiceLocator,
            scriptPluginFactory: ScriptPluginFactory,
            fingerprintController: ConfigurationCacheFingerprintController,
            cancellationToken: BuildCancellationToken
        ): ProjectEvaluator {
            val evaluator = VintageProjectServicesProvider().createProjectEvaluator(buildOperationExecutor, cachingServiceLocator, scriptPluginFactory, cancellationToken)
            return ConfigurationCacheAwareProjectEvaluator(evaluator, fingerprintController)
        }

        fun createLocalComponentRegistry(
            projectStateRegistry: ProjectStateRegistry,
            calculatedValueContainerFactory: CalculatedValueContainerFactory,
            cache: BuildTreeConfigurationCache,
            providers: List<LocalComponentProvider>
        ): LocalComponentRegistry {
            val effectiveProviders = listOf(ConfigurationCacheAwareLocalComponentProvider(providers, cache))
            return DefaultLocalComponentRegistry(projectStateRegistry, calculatedValueContainerFactory, effectiveProviders)
        }
    }

    private
    class VintageProjectServicesProvider {
        fun createProjectEvaluator(
            buildOperationExecutor: BuildOperationExecutor,
            cachingServiceLocator: CachingServiceLocator,
            scriptPluginFactory: ScriptPluginFactory,
            cancellationToken: BuildCancellationToken
        ): ProjectEvaluator {
            val withActionsEvaluator = ConfigureActionsProjectEvaluator(
                PluginsProjectConfigureActions.from(cachingServiceLocator),
                BuildScriptProcessor(scriptPluginFactory),
                DelayedConfigurationActions()
            )
            return LifecycleProjectEvaluator(buildOperationExecutor, withActionsEvaluator, cancellationToken)
        }

        fun createLocalComponentRegistry(
            projectStateRegistry: ProjectStateRegistry,
            calculatedValueContainerFactory: CalculatedValueContainerFactory,
            providers: List<LocalComponentProvider>
        ): LocalComponentRegistry {
            return DefaultLocalComponentRegistry(projectStateRegistry, calculatedValueContainerFactory, providers)
        }
    }
}
