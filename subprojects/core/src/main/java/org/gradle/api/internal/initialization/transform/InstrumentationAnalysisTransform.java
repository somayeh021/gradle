/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.api.internal.initialization.transform;

import com.google.common.collect.Ordering;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.initialization.transform.services.CacheInstrumentationDataBuildService;
import org.gradle.api.internal.initialization.transform.services.InjectedInstrumentationServices;
import org.gradle.api.internal.initialization.transform.utils.ClassAnalysisUtils;
import org.gradle.api.internal.initialization.transform.utils.InstrumentationAnalysisSerializer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.internal.classpath.ClasspathWalker;
import org.gradle.internal.file.FileException;
import org.gradle.internal.lazy.Lazy;
import org.gradle.work.DisableCachingByDefault;
import org.objectweb.asm.ClassReader;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.ANALYSIS_OUTPUT_DIR;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.DEPENDENCIES_FILE_NAME;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.FILE_MISSING_HASH;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.METADATA_FILE_NAME;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.SUPER_TYPES_FILE_NAME;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.createInstrumentationClasspathMarker;
import static org.gradle.internal.classpath.transforms.MrJarUtils.isInUnsupportedMrJarVersionedDirectory;

/**
 * A transform that analyzes an artifact: it discovers all super types for classes in an artifact and all class dependencies.<br><br>
 *
 * Outputs 4 files:<br>
 * 1. Instrumentation classpath marker file.<br>
 * 2. A properties file with original file hash and original file name.<br>
 * 3. A file with all classes that this artifact depends on.<br>
 * 4. A properties file with all direct super types for every class in an artifact.<br><br>
 *
 * A file with all direct super types is a properties file like:<br>
 * [class name 1]=[super type 1],[super type 2],...<br>
 * [class name 2]=[super type 1],[super type 2],...<br>
 * ...
 */
@DisableCachingByDefault(because = "Not worth caching.")
public abstract class InstrumentationAnalysisTransform implements TransformAction<InstrumentationAnalysisTransform.Parameters> {

    public interface Parameters extends TransformParameters {
        @Internal
        Property<CacheInstrumentationDataBuildService> getBuildService();
        @Internal
        Property<Long> getContextId();
    }
    private static final Predicate<String> ACCEPTED_TYPES = type -> type != null && !type.startsWith("java/lang/");

    private final Lazy<InjectedInstrumentationServices> internalServices = Lazy.unsafe().of(() -> getObjects().newInstance(InjectedInstrumentationServices.class));

    @Inject
    protected abstract ObjectFactory getObjects();

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInput();

    @Override
    public void transform(TransformOutputs outputs) {
        File artifact = getInput().get().getAsFile();
        if (!artifact.exists()) {
            // Files can be passed to the artifact transform even if they don't exist,
            // in the case when user adds a file classpath via files("path/to/jar").
            // Unfortunately we don't filter them out before the artifact transform is run.
            writeOutput(artifact, outputs, Collections.emptyMap(), Collections.emptySet());
            return;
        }

        try {
            Map<String, Set<String>> superTypes = new TreeMap<>();
            Set<String> dependencies = new TreeSet<>();
            analyzeArtifact(artifact, superTypes, dependencies);
            writeOutput(artifact, outputs, superTypes, dependencies);
        } catch (IOException | FileException ignored) {
            // We support badly formatted jars on the build classpath
            // see: https://github.com/gradle/gradle/issues/13816
            writeOutput(artifact, outputs, Collections.emptyMap(), Collections.emptySet());
        }
    }

    private void analyzeArtifact(File artifact, Map<String, Set<String>> superTypesCollector, Set<String> dependenciesCollector) throws IOException {
        // We cannot inject internal services in to the transform directly, but we can create them via object factory
        ClasspathWalker walker = internalServices.get().getClasspathWalker();
        walker.visit(artifact, entry -> {
            if (entry.getName().endsWith(".class") && !isInUnsupportedMrJarVersionedDirectory(entry)) {
                ClassReader reader = new ClassReader(entry.getContent());
                String className = reader.getClassName();
                Set<String> classSuperTypes = collectSuperTypes(reader);
                collectArtifactClassDependencies(className, reader, dependenciesCollector);
                if (!classSuperTypes.isEmpty()) {
                    superTypesCollector.put(className, classSuperTypes);
                }
            }
        });
    }

    private static Set<String> collectSuperTypes(ClassReader reader) {
        return Stream.concat(Stream.of(reader.getSuperName()), Stream.of(reader.getInterfaces()))
            .filter(ACCEPTED_TYPES)
            .collect(toImmutableSortedSet(Ordering.natural()));
    }

    private static void collectArtifactClassDependencies(String className, ClassReader reader, Set<String> collector) {
        ClassAnalysisUtils.getClassDependencies(reader, dependencyDescriptor -> {
            if (!dependencyDescriptor.equals(className) && ACCEPTED_TYPES.test(dependencyDescriptor)) {
                collector.add(dependencyDescriptor);
            }
        });
    }

    private void writeOutput(File artifact, TransformOutputs outputs, Map<String, Set<String>> superTypes, Set<String> dependencies) {
        createInstrumentationClasspathMarker(outputs);

        StringInterner stringInterner = internalServices.get().getStringInterner();
        InstrumentationAnalysisSerializer serializer = new InstrumentationAnalysisSerializer(stringInterner);
        File outputDir = outputs.dir(ANALYSIS_OUTPUT_DIR);

        File metadataFile = new File(outputDir, METADATA_FILE_NAME);
        serializer.writeMetadata(metadataFile, getArtifactMetadata(artifact));

        File superTypesFile = new File(outputDir, SUPER_TYPES_FILE_NAME);
        serializer.writeTypesMap(superTypesFile, superTypes);

        File dependenciesFile = new File(outputDir, DEPENDENCIES_FILE_NAME);
        serializer.writeTypes(dependenciesFile, dependencies);
    }

    private InstrumentationArtifactMetadata getArtifactMetadata(File artifact) {
        long contextId = getParameters().getContextId().get();
        CacheInstrumentationDataBuildService buildService = getParameters().getBuildService().get();
        String hash = firstNonNull(buildService.getArtifactHash(contextId, artifact), FILE_MISSING_HASH);
        return new InstrumentationArtifactMetadata(artifact.getName(), hash);
    }
}
