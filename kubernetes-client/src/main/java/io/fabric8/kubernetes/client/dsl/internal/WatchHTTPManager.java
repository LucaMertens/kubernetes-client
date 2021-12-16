/**
 * Copyright (C) 2015 Red Hat, Inc.
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
package io.fabric8.kubernetes.client.dsl.internal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.BaseOperation;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchHTTPManager<T extends HasMetadata, L extends KubernetesResourceList<T>> extends AbstractWatchManager<T> {
  private static final Logger logger = LoggerFactory.getLogger(WatchHTTPManager.class);
  private CompletableFuture<HttpResponse<InputStream>> call;
  
  public WatchHTTPManager(final HttpClient client,
                          final BaseOperation<T, L, ?> baseOperation,
                          final ListOptions listOptions, final Watcher<T> watcher, final int reconnectInterval,
                          final int reconnectLimit, long connectTimeout)
    throws MalformedURLException {
    // Default max 32x slowdown from base interval
    this(client, baseOperation, listOptions, watcher, reconnectInterval, reconnectLimit, connectTimeout, 5);
  }
  
  public WatchHTTPManager(final HttpClient client,
                          final BaseOperation<T, L, ?> baseOperation,
                          final ListOptions listOptions, final Watcher<T> watcher, final int reconnectInterval,
                          final int reconnectLimit, long connectTimeout, int maxIntervalExponent)
    throws MalformedURLException {
    
    super(
        watcher, baseOperation, listOptions, reconnectLimit, reconnectInterval, maxIntervalExponent,
        () -> client.newBuilder()
            .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .forStreaming()
            .build());
    
  }
  
  @Override
  protected synchronized void run(URL url, Map<String, String> headers) {
    HttpRequest.Builder builder = client.newHttpRequestBuilder().url(url);
    headers.forEach(builder::header);
    call = client.sendAsync(builder.build(), InputStream.class);
    call.whenComplete((response, t) -> {
      if (!call.isCancelled() && t != null) {
        logger.info("Watch connection failed. reason: {}", t.getMessage());
      }
      if (response != null) {
        try (InputStream body = response.body()){
          resetReconnectAttempts();
          if (!response.isSuccessful() 
              && onStatus(OperationSupport.createStatus(response.code(), response.message()))) {
            return;
          }
          BufferedReader source = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
          String message = null;
          while ((message = source.readLine()) != null) {
            onMessage(message);
          }
        } catch (Exception e) {
          logger.info("Watch terminated unexpectedly. reason: {}", e.getMessage());
        }
      }
      
      if (!call.isCancelled()) {
        scheduleReconnect();
      }
    });
  }
  
  @Override
  protected synchronized void closeRequest() {
    if (call != null) {
      call.cancel(true);
      call = null;
    }
  }
  
}
