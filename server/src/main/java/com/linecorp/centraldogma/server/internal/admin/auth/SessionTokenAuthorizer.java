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

package com.linecorp.centraldogma.server.internal.admin.auth;

import static com.linecorp.centraldogma.server.metadata.User.LEVEL_SYSTEM_ADMIN;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_USER;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A decorator to check whether the request holds a valid token. If it holds a valid token, this
 * decorator would find a session belonging to the token and attach it to the service context attributes.
 */
public class SessionTokenAuthorizer extends AbstractAuthorizer {

    private final SessionManager sessionManager;
    private final Set<String> systemAdministrators;

    public SessionTokenAuthorizer(SessionManager sessionManager, Set<String> systemAdministrators) {
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        this.systemAdministrators = requireNonNull(systemAdministrators, "systemAdministrators");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req, String accessToken) {
        return sessionManager.get(accessToken).thenApply(session -> {
            if (session == null) {
                return false;
            }
            final String username = session.username();
            final List<String> roles =
                    systemAdministrators.contains(username) ? LEVEL_SYSTEM_ADMIN
                                                            : LEVEL_USER;
            final User user = new User(username, roles);
            ctx.logBuilder().authenticatedUser("user/" + username);
            AuthUtil.setCurrentUser(ctx, user);
            HttpApiUtil.setVerboseResponses(ctx, user);
            return true;
        });
    }
}
