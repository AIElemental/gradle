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

package org.gradle.integtests.resolve.api

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.extensions.FluidDependenciesResolveInterceptor
import org.gradle.integtests.fixtures.extensions.FluidDependenciesResolveTest

@FluidDependenciesResolveTest
class ArtifactCollectionResultProviderIntegrationTest extends AbstractHttpDependencyResolutionTest {

    def setup() {
        settingsFile << """
            rootProject.name = 'root'
            include 'project-lib'
        """
        mavenRepo.module("org.external", "external-lib").publish()
        file('lib/file-lib.jar') << 'content'
        buildFile << """
            project(':project-lib') {
                apply plugin: 'java'
            }
            configurations {
                compile
            }
            repositories {
                maven { url "${mavenRepo.uri}" }
            }
            dependencies {
                compile 'org.external:external-lib:1.0'
                compile project('project-lib')
                compile files('lib/file-lib.jar')
            }

            abstract class TaskWithArtifactCollectionResultProviderInput extends DefaultTask {

                @InputFiles
                abstract ConfigurableFileCollection getArtifactFiles()

                @InputFiles
                abstract SetProperty<ResolvedArtifactResult> getResolvedArtifacts()

                @OutputFile
                abstract RegularFileProperty getOutputFile()
            }
        """
    }

    def "result provider has artifact files and metadata"() {
        given:
        buildFile << """
            tasks.register('checkArtifacts', TaskWithArtifactCollectionResultProviderInput) {
                artifactFiles.from(configurations.compile.incoming.artifacts.artifactFiles)
                resolvedArtifacts.set(configurations.compile.incoming.artifacts.resolvedArtifacts)
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    def artifactResults = resolvedArtifacts.get()

                    assert artifactResults.size() == 3

                    // Check external artifact
                    def idx = artifactFiles.findIndexOf { it.name == 'external-lib-1.0.jar' }

                    def result = artifactResults[idx]
                    assert result.file == artifactFiles[idx]

                    assert result.id instanceof org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
                    assert result.id.componentIdentifier.group == 'org.external'
                    assert result.id.componentIdentifier.module == 'external-lib'
                    assert result.id.componentIdentifier.version == '1.0'
                    assert result.id.fileName == 'external-lib-1.0.jar'

                    // Check project artifact
                    idx = artifactFiles.findIndexOf { it.name == 'project-lib.jar' }

                    result = artifactResults[idx]
                    assert result.file == artifactFiles[idx]

                    assert result.id.componentIdentifier instanceof ProjectComponentIdentifier
                    assert result.id.componentIdentifier.projectPath == ':project-lib'

                    // Check file artifact
                    idx = artifactFiles.findIndexOf { it.name == 'file-lib.jar' }

                    result = artifactResults[idx]
                    assert result.file == artifactFiles[idx]

                    assert result.id instanceof org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
                    assert result.id.componentIdentifier == result.id
                    assert result.id.displayName == 'file-lib.jar'
                }
            }
        """

        expect:
        run 'checkArtifacts'
    }

    def "failure to resolve artifact collection"() {
        given:
        buildFile << """
            dependencies {
                compile 'org:does-not-exist:1.0'
            }

            task verify(type: TaskWithArtifactCollectionResultProviderInput) {
                artifactFiles.from(configurations.compile.incoming.artifacts.artifactFiles)
                resolvedArtifacts.set(configurations.compile.incoming.artifacts.resolvedArtifacts)
                outputFile.set(layout.buildDirectory.file('output.txt'))
                doLast {
                    assert resolvedArtifacts.get().size == 3
                }
            }
        """

        when:
        succeeds "help"

        and:
        fails "verify"

        then:
        if (FluidDependenciesResolveInterceptor.isFluid()) {
            failure.assertHasCause("Could not resolve all task dependencies for configuration ':compile'.")
        } else {
            failure.assertHasCause("Could not resolve all files for configuration ':compile'.")
        }
        failure.assertHasCause("Could not find org:does-not-exist:1.0.")
    }


    def "task is not up-to-date when #useCase changes"() {
        given:
        buildFile << """
            task verify(type: TaskWithArtifactCollectionResultProviderInput) {
                $taskConfiguration
                outputFile.set(layout.buildDirectory.file('output.txt'))
            }
"""
        def sourceFile = file("project-lib/src/main/java/Main.java")
        sourceFile << """
class Main {}
"""
        sourceFile.makeOlder()

        when:
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        when:
        succeeds "verify"

        then:
        skipped ":project-lib:jar", ":verify"

        when:
        sourceFile.text = """
class Main {
    public static void main(String[] args) {}
}
"""
        succeeds "verify"

        then:
        executedAndNotSkipped ":project-lib:jar", ":verify"

        where:
        useCase           | taskConfiguration
        'files input'     | 'artifactFiles.from(configurations.compile.incoming.artifacts.artifactFiles)\nresolvedArtifacts.empty()'
        'artifacts input' | 'resolvedArtifacts.set(configurations.compile.incoming.artifacts.resolvedArtifacts)'
    }
}
