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
package com.graviteesource.notifier.slack;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.notifier.api.AbstractConfigurableNotifier;
import io.gravitee.notifier.api.Notification;
import io.gravitee.notifier.api.exception.NotifierException;
import com.graviteesource.notifier.slack.configuration.SlackNotifierConfiguration;
import com.graviteesource.notifier.slack.deployment.SlackNotifierDeploymentLifecycle;
import com.graviteesource.notifier.slack.request.PostMessage;
import com.graviteesource.notifier.slack.vertx.VertxCompletableFuture;
import io.gravitee.plugin.api.annotations.Plugin;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Plugin(
        deployment = SlackNotifierDeploymentLifecycle.class
)
public class SlackNotifier extends AbstractConfigurableNotifier<SlackNotifierConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackNotifier.class);

    private static final String TYPE = "slack-notifier";

    private static final String SLACK_POST_MESSAGES_URL = "https://slack.com/api/chat.postMessage";
    private static final String HTTPS_SCHEME = "https";

    private final static String UTF8_CHARSET_NAME = "UTF-8";
    private final static String APPLICATION_JSON = MediaType.APPLICATION_JSON + ";charset=" + UTF8_CHARSET_NAME;

    @Value("${httpClient.timeout:10000}")
    private int httpClientTimeout;
    @Value("${httpClient.proxy.type:HTTP}")
    private String httpClientProxyType;

    @Value("${httpClient.proxy.http.host:#{systemProperties['http.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpHost;
    @Value("${httpClient.proxy.http.port:#{systemProperties['http.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpPort;
    @Value("${httpClient.proxy.http.username:#{null}}")
    private String httpClientProxyHttpUsername;
    @Value("${httpClient.proxy.http.password:#{null}}")
    private String httpClientProxyHttpPassword;

    @Value("${httpClient.proxy.https.host:#{systemProperties['https.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpsHost;
    @Value("${httpClient.proxy.https.port:#{systemProperties['https.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpsPort;
    @Value("${httpClient.proxy.https.username:#{null}}")
    private String httpClientProxyHttpsUsername;
    @Value("${httpClient.proxy.https.password:#{null}}")
    private String httpClientProxyHttpsPassword;

    public SlackNotifier(SlackNotifierConfiguration configuration) {
        super(TYPE, configuration);
    }

    @Override
    protected CompletableFuture<Void> doSend(Notification notification, Map<String, Object> parameters) {
        CompletableFuture<Void> future = new VertxCompletableFuture<>(Vertx.currentContext());

        URI requestUri = URI.create(SLACK_POST_MESSAGES_URL);

        boolean ssl = HTTPS_SCHEME.equalsIgnoreCase(requestUri.getScheme());

        final HttpClientOptions options = new HttpClientOptions()
                .setSsl(ssl)
                .setTrustAll(true)
                .setMaxPoolSize(1)
                .setKeepAlive(false)
                .setTcpKeepAlive(false)
                .setConnectTimeout(httpClientTimeout);

        if (configuration.isUseSystemProxy()) {
            ProxyOptions proxyOptions = new ProxyOptions();
            proxyOptions.setType(ProxyType.valueOf(httpClientProxyType));
            if (HTTPS_SCHEME.equals(requestUri.getScheme())) {
                proxyOptions.setHost(httpClientProxyHttpsHost);
                proxyOptions.setPort(httpClientProxyHttpsPort);
                proxyOptions.setUsername(httpClientProxyHttpsUsername);
                proxyOptions.setPassword(httpClientProxyHttpsPassword);
            } else {
                proxyOptions.setHost(httpClientProxyHttpHost);
                proxyOptions.setPort(httpClientProxyHttpPort);
                proxyOptions.setUsername(httpClientProxyHttpUsername);
                proxyOptions.setPassword(httpClientProxyHttpPassword);
            }
            options.setProxyOptions(proxyOptions);
        }

        options.setDefaultPort(requestUri.getPort() != -1 ? requestUri.getPort() :
                (HTTPS_SCHEME.equals(requestUri.getScheme()) ? 443 : 80));
        options.setDefaultHost(requestUri.getHost());

        HttpClient client = Vertx.currentContext().owner().createHttpClient(options);

        try {
            HttpClientRequest request = client
                    .request(HttpMethod.POST, requestUri.getPath())
                    .setFollowRedirects(true)
                    .setTimeout(httpClientTimeout);

            request.handler(response -> {
                if (response.statusCode() == HttpStatusCode.OK_200) {
                    response.bodyHandler(buffer -> {
                        LOGGER.info("Slack notification sent!");
                        future.complete(null);

                        // Close client
                        client.close();
                    });
                } else {
                    future.completeExceptionally(new NotifierException("Unable to send message to '" +
                            SLACK_POST_MESSAGES_URL + "'. Status code: " + response.statusCode() + ". Message: " +
                            response.statusMessage(), null));

                    // Close client
                    client.close();
                }
            });

            request.headers().set(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON);
            request.headers().set(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getToken());

            request.exceptionHandler(throwable -> {
                try {
                    LOGGER.error("Error while sending slack notification", throwable);
                    future.completeExceptionally(throwable);

                    // Close client
                    client.close();
                } catch (IllegalStateException ise) {
                    // Do not take care about exception when closing client
                }
            });

            PostMessage message = new PostMessage();

            message.setChannel(configuration.getChannel());
            message.setText(templatize(configuration.getMessage(), parameters));

            request.end(Json.encode(message));
        } catch (Exception ex) {
            logger.error("Unable to fetch content using HTTP", ex);
            future.completeExceptionally(ex);
        }

        return future;
    }
}
