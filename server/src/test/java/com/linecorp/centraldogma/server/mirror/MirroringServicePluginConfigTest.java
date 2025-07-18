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
package com.linecorp.centraldogma.server.mirror;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.Jackson;

class MirroringServicePluginConfigTest {

    @Test
    void serializeAndDeserialize() throws Exception {
        final Author defaultMirrorAuthor = new Author("Test Mirror", "mirror@example.com");
        final MirroringServicePluginConfig config = new MirroringServicePluginConfig(
                true, 32, 16384, 64 * 1048576L, true, defaultMirrorAuthor);

        // Serialize
        final String json = Jackson.writeValueAsString(config);
        assertThat(json).contains("\"defaultMirrorAuthor\"");
        assertThat(json).contains("\"name\":\"Test Mirror\"");
        assertThat(json).contains("\"email\":\"mirror@example.com\"");

        // Deserialize
        final MirroringServicePluginConfig deserialized = Jackson.readValue(json, MirroringServicePluginConfig.class);
        assertThat(deserialized.enabled()).isTrue();
        assertThat(deserialized.numMirroringThreads()).isEqualTo(32);
        assertThat(deserialized.maxNumFilesPerMirror()).isEqualTo(16384);
        assertThat(deserialized.maxNumBytesPerMirror()).isEqualTo(64 * 1048576L);
        assertThat(deserialized.zonePinned()).isTrue();
        assertThat(deserialized.defaultMirrorAuthor()).isNotNull();
        assertThat(deserialized.defaultMirrorAuthor().name()).isEqualTo("Test Mirror");
        assertThat(deserialized.defaultMirrorAuthor().email()).isEqualTo("mirror@example.com");
    }

    @Test
    void deserializeWithoutDefaultMirrorAuthor() throws Exception {
        final String json = "{\"enabled\":true,\"numMirroringThreads\":16,\"maxNumFilesPerMirror\":8192," +
                            "\"maxNumBytesPerMirror\":33554432,\"zonePinned\":false}";

        final MirroringServicePluginConfig config = Jackson.readValue(json, MirroringServicePluginConfig.class);
        assertThat(config.enabled()).isTrue();
        assertThat(config.numMirroringThreads()).isEqualTo(16);
        assertThat(config.maxNumFilesPerMirror()).isEqualTo(8192);
        assertThat(config.maxNumBytesPerMirror()).isEqualTo(33554432L);
        assertThat(config.zonePinned()).isFalse();
        assertThat(config.defaultMirrorAuthor()).isNull();
    }

    @Test
    void deserializeWithDefaults() throws Exception {
        final String json = "{\"enabled\":true}";

        final MirroringServicePluginConfig config = Jackson.readValue(json, MirroringServicePluginConfig.class);
        assertThat(config.enabled()).isTrue();
        assertThat(config.numMirroringThreads()).isEqualTo(MirroringServicePluginConfig.DEFAULT_NUM_MIRRORING_THREADS);
        assertThat(config.maxNumFilesPerMirror()).isEqualTo(MirroringServicePluginConfig.DEFAULT_MAX_NUM_FILES_PER_MIRROR);
        assertThat(config.maxNumBytesPerMirror()).isEqualTo(MirroringServicePluginConfig.DEFAULT_MAX_NUM_BYTES_PER_MIRROR);
        assertThat(config.zonePinned()).isFalse();
        assertThat(config.defaultMirrorAuthor()).isNull();
    }

    @Test
    void testToString() {
        final Author defaultMirrorAuthor = new Author("Test Mirror", "mirror@example.com");
        final MirroringServicePluginConfig config = new MirroringServicePluginConfig(
                true, 32, 16384, 64 * 1048576L, true, defaultMirrorAuthor);

        final String str = config.toString();
        assertThat(str).contains("numMirroringThreads=32");
        assertThat(str).contains("maxNumFilesPerMirror=16384");
        assertThat(str).contains("maxNumBytesPerMirror=67108864");
        assertThat(str).contains("zonePinned=true");
        assertThat(str).contains("defaultMirrorAuthor=Author[\"Test Mirror\" <mirror@example.com>]");
    }
}