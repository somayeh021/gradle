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

package org.gradle.api.internal.initialization.transform.services;

import com.google.common.collect.Sets;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.initialization.transform.utils.InstrumentationAnalysisSerializer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.internal.classpath.types.ExternalPluginsInstrumentationTypeRegistry;
import org.gradle.internal.classpath.types.InstrumentationTypeRegistry;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.lazy.Lazy;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.gradle.api.internal.initialization.transform.utils.InstrumentationTransformUtils.SUPER_TYPES_FILE_NAME;

public abstract class CacheInstrumentationDataBuildService implements BuildService<BuildServiceParameters.None> {

    /**
     * Can be removed once we actually have some upgrades, but without upgrades we currently can't test this
     */
    public static final String GENERATE_CLASS_HIERARCHY_WITHOUT_UPGRADES_PROPERTY = "org.gradle.internal.instrumentation.generateClassHierarchyWithoutUpgrades";

    private final Map<Long, ResolutionData> resolutionData = new ConcurrentHashMap<>();
    private final Lazy<InjectedInstrumentationServices> internalServices = Lazy.locking().of(() -> getObjectFactory().newInstance(InjectedInstrumentationServices.class));

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    public InstrumentationTypeRegistry getInstrumentationTypeRegistry(long contextId) {
        InstrumentationTypeRegistry gradleCoreInstrumentationTypeRegistry = internalServices.get().getGradleCoreInstrumentationTypeRegistry();
        if (!Boolean.getBoolean(GENERATE_CLASS_HIERARCHY_WITHOUT_UPGRADES_PROPERTY) && gradleCoreInstrumentationTypeRegistry.isEmpty()) {
            // In case core types registry is empty, it means we don't have any upgrades
            // in Gradle core, so we can return empty registry
            return InstrumentationTypeRegistry.empty();
        }
        return getResolutionData(contextId).getInstrumentationTypeRegistry();
    }

    /**
     * Returns the original file for the given hash. It's possible that multiple files have the same content,
     * so this method returns just one. For instrumentation is not important which one is returned.
     */
    public File getOriginalFile(long contextId, String hash) {
        return checkNotNull(getResolutionData(contextId).getOriginalFile(hash), "Original file for hash '%s' does not exist!", hash);
    }

    @Nullable
    public String getArtifactHash(long contextId, File file) {
        return getResolutionData(contextId).getArtifactHash(file);
    }

    public FileCollection getOriginalClasspath(long contextId) {
        return getResolutionData(contextId).getOriginalClasspath();
    }

    private ResolutionData getResolutionData(long contextId) {
        return checkNotNull(resolutionData.get(contextId), "Resolution data for id %s does not exist!", contextId);
    }

    public ResolutionScope newResolutionScope(long contextId) {
        ResolutionData resolutionData = this.resolutionData.compute(contextId, (__, value) -> {
            checkArgument(value == null, "Resolution data for id %s already exists! Was previous resolution scope closed properly?", contextId);
            return getObjectFactory().newInstance(ResolutionData.class, internalServices.get());
        });
        return new ResolutionScope() {
            @Override
            public void setAnalysisResult(FileCollection analysisResult) {
                resolutionData.getAnalysisResult().setFrom(analysisResult);
            }

            @Override
            public void setOriginalClasspath(FileCollection originalClasspath) {
                resolutionData.getOriginalClasspath().setFrom(originalClasspath);
            }

            @Override
            public void close() {
                CacheInstrumentationDataBuildService.this.resolutionData.remove(contextId);
            }
        };
    }

    public interface ResolutionScope extends AutoCloseable {

        void setAnalysisResult(FileCollection analysisResult);
        void setOriginalClasspath(FileCollection originalClasspath);

        @Override
        void close();
    }

    abstract static class ResolutionData {
        private final Lazy<InstrumentationTypeRegistry> instrumentationTypeRegistry;
        private final Lazy<Map<String, File>> hashToOriginalFile;
        private final Map<File, String> hashCache;
        private final InjectedInstrumentationServices internalServices;

        @Inject
        public ResolutionData(InjectedInstrumentationServices internalServices) {
            this.hashCache = new ConcurrentHashMap<>();
            this.hashToOriginalFile = Lazy.locking().of(() -> {
                Map<String, File> originalFiles = new HashMap<>(getOriginalClasspath().getFiles().size());
                getOriginalClasspath().forEach(file -> {
                    String fileHash = getArtifactHash(file);
                    if (fileHash != null) {
                        originalFiles.put(fileHash, file);
                    }
                });
                return originalFiles;
            });
            this.instrumentationTypeRegistry = Lazy.locking().of(() -> {
                InstrumentationTypeRegistry gradleCoreInstrumentationTypeRegistry = internalServices.getGradleCoreInstrumentationTypeRegistry();
                Map<String, Set<String>> directSuperTypes = readDirectSuperTypes();
                return new ExternalPluginsInstrumentationTypeRegistry(directSuperTypes, gradleCoreInstrumentationTypeRegistry);
            });
            this.internalServices = internalServices;
        }

        public abstract ConfigurableFileCollection getAnalysisResult();
        public abstract ConfigurableFileCollection getOriginalClasspath();

        private Map<String, Set<String>> readDirectSuperTypes() {
            Set<File> directories = getAnalysisResult().getFiles();
            InstrumentationAnalysisSerializer serializer = new InstrumentationAnalysisSerializer(internalServices.getStringInterner());
            return directories.stream()
                .filter(File::isDirectory)
                .map(dir -> new File(dir, SUPER_TYPES_FILE_NAME))
                .flatMap(file -> serializer.readTypesMap(file).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Sets::union));
        }

        public InstrumentationTypeRegistry getInstrumentationTypeRegistry() {
            return instrumentationTypeRegistry.get();
        }

        @Nullable
        public File getOriginalFile(String hash) {
            return hashToOriginalFile.get().get(hash);
        }

        @Nullable
        public String getArtifactHash(File file) {
            return hashCache.computeIfAbsent(file, __ -> {
                Hasher hasher = Hashing.newHasher();
                FileSystemLocationSnapshot snapshot = internalServices.getFileSystemAccess().read(file.getAbsolutePath());
                if (snapshot.getType() == FileType.Missing) {
                    return null;
                }

                hasher.putHash(snapshot.getHash());
                hasher.putString(file.getName());
                hasher.putBoolean(internalServices.getGlobalCacheLocations().isInsideGlobalCache(file.getAbsolutePath()));
                return hasher.hash().toString();
            });
        }

    }
}
