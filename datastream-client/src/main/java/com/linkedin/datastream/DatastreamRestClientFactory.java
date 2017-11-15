package com.linkedin.datastream;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.restli.client.RestClient;


/**
 * Factory class for obtaining {@link DatastreamRestClient} objects.
 *
 * The reason is that currently we inject the dmsUri as part of the configuration
 * but we don't have a dependency injection framework that allows us to inject mock
 * DatastreamRestClient, without doing a major refactoring of the code.
 */
public final class DatastreamRestClientFactory {
  private static final Logger LOG = LoggerFactory.getLogger(DatastreamRestClientFactory.class);
  private static final BaseRestClientFactory<DatastreamRestClient> FACTORY =
      new BaseRestClientFactory<>(DatastreamRestClient.class, LOG);

  /**
   * Get a DatastreamRestClient with default HTTP client
   * @param dmsUri URI to DMS endpoint
   * @return
   */
  public static DatastreamRestClient getClient(String dmsUri) {
    return FACTORY.getClient(dmsUri, Collections.emptyMap());
  }

  /**
   * Get a DatastreamRestClient with custom HTTP configs
   * @see BaseRestClientFactory#getClient(String, Map)
   * @param dmsUri URI to DMS endpoint
   * @param httpConfig custom config for HTTP client, please find the configs in
   *                   {@link com.linkedin.r2.transport.http.client.HttpClientFactory}
   * @return
   */
  public static DatastreamRestClient getClient(String dmsUri, Map<String, String> httpConfig) {
    return FACTORY.getClient(dmsUri, httpConfig);
  }

  /**
   * @see BaseRestClientFactory#addOverride(String, Object)
   */
  public static void addOverride(String dmsUri, DatastreamRestClient restClient) {
    FACTORY.addOverride(dmsUri, restClient);
  }

  /**
   * @see BaseRestClientFactory#registerRestClient(String, RestClient)
   */
  public static void registerRestClient(String dmsUri, RestClient restClient) {
    FACTORY.registerRestClient(dmsUri, restClient);
  }

  /**
   * @see BaseRestClientFactory#shutdown(Callback, Duration)
   */
  public static void shutdown(Callback<None> callback, Duration timeout) {
    FACTORY.shutdown(callback, timeout);
  }
}
