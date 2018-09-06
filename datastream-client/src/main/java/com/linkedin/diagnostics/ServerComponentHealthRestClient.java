package com.linkedin.diagnostics;

import java.util.List;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.diagnostics.ServerComponentHealth;
import com.linkedin.datastream.server.diagnostics.DiagRequestBuilders;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.restli.client.FindRequest;
import com.linkedin.restli.client.RestClient;

/**
 * Restli Client to call the ServerComponentHealth Find request to get the server status.
 */

public class ServerComponentHealthRestClient {
  private static final Logger LOG = LoggerFactory.getLogger(ServerComponentHealthRestClient.class);
  private final DiagRequestBuilders _builders;
  private final RestClient _restClient;

  public ServerComponentHealthRestClient(RestClient restClient) {
    Validate.notNull(restClient, "null restClient");
    _builders = new DiagRequestBuilders();
    _restClient = restClient;
  }

  /**
   * Get the ServerComponentHealth status from one server instance. This method makes a FIND rest call
   * to the ServerComponentHealth management service which in turn fetches this status from the component.
   * @param type
   *    Type of the component such as connector.
   * @param scope
   *    Scope of the component such as Espresso and Kafka.
   * @param content
   *    Request content should be passed to the component.
   * @return
   *    List of ServerComponentHealth object corresponding to the component.
   */
  public ServerComponentHealth getStatus(String type, String scope, String content) {
    List<ServerComponentHealth> response = getServerComponentHealthStatues(type, scope, content);
    if (response != null && !response.isEmpty()) {
      return response.get(0);
    } else {
      LOG.error("ServerComponentHealth getStatus {} {} failed with empty response.", type, scope);
      return null;
    }
  }

  public List<ServerComponentHealth> getServerComponentHealthStatues(String type, String scope, String content) {
    try {
      FindRequest<ServerComponentHealth> request = _builders.findByStatus().typeParam(type).scopeParam(scope).contentParam(content).build();
      return _restClient.sendRequest(request).getResponse().getEntity().getElements();
    } catch (RemoteInvocationException e) {
      LOG.error("Get serverComponentHealthStatus {} {} failed with error.", type, scope);
      return null;
    }
  }

  /**
   * Get the ServerComponentHealth statuses from all server instances. This method makes a FIND rest call
   * to the ServerComponentHealth management service which in turn fetches this status from the component.
   * @param type
   *    Type of the component such as connector.
   * @param scope
   *    Scope of the component such as Espresso and Kafka.
   * @param content
   *    Request content should be passed to the component.
   * @return
   *    List of ServerComponentHealth object corresponding to the component.
   */
  public ServerComponentHealth getAllStatus(String type, String scope, String content) {
    List<ServerComponentHealth> response = getServerComponentHealthAllStatus(type, scope, content);
    if (response != null && !response.isEmpty()) {
      return response.get(0);
    } else {
      LOG.error("ServerComponentHealth getAllStatus {} {} failed with empty response.", type, scope);
      return null;
    }
  }

  public List<ServerComponentHealth> getServerComponentHealthAllStatus(String type, String scope, String content) {
    try {
      FindRequest<ServerComponentHealth> request = _builders.findByAllStatus().typeParam(type).scopeParam(scope).contentParam(content).build();
      return _restClient.sendRequest(request).getResponse().getEntity().getElements();
    } catch (RemoteInvocationException e) {
      LOG.error("Get serverComponentHealthAllStatus {} {} failed with error.", type, scope);
      return null;
    }
  }
}
