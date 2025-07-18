/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

/**
 * Integration test for verifying mirror author configuration.
 */
class MirrorAuthorIntegrationTest {

    private static final String TEST_MIRROR_AUTHOR_NAME = "Integration Test Mirror";
    private static final String TEST_MIRROR_AUTHOR_EMAIL = "test-mirror@example.com";
    private static final Author TEST_MIRROR_AUTHOR = new Author(TEST_MIRROR_AUTHOR_NAME, TEST_MIRROR_AUTHOR_EMAIL);

    @RegisterExtension
    static final CentralDogmaExtension dogmaWithCustomAuthor = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(
                    new MirroringServicePluginConfig(true, 1, 8192, 32 * 1048576L, false, TEST_MIRROR_AUTHOR));
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension dogmaWithDefaultAuthor = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(
                    new MirroringServicePluginConfig(true, 1, 8192, 32 * 1048576L, false, null));
        }
    };

    private static CentralDogma clientWithCustomAuthor;
    private static CentralDogma clientWithDefaultAuthor;
    private static MirroringService mirroringServiceWithCustomAuthor;
    private static MirroringService mirroringServiceWithDefaultAuthor;

    @BeforeAll
    static void init() {
        clientWithCustomAuthor = dogmaWithCustomAuthor.client();
        clientWithDefaultAuthor = dogmaWithDefaultAuthor.client();
        mirroringServiceWithCustomAuthor = dogmaWithCustomAuthor.mirroringService();
        mirroringServiceWithDefaultAuthor = dogmaWithDefaultAuthor.mirroringService();
    }

    @RegisterExtension
    final TemporaryFolderExtension gitRepoDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private Git git;
    private File gitWorkTree;
    private String gitUri;
    private String projName;

    @BeforeEach
    void initGitRepo(TestInfo testInfo) throws Exception {
        final String repoName = TestUtil.normalizedDisplayName(testInfo);
        gitWorkTree = new File(gitRepoDir.getRoot().toFile(), repoName).getAbsoluteFile();
        final org.eclipse.jgit.lib.Repository gitRepo = 
                new org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepo.create();
        
        git = Git.wrap(gitRepo);
        gitUri = "git+file://" +
                 (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                 gitWorkTree.getPath().replace(File.separatorChar, '/') +
                 "/.git";

        projName = TestUtil.normalizedDisplayName(testInfo);
    }

    @AfterEach
    void destroyGitRepo() {
        // GitRepoExtension handles cleanup
    }

    @Test
    void testCustomMirrorAuthor() throws Exception {
        // Create a project and repository
        clientWithCustomAuthor.createProject(projName).join();
        clientWithCustomAuthor.createRepository(projName, "repo1").join();

        // Add a file to mirror
        clientWithCustomAuthor.forRepo(projName, "repo1")
                .commit("Add test file", Change.ofTextUpsert("/test.txt", "Hello Mirror!"))
                .push().join();

        // Configure mirroring
        pushMirrorSettings(clientWithCustomAuthor, projName, "repo1", "/", "/", null, MirrorDirection.LOCAL_TO_REMOTE);

        // Perform mirroring
        mirroringServiceWithCustomAuthor.mirror().join();

        // Verify the author in the git commit
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            ObjectId headId = git.getRepository().resolve("refs/heads/master");
            RevCommit commit = revWalk.parseCommit(headId);
            
            PersonIdent author = commit.getAuthorIdent();
            assertThat(author.getName()).isEqualTo(TEST_MIRROR_AUTHOR_NAME);
            assertThat(author.getEmailAddress()).isEqualTo(TEST_MIRROR_AUTHOR_EMAIL);
            
            PersonIdent committer = commit.getCommitterIdent();
            assertThat(committer.getName()).isEqualTo(TEST_MIRROR_AUTHOR_NAME);
            assertThat(committer.getEmailAddress()).isEqualTo(TEST_MIRROR_AUTHOR_EMAIL);
        }
    }

    @Test
    void testDefaultMirrorAuthor() throws Exception {
        // Create a project and repository
        clientWithDefaultAuthor.createProject(projName).join();
        clientWithDefaultAuthor.createRepository(projName, "repo1").join();

        // Add a file to mirror
        clientWithDefaultAuthor.forRepo(projName, "repo1")
                .commit("Add test file", Change.ofTextUpsert("/test.txt", "Hello Default Mirror!"))
                .push().join();

        // Configure mirroring
        pushMirrorSettings(clientWithDefaultAuthor, projName, "repo1", "/", "/", null, MirrorDirection.LOCAL_TO_REMOTE);

        // Perform mirroring
        mirroringServiceWithDefaultAuthor.mirror().join();

        // Verify the default author in the git commit
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            ObjectId headId = git.getRepository().resolve("refs/heads/master");
            RevCommit commit = revWalk.parseCommit(headId);
            
            PersonIdent author = commit.getAuthorIdent();
            assertThat(author.getName()).isEqualTo("Mirror");
            assertThat(author.getEmailAddress()).isEqualTo("mirror@localhost.localdomain");
            
            PersonIdent committer = commit.getCommitterIdent();
            assertThat(committer.getName()).isEqualTo("Mirror");
            assertThat(committer.getEmailAddress()).isEqualTo("mirror@localhost.localdomain");
        }
    }

    private void pushMirrorSettings(CentralDogma client, String projName, String localRepo,
                                    @Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore, MirrorDirection direction) {
        final String localPath0 = localPath == null ? "/" : localPath;
        final String remoteUri = gitUri + firstNonNull(remotePath, "");
        
        // Add credential
        try {
            final String credentialName = credentialName(projName, "none");
            client.forRepo(projName, Project.REPO_META)
                  .commit("Add /credentials/none",
                          Change.ofJsonUpsert(credentialFile(credentialName),
                                              "{ " +
                                              "\"type\": \"NONE\"," +
                                              "\"name\": \"" + credentialName + '"' +
                                              '}'))
                  .push().join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof RedundantChangeException)) {
                throw e;
            }
        }
        
        // Add mirror configuration
        client.forRepo(projName, Project.REPO_META)
              .commit("Add /repos/" + localRepo + "/mirrors/test-mirror.json",
                      Change.ofJsonUpsert("/repos/" + localRepo + "/mirrors/test-mirror.json",
                                          '{' +
                                          " \"id\": \"test-mirror\"," +
                                          " \"enabled\": true," +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"" + direction + "\"," +
                                          "  \"localRepo\": \"" + localRepo + "\"," +
                                          "  \"localPath\": \"" + localPath0 + "\"," +
                                          "  \"remoteUri\": \"" + remoteUri + "\"," +
                                          "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                          "  \"gitignore\": " + firstNonNull(gitignore, "\"\"") + ',' +
                                          "  \"credentialName\": \"" +
                                          credentialName(projName, "none") + '"' +
                                          '}'))
              .push().join();
    }
}