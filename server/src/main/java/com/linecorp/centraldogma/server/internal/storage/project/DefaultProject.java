/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.storage.project;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.internal.storage.MigratingMetaToDogmaRepositoryService.META_TO_DOGMA_MIGRATED;
import static com.linecorp.centraldogma.server.internal.storage.MigratingMetaToDogmaRepositoryService.META_TO_DOGMA_MIGRATION_JOB;
import static com.linecorp.centraldogma.server.metadata.MetadataService.METADATA_JSON;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.CachingRepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;
import com.linecorp.centraldogma.server.metadata.Member;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public class DefaultProject implements Project {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProject.class);

    private final String name;
    private final long creationTimeMillis;
    private final Author author;
    final RepositoryManager repos;

    @SuppressWarnings("NotNullFieldNotInitialized")
    private volatile MetaRepository metaRepo;

    @Nullable
    private volatile Revision lastMetadataRevision;
    @Nullable
    private volatile ProjectMetadata projectMetadata;

    /**
     * Opens an existing project.
     */
    DefaultProject(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                   @Nullable RepositoryCache cache, EncryptionStorageManager encryptionStorageManager) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        if (!rootDir.exists()) {
            throw new ProjectNotFoundException(rootDir.toString());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, purgeWorker, cache, encryptionStorageManager);
        if (!repos.exists(REPO_DOGMA)) {
            throw new IllegalStateException(
                    "The project does not have a dogma repository: " + rootDir);
        }

        boolean success = false;
        try {
            final ProjectMetadata projectedMetadata = initialMetadata();
            if (projectedMetadata != null) {
                final UserAndTimestamp creation = projectedMetadata.creation();
                creationTimeMillis = creation.timestampMillis();
                author = Author.ofEmail(creation.user());
                attachMetadataListener();
                resetMetaRepository();
            } else {
                creationTimeMillis = repos.get(REPO_DOGMA).creationTimeMillis();
                author = repos.get(REPO_DOGMA).author();
            }
            success = true;
        } catch (Exception e) {
            throw new CentralDogmaException("failed to initialize internal repositories of " + name, e);
        } finally {
            if (!success) {
                repos.close(() -> new CentralDogmaException(
                        "failed to initialize internal repositories of " + name));
            }
        }
    }

    /**
     * Creates a new project.
     */
    DefaultProject(@Nullable Project dogmaProject, File rootDir,
                   Executor repositoryWorker, Executor purgeWorker,
                   long creationTimeMillis, Author author, @Nullable RepositoryCache cache,
                   EncryptionStorageManager encryptionStorageManager, boolean encryptDogmaRepo) {
        requireNonNull(rootDir, "rootDir");
        requireNonNull(repositoryWorker, "repositoryWorker");
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        if (rootDir.exists()) {
            throw new ProjectExistsException(rootDir.getName());
        }

        name = rootDir.getName();
        repos = newRepoManager(rootDir, repositoryWorker, purgeWorker, cache, encryptionStorageManager);

        final boolean useDogmaRepoAsMetaRepo;
        if (dogmaProject == null) {
            useDogmaRepoAsMetaRepo = false;
        } else {
            final Repository dogmaProjectDogmaRepository = dogmaProject.repos().get(REPO_DOGMA);
            final Entry<JsonNode> entry = dogmaProjectDogmaRepository.getOrNull(
                    Revision.HEAD, Query.ofJson(META_TO_DOGMA_MIGRATION_JOB)).join();
            useDogmaRepoAsMetaRepo = entry != null;
        }

        boolean success = false;
        try {
            createReservedRepos(creationTimeMillis, useDogmaRepoAsMetaRepo, encryptDogmaRepo);
            initializeMetadata(creationTimeMillis, author);
            this.creationTimeMillis = creationTimeMillis;
            this.author = author;
            attachMetadataListener();
            setMetaRepository(useDogmaRepoAsMetaRepo);
            success = true;
        } finally {
            if (!success) {
                repos.close(() -> new CentralDogmaException(
                        "failed to initialize internal repositories of " + name));
            }
        }
    }

    private RepositoryManager newRepoManager(File rootDir, Executor repositoryWorker, Executor purgeWorker,
                                             @Nullable RepositoryCache cache,
                                             EncryptionStorageManager encryptionStorageManager) {
        // Enable caching if 'cache' is not null.
        final GitRepositoryManager gitRepos =
                new GitRepositoryManager(this, rootDir, repositoryWorker, purgeWorker, cache,
                                         encryptionStorageManager);
        return cache == null ? gitRepos : new CachingRepositoryManager(gitRepos, cache);
    }

    private void createReservedRepos(long creationTimeMillis, boolean useDogmaRepoAsMetaRepo,
                                     boolean encryptDogmaRepo) {
        if (!repos.exists(REPO_DOGMA)) {
            try {
                final Repository dogmaRepository =
                        repos.create(REPO_DOGMA, creationTimeMillis, Author.SYSTEM, encryptDogmaRepo);
                if (useDogmaRepoAsMetaRepo) {
                    dogmaRepository.commit(
                            Revision.HEAD, creationTimeMillis, Author.SYSTEM,
                            "Add " + META_TO_DOGMA_MIGRATED + " file to dogma repository", "", Markup.PLAINTEXT,
                            Change.ofJsonUpsert(META_TO_DOGMA_MIGRATED, "{}"))
                                   .join();
                }
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
        if (!useDogmaRepoAsMetaRepo && !repos.exists(REPO_META)) {
            try {
                repos.create(REPO_META, creationTimeMillis, Author.SYSTEM, encryptDogmaRepo);
            } catch (RepositoryExistsException ignored) {
                // Just in case there's a race.
            }
        }
    }

    @Nullable
    @Override
    public ProjectMetadata metadata() {
        // projectMetadata is null only when the project is dogma project.
        return projectMetadata;
    }

    private void initializeMetadata(long creationTimeMillis, Author author) {
        // Do not generate a metadata file for internal projects.
        if (name.equals(INTERNAL_PROJECT_DOGMA)) {
            return;
        }

        final Repository dogmaRepo = repos.get(REPO_DOGMA);
        final Revision headRev = dogmaRepo.normalizeNow(Revision.HEAD);
        if (!dogmaRepo.exists(headRev, METADATA_JSON).join()) {
            logger.info("Initializing metadata of project: {}", name);

            final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);
            final Member member = new Member(author, ProjectRole.OWNER, userAndTimestamp);
            final ProjectMetadata metadata = new ProjectMetadata(name,
                                                                 ImmutableMap.of(),
                                                                 ImmutableMap.of(member.id(), member),
                                                                 ImmutableMap.of(),
                                                                 userAndTimestamp, null);
            final CommitResult result =
                    dogmaRepo.commit(headRev, creationTimeMillis, Author.SYSTEM,
                                     "Initialize metadata", "",
                                     Markup.PLAINTEXT,
                                     Change.ofJsonUpsert(METADATA_JSON, Jackson.valueToTree(metadata)))
                             .join();
            lastMetadataRevision = result.revision();
            projectMetadata = metadata;
        }
    }

    @Nullable
    private ProjectMetadata initialMetadata()
            throws ExecutionException, InterruptedException, JsonProcessingException {
        if (name.equals(INTERNAL_PROJECT_DOGMA)) {
            return null;
        }
        final Entry<JsonNode> metadata = repos.get(REPO_DOGMA).get(Revision.HEAD, Query.ofJson(METADATA_JSON))
                                              .get();
        final ProjectMetadata projectMetadata = Jackson.treeToValue(metadata.content(),
                                                                    ProjectMetadata.class);
        lastMetadataRevision = metadata.revision();
        this.projectMetadata = projectMetadata;
        return projectMetadata;
    }

    /**
     * Listens to new changes for "metadata.json" and updates the information to {@link #lastMetadataRevision}
     * and {@link #projectMetadata}.
     */
    private void attachMetadataListener() {
        if (name.equals(INTERNAL_PROJECT_DOGMA)) {
            return;
        }

        final Repository dogmaRepo = repos.get(REPO_DOGMA);
        dogmaRepo.addListener(RepositoryListener.of(Query.ofJson(METADATA_JSON), entry -> {
            if (entry == null) {
                logger.warn("{} file is missing in {}/{}", METADATA_JSON, name, REPO_DOGMA);
                return;
            }

            final Revision lastRevision = entry.revision();
            final Revision lastMetadataRevision = this.lastMetadataRevision;
            assert lastMetadataRevision != null;
            if (lastRevision.compareTo(lastMetadataRevision) <= 0) {
                // An old data.
                return;
            }

            try {
                final ProjectMetadata projectMetadata = Jackson.treeToValue(entry.content(),
                                                                            ProjectMetadata.class);
                this.lastMetadataRevision = lastRevision;
                this.projectMetadata = projectMetadata;
            } catch (JsonParseException | JsonMappingException e) {
                logger.warn("Invalid {} file in {}/{}", METADATA_JSON, name, REPO_DOGMA, e);
            }
        }));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long creationTimeMillis() {
        return creationTimeMillis;
    }

    @Override
    public Author author() {
        return author;
    }

    @Override
    public MetaRepository resetMetaRepository() {
        final Repository repository = repos.get(REPO_DOGMA);
        final CompletableFuture<Entry<JsonNode>> future = repository.getOrNull(Revision.HEAD, Query.ofJson(
                META_TO_DOGMA_MIGRATED));
        final Entry<JsonNode> entry;
        try {
            // Will be executed by the ZooKeeper command executor during migration.
            entry = future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("failed to get the migration entry in 10 seconds. ", e);
        }
        return setMetaRepository(entry != null);
    }

    private MetaRepository setMetaRepository(boolean useDogmaRepoAsMetaRepo) {
        final String repoName = useDogmaRepoAsMetaRepo ? REPO_DOGMA : REPO_META;
        final DefaultMetaRepository metaRepo = new DefaultMetaRepository(repos.get(repoName));
        this.metaRepo = metaRepo;
        return metaRepo;
    }

    @Override
    public MetaRepository metaRepo() {
        checkState(!name.equals(INTERNAL_PROJECT_DOGMA),
                   "metaRepo() is not available for %s project", INTERNAL_PROJECT_DOGMA);
        return metaRepo;
    }

    @Override
    public RepositoryManager repos() {
        return repos;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("author", author)
                          .add("repos", repos)
                          .toString();
    }
}
