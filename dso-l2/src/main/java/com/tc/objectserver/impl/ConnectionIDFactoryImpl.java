/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.objectserver.impl;

import com.tc.exception.TCRuntimeException;
import com.tc.util.ProductID;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.transport.ConnectionID;
import com.tc.net.protocol.transport.ConnectionIDFactory;
import com.tc.net.protocol.transport.ConnectionIDFactoryListener;
import com.tc.object.net.DSOChannelManagerEventListener;
import com.tc.objectserver.api.ClientNotFoundException;
import com.tc.objectserver.persistence.ClientStatePersistor;
import com.tc.util.Assert;
import com.tc.util.sequence.MutableSequence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionIDFactoryImpl implements ConnectionIDFactory, DSOChannelManagerEventListener {

  private final ClientStatePersistor              clientStateStore;
  private final MutableSequence                   connectionIDSequence;
  private String                                  serverUUID;
  private final List<ConnectionIDFactoryListener> listeners = new CopyOnWriteArrayList<>();

  public ConnectionIDFactoryImpl(ClientStatePersistor clientStateStore) {
    this.clientStateStore = clientStateStore;
    this.connectionIDSequence = clientStateStore.getConnectionIDSequence();
    this.serverUUID = this.clientStateStore.getServerUUID();
  }

  @Override
  public ConnectionID populateConnectionID(ConnectionID connectionID) {
    if (new ChannelID(connectionID.getChannelID()).isNull()) {
      return nextConnectionId(connectionID.getJvmID(), connectionID.getProductId());
    } else {
      return makeConnectionId(connectionID.getJvmID(), connectionID.getChannelID(), connectionID.getProductId());
    }
  }

  private ConnectionID nextConnectionId(String clientJvmID, ProductID productId) {
    return buildConnectionId(clientJvmID, connectionIDSequence.next(), productId);
  }

  private ConnectionID buildConnectionId(String jvmID, long channelID, ProductID productId) {
    Assert.assertNotNull(this.serverUUID);
    // Make sure we save the fact that we are giving out this id to someone in the database before giving it out.
    clientStateStore.saveClientState(new ChannelID(channelID));
    ConnectionID rv = new ConnectionID(jvmID, channelID, this.serverUUID, null, null, productId);
    fireCreationEvent(rv);
    return rv;
  }

  private ConnectionID makeConnectionId(String clientJvmID, long channelID, ProductID productId) {
    Assert.assertTrue(channelID != ChannelID.NULL_ID.toLong());
    // provided channelID shall not be using
    if (clientStateStore.containsClient(new ChannelID(channelID))) { throw new TCRuntimeException(
                                                                                                  "The connectionId "
                                                                                                      + channelID
                                                                                                      + " has been used. "
                                                                                                      + " One possible cause: restarted some mirror groups but not all."); }

    return buildConnectionId(clientJvmID, channelID, productId);
  }

  @Override
  public void restoreConnectionId(ConnectionID rv) {
    fireCreationEvent(rv);
  }

  private void fireCreationEvent(ConnectionID rv) {
    for (ConnectionIDFactoryListener listener : listeners) {
      listener.connectionIDCreated(rv);
    }
  }

  private void fireDestroyedEvent(ConnectionID connectionID) {
    for (ConnectionIDFactoryListener listener : listeners) {
      listener.connectionIDDestroyed(connectionID);
    }
  }

  @Override
  public void init(String clusterID, long nextAvailChannelID, Set<ConnectionID> connections) {
    this.serverUUID = clusterID;
    if (nextAvailChannelID >= 0) {
      this.connectionIDSequence.setNext(nextAvailChannelID);
    }
    for (final ConnectionID cid : connections) {
      Assert.assertEquals(clusterID, cid.getServerID());
      this.clientStateStore.saveClientState(new ChannelID(cid.getChannelID()));
    }
  }

  @Override
  public Set<ConnectionID> loadConnectionIDs() {
    Assert.assertNotNull(this.serverUUID);
    Set<ConnectionID> connections = new HashSet<>();
    for (final ChannelID channelID : clientStateStore.loadClientIDs()) {
      connections.add(new ConnectionID(ConnectionID.NULL_JVM_ID, (channelID).toLong(), this.serverUUID));
    }
    return connections;
  }

  @Override
  public void registerForConnectionIDEvents(ConnectionIDFactoryListener listener) {
    listeners.add(listener);
  }

  @Override
  public void channelCreated(MessageChannel channel) {
    // NOP
  }

  @Override
  public void channelRemoved(MessageChannel channel, boolean wasActive)  {
    ChannelID clientID = channel.getChannelID();
    try {
      clientStateStore.deleteClientState(clientID);
    } catch (ClientNotFoundException e) {
      throw new AssertionError(e);
    }
    fireDestroyedEvent(new ConnectionID(ConnectionID.NULL_JVM_ID, clientID.toLong(), this.serverUUID));
  }

  @Override
  public long getCurrentConnectionID() {
    return connectionIDSequence.current();
  }

}