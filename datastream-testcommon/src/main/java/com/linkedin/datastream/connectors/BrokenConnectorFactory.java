/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.connectors;

import java.util.Properties;

import com.linkedin.datastream.server.api.connector.ConnectorFactory;

public class BrokenConnectorFactory implements ConnectorFactory<BrokenConnector> {
  @Override
  public BrokenConnector createConnector(String connectorName, Properties config, String clusterName) {
    try {
      return new BrokenConnector(config);
    } catch (Exception e) {
      throw new RuntimeException("Instantiating BrokenConnector threw exception", e);
    }
  }
}
