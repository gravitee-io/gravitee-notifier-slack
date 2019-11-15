/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.notifier.slack.deployment;

import com.graviteesource.license.api.LicensedNode;
import io.gravitee.node.api.plugin.NodeDeploymentContext;
import io.gravitee.plugin.api.DeploymentLifecycle;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SlackNotifierDeploymentLifecycle implements DeploymentLifecycle<NodeDeploymentContext> {

    @Override
    public void onDeploy(NodeDeploymentContext context) {
        try {
            if (LicensedNode.class.isAssignableFrom(context.node().getClass())) {
                return;
            }

            throw new IllegalStateException("Slack notifier can run only on an Enterprise Gravitee.io Node");
        } catch (NoClassDefFoundError ncdfe) {
            throw new IllegalStateException("Slack notifier can run only on an Enterprise Gravitee.io Node");
        }
    }
}
