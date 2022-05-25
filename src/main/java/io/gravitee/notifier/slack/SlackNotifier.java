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
package io.gravitee.notifier.slack;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.notifier.api.AbstractConfigurableNotifier;
import io.gravitee.notifier.api.Notification;
import io.gravitee.notifier.api.exception.NotifierException;
import io.gravitee.notifier.slack.configuration.SlackNotifierConfiguration;
import io.gravitee.notifier.slack.request.PostMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SlackNotifier extends AbstractConfigurableNotifier<SlackNotifierConfiguration> {

    private static final String TYPE = "slack-notifier";

    private static final String SLACK_POST_MESSAGES_URL = "https://slack.com/api/chat.postMessage";
    private static final String HTTPS_SCHEME = "https";

    private static final String UTF8_CHARSET_NAME = "UTF-8";
    private static final String APPLICATION_JSON = MediaType.APPLICATION_JSON + ";charset=" + UTF8_CHARSET_NAME;

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
        CompletableFuture<Void> future = new CompletableFuture<>();

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

        options.setDefaultPort(
            requestUri.getPort() != -1 ? requestUri.getPort() : (HTTPS_SCHEME.equals(requestUri.getScheme()) ? 443 : 80)
        );
        options.setDefaultHost(requestUri.getHost());

        HttpClient client = Vertx.currentContext().owner().createHttpClient(options);

        RequestOptions requestOpts = new RequestOptions()
            .setURI(requestUri.getPath())
            .setMethod(HttpMethod.POST)
            .setFollowRedirects(true)
            .setTimeout(httpClientTimeout);

        client
            .request(requestOpts)
            .onFailure(throwable -> handleFailure(future, client, throwable))
            .onSuccess(httpClientRequest -> {
                try {
                    // Connection is made, lets continue.
                    final Future<HttpClientResponse> futureResponse;

                    httpClientRequest.headers().set(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON);
                    httpClientRequest.headers().set(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getToken());

                    PostMessage message = new PostMessage();

                    message.setChannel(configuration.getChannel());
                    message.setText(templatize(configuration.getMessage(), parameters));

                    httpClientRequest
                        .send(Json.encode(message))
                        .onSuccess(httpClientResponse -> handleSuccess(future, client, httpClientResponse))
                        .onFailure(throwable -> handleFailure(future, client, throwable));
                } catch (Exception e) {
                    handleFailure(future, client, e);
                }
            });

        return future;
    }

    private void handleSuccess(CompletableFuture<Void> future, HttpClient client, HttpClientResponse httpClientResponse) {
        if (httpClientResponse.statusCode() == HttpStatusCode.OK_200) {
            httpClientResponse.bodyHandler(buffer -> {
                future.complete(null);

                // Close client
                client.close();
            });
        } else {
            future.completeExceptionally(
                new NotifierException(
                    "Unable to send message to '" +
                    SLACK_POST_MESSAGES_URL +
                    "'. Status code: " +
                    httpClientResponse.statusCode() +
                    ". Message: " +
                    httpClientResponse.statusMessage(),
                    null
                )
            );

            // Close client
            client.close();
        }
    }

    private void handleFailure(CompletableFuture<Void> future, HttpClient client, Throwable throwable) {
        try {
            logger.error("Unable send Slack notification", throwable);

            future.completeExceptionally(throwable);

            // Close client
            client.close();
        } catch (IllegalStateException ise) {
            // Do not take care about exception when closing client
        }
    }
}
