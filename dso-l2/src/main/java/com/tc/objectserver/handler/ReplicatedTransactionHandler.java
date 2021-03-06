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
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.objectserver.entity.MessagePayload;
import com.tc.l2.msg.ReplicationMessage;
import com.tc.l2.msg.ReplicationMessageAck;
import com.tc.l2.msg.ReplicationResultCode;
import com.tc.l2.msg.SyncReplicationActivity;
import com.tc.l2.state.StateManager;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.ServerID;
import com.tc.net.groups.AbstractGroupMessage;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.MessageID;
import com.tc.object.ClientInstanceID;
import com.tc.object.EntityDescriptor;
import com.tc.object.EntityID;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.EntityManager;
import com.tc.objectserver.api.ManagedEntity;
import com.tc.objectserver.api.ServerEntityAction;
import com.tc.objectserver.api.ServerEntityRequest;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.entity.BarrierCompletion;
import com.tc.objectserver.entity.ClientDescriptorImpl;
import com.tc.objectserver.entity.PlatformEntity;
import com.tc.objectserver.entity.ServerEntityRequestResponse;
import com.tc.objectserver.persistence.EntityPersistor;
import com.tc.objectserver.persistence.TransactionOrderPersistor;
import com.tc.util.Assert;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Set;
import org.terracotta.entity.ClientDescriptor;
import org.terracotta.entity.ConcurrencyStrategy;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.MessageCodecException;
import org.terracotta.exception.EntityException;


public class ReplicatedTransactionHandler {
  private static final TCLogger PLOGGER = TCLogging.getLogger(MessagePayload.class);
  private static final TCLogger LOGGER = TCLogging.getLogger(ReplicatedTransactionHandler.class);
  private final EntityManager entityManager;
  private final EntityPersistor entityPersistor;
  private final GroupManager<AbstractGroupMessage> groupManager;
  private final TransactionOrderPersistor orderedTransactions;
  private final StateManager stateManager;
  private final ManagedEntity platform;
  
  private final SyncState state = new SyncState();
  
  // This MUST be manipulated under lock - it is the batch of ack messages we are accumulating until the network is ready for another message.
  private boolean isWaitingForNetwork;
  private NodeID cachedMessageAckFrom;
  private ReplicationMessageAck cachedBatchAck;
  private final Runnable handleMessageSend = new Runnable() {
    @Override
    public void run() {
      handleNetworkDone();
    }
  };
  
  public ReplicatedTransactionHandler(StateManager state, TransactionOrderPersistor transactionOrderPersistor, 
      EntityManager manager, EntityPersistor entityPersistor, GroupManager<AbstractGroupMessage> groupManager) {
    this.stateManager = state;
    this.entityManager = manager;
    this.entityPersistor = entityPersistor;
    this.groupManager = groupManager;
    this.orderedTransactions = transactionOrderPersistor;
    try {
      platform = entityManager.getEntity(PlatformEntity.PLATFORM_ID, PlatformEntity.VERSION).get();
    } catch (EntityException ee) {
      throw new RuntimeException(ee);
    }
  }

  private final EventHandler<ReplicationMessage> eventHorizon = new AbstractEventHandler<ReplicationMessage>() {
    @Override
    public void handleEvent(ReplicationMessage message) throws EventHandlerException {
      try {
        processMessage(message);
      } catch (Throwable t) {
        // We don't expect to see an exception executing a replicated message.
        // TODO:  Find a better way to handle this error.
        throw Assert.failure("Unexpected exception executing replicated message", t);
      }
    }

    @Override
    protected void initialize(ConfigurationContext context) {
      ServerConfigurationContext scxt = (ServerConfigurationContext)context;
  //  when this spins up, send  request to active and ask for sync
      scxt.getL2Coordinator().getReplicatedClusterStateManager().setCurrentState(scxt.getL2Coordinator().getStateManager().getCurrentState());
      if (stateManager.getCurrentState().equals(StateManager.PASSIVE_UNINITIALIZED)) {
        requestPassiveSync();
      }
    }

    @Override
    public void destroy() {
      ServerEntityRequest req = new ServerEntityRequest() {
        @Override
        public ServerEntityAction getAction() {
          return ServerEntityAction.NOOP;
        }

        @Override
        public ClientID getNodeID() {
          return ClientID.NULL_ID;
        }

        @Override
        public TransactionID getTransaction() {
          return TransactionID.NULL_ID;
        }

        @Override
        public TransactionID getOldestTransactionOnClient() {
          return TransactionID.NULL_ID;
        }

        @Override
        public ClientDescriptor getSourceDescriptor() {
          return new ClientDescriptorImpl(ClientID.NULL_ID, EntityDescriptor.NULL_ID);
        }

        @Override
        public Set<NodeID> replicateTo(Set<NodeID> passives) {
          return Collections.emptySet();
        }
      };
  //    MGMT_KEY because the request processor needs to be flushed
      for (ManagedEntity me : entityManager.getAll()) {
        BarrierCompletion latch = new BarrierCompletion();
        me.clearQueue();
        me.addRequestMessage(req,
            new MessagePayload(new byte[0], null, ConcurrencyStrategy.MANAGEMENT_KEY), 
            (result)->latch.complete(), exception->Assert.fail());
        latch.waitForCompletion();
      }
      BarrierCompletion latch = new BarrierCompletion();
      platform.addRequestMessage(req, new MessagePayload(new byte[0], null, ConcurrencyStrategy.MANAGEMENT_KEY), (result)->latch.complete(), null);
      latch.waitForCompletion();
    }    
  };
  
  public EventHandler<ReplicationMessage> getEventHandler() {
    return eventHorizon;
  }

  private void processMessage(ReplicationMessage rep) throws EntityException {
    if (PLOGGER.isDebugEnabled()) {
      PLOGGER.debug("RECEIVED:" + rep.getDebugId());
    }
    switch (rep.getType()) {
      case ReplicationMessage.REPLICATE:
        if (state.ignore(rep)) {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Ignoring:" + rep);
          }
          acknowledge(rep, ReplicationResultCode.NONE);
        } else if (state.defer(rep)) {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Deferring:" + rep);
          }
        } else {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Applying:" + rep);
          }
          replicatedMessageReceived(rep);
        }
        break;
      case ReplicationMessage.SYNC:
        syncMessageReceived(rep);
        break;
      case ReplicationMessage.START:
        establishNewPassive(rep);
        break;
      default:
        // This is an unexpected replicated message type.
        throw new RuntimeException();
    }
  }
//  don't need to worry about resends here for lifecycle messages.  active will filer them  
  private void replicatedMessageReceived(ReplicationMessage rep) throws EntityException {
    ClientID sourceNodeID = rep.getSource();
    TransactionID transactionID = rep.getTransactionID();
    TransactionID oldestTransactionOnClient = rep.getOldestTransactionOnClient();
    EntityDescriptor descriptor = rep.getEntityDescriptor();

    // Send the RECEIVED ack before we run this.
    ackReceived(rep);
    
    // Note that we only want to persist the messages with a true sourceNodeID.  Synthetic invocations and sync messages
    // don't have one (although sync messages shouldn't come down this path).
    if (!ClientInstanceID.NULL_ID.equals(sourceNodeID)) {
      if (!oldestTransactionOnClient.isNull()) {
        this.orderedTransactions.updateWithNewMessage(sourceNodeID, transactionID, oldestTransactionOnClient);
      } else {
        // This corresponds to a disconnect.
        this.orderedTransactions.removeTrackingForClient(sourceNodeID);
        this.entityPersistor.removeTrackingForClient(sourceNodeID);
      }
    }

    long version = rep.getVersion();
    EntityID entityID = descriptor.getEntityID();
    byte[] extendedData = rep.getExtendedData();

    // At this point, we can now look up the managed entity (used later).
    Optional<ManagedEntity> entity = entityManager.getEntity(entityID,version);

    // Create the request, since it is how we will generically return complete.
    ServerEntityRequest request = make(rep);
    // If we satisfied this as a known re-send, don't add the request to the entity.
    if (request.getAction() == ServerEntityAction.CREATE_ENTITY) {
// The common pattern for this is to pass an empty array on success ("found") or an exception on failure ("not found").
      long consumerID = this.entityPersistor.getNextConsumerID();
      try {
        ManagedEntity temp = entityManager.createEntity(entityID, descriptor.getClientSideVersion(), consumerID, !sourceNodeID.isNull() ? 0 : ManagedEntity.UNDELETABLE_ENTITY);
        temp.addRequestMessage(request, new MessagePayload(extendedData, null, ConcurrencyStrategy.MANAGEMENT_KEY), 
          (result) -> {
            if (!sourceNodeID.isNull()) {
              entityPersistor.entityCreated(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), entityID, descriptor.getClientSideVersion(), consumerID, true /*from client checked*/, extendedData);
              acknowledge(rep, ReplicationResultCode.SUCCESS);
            } else {
              entityPersistor.entityCreatedNoJournal(entityID, descriptor.getClientSideVersion(), consumerID, true, extendedData);
              acknowledge(rep, ReplicationResultCode.SUCCESS);
            }
          }, (exception) -> {
            entityPersistor.entityCreateFailed(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), exception);
            acknowledge(rep, ReplicationResultCode.FAIL);
          });
      } catch (EntityException ee) {
        acknowledge(rep, ReplicationResultCode.FAIL);
        if (!sourceNodeID.isNull()) {
          entityPersistor.entityCreateFailed(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), ee);
        }
      }
    } else if (entity.isPresent()) {
      ManagedEntity entityInstance = entity.get();
      EntityMessage msg = null;
      try {
        if (rep.getReplicationType() == SyncReplicationActivity.ActivityType.INVOKE_ACTION) {
          msg = entityInstance.getCodec().decodeMessage(extendedData);
        }
      } catch (MessageCodecException codec) {
        throw new RuntimeException(codec);
      }
      MessagePayload payload = new MessagePayload(extendedData, msg, rep.getConcurrency());
      if (null != request.getAction()) switch (request.getAction()) {
        case RECONFIGURE_ENTITY:  
          entity.get().addRequestMessage(request, payload, 
            (result)->{
              entityPersistor.entityReconfigureSucceeded(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), entityID, version, result);
              acknowledge(rep, ReplicationResultCode.SUCCESS);
            } , (exception) -> {
              entityPersistor.entityReconfigureFailed(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), exception);
              acknowledge(rep, ReplicationResultCode.FAIL);
            });
          break;
        case DESTROY_ENTITY:
          entityInstance.addRequestMessage(request, payload, 
            (result)-> {
              entityPersistor.entityDestroyed(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), entityID);
              acknowledge(rep, ReplicationResultCode.SUCCESS);
            }, (exception) -> {
             entityPersistor.entityDestroyFailed(sourceNodeID, transactionID.toLong(), oldestTransactionOnClient.toLong(), exception);
              acknowledge(rep, ReplicationResultCode.FAIL);
            });
          break;
        case FETCH_ENTITY:
        case RELEASE_ENTITY:
          entityInstance.addRequestMessage(request, payload, 
            (result)-> {
              acknowledge(rep, ReplicationResultCode.SUCCESS);
            }, (exception) -> {
              LOGGER.warn("fetch/release fail:" + rep);
              acknowledge(rep, ReplicationResultCode.FAIL);
            });
          break;
        case NOOP:
          if (entityInstance.isRemoveable()) {
            LOGGER.debug("removing " + entityInstance.getID());
            entityManager.removeDestroyed(entityInstance.getID());
          }
          //  fall-through to default
        default:
          entityInstance.addRequestMessage(request, payload, (result)-> acknowledge(rep, ReplicationResultCode.SUCCESS), (exception) -> acknowledge(rep, ReplicationResultCode.FAIL));
          break;
      }
    } else {
 //  fail, just ack
      acknowledge(rep, ReplicationResultCode.FAIL);
    }
  }
  
  private void establishNewPassive(ReplicationMessage rep) {
    entityManager.resetReferences();
  }
  
  private void requestPassiveSync() {
    NodeID node = stateManager.getActiveNodeID();
    Assert.assertTrue(entityManager.getAll().stream().allMatch((e)->e.getID().equals(PlatformEntity.PLATFORM_ID)));
    moveToPassiveUnitialized(node);
    try {
      LOGGER.info("Requesting Passive Sync from " + node);
      groupManager.sendTo(node, ReplicationMessageAck.createSyncRequestMessage());
    } catch (GroupException ge) {
      LOGGER.warn("can't request passive sync", ge);
    }
  }  
  
  private void syncMessageReceived(ReplicationMessage sync) {
    EntityID eid = sync.getEntityDescriptor().getEntityID();
    long version = sync.getVersion();
    
    ackReceived(sync);
    beforeSyncAction(sync);
    
    if (sync.getReplicationType() == SyncReplicationActivity.ActivityType.SYNC_ENTITY_BEGIN && !eid.equals(EntityID.NULL_ID)) {
      try {
        if (!this.entityManager.getEntity(eid, sync.getVersion()).isPresent()) {
          long consumerID = entityPersistor.getNextConsumerID();
 //  repurposed concurrency id to tell passive if entity can be deleted 0 for deletable and 1 for not deletable
          this.entityManager.createEntity(eid, sync.getVersion(), consumerID, sync.getConcurrency());          
          // We record this in the persistor but not record it in the journal since it has no originating client and can't be re-sent. 
          this.entityPersistor.entityCreatedNoJournal(eid, version, consumerID, !sync.getSource().isNull(), sync.getExtendedData());
        } else {
          Assert.fail("this entity should not be here");
        }
      } catch (EntityException exception) {
//  TODO: this needs to be controlled.  
        LOGGER.warn("entity has already been created", exception);
      }
    }
        
    try {
      Optional<ManagedEntity> entity = entityManager.getEntity(eid, version);
      if (entity.isPresent()) {
        MessagePayload payload = new MessagePayload(sync.getExtendedData(), null, sync.getConcurrency());
        entity.get().addRequestMessage(make(sync), payload, (result)->acknowledge(sync, ReplicationResultCode.SUCCESS), (exception)->acknowledge(sync, ReplicationResultCode.FAIL));
        if (sync.getReplicationType() != SyncReplicationActivity.ActivityType.SYNC_ENTITY_CONCURRENCY_PAYLOAD) {
          entity.get().addRequestMessage(makeNoop(eid, version), MessagePayload.EMPTY, null, null);
        }
      } else {
        if (sync.getReplicationType() == SyncReplicationActivity.ActivityType.NOOP) {
          acknowledge(sync, ReplicationResultCode.SUCCESS);
        } else if (!eid.equals(EntityID.NULL_ID)) {
          throw new AssertionError();
        } else {
          MessagePayload payload = new MessagePayload(sync.getExtendedData(), null, sync.getConcurrency());
          platform.addRequestMessage(make(sync), payload, (result)-> {
            if (sync.getReplicationType() == SyncReplicationActivity.ActivityType.SYNC_END) {
              try {
                entityPersistor.layer(new ObjectInputStream(new ByteArrayInputStream(payload.getRawPayload())));
              } catch (IOException ioe) {
                throw new RuntimeException(ioe);
              }
              moveToPassiveStandBy();
            }
            acknowledge(sync, ReplicationResultCode.SUCCESS);
          }, (exception)->acknowledge(sync, ReplicationResultCode.FAIL));
        }
      }
    } catch (EntityException ee) {
      throw new RuntimeException(ee);
    }
  }
  
  private void start() {
    state.start();
  }
  
  private void start(EntityID eid) {
    state.startEntity(eid);
  }
  
  private void start(EntityID eid, int concurrency) {
    state.startConcurrency(eid, concurrency);
  }
  
  private void finish() {
    state.finish();
  }
  
  private void finish(EntityID eid) {
    state.endEntity(eid);
  }
  
  private void finish(EntityID eid, int concurrency) {
    scheduleDeferred(state.endConcurrency(eid, concurrency));
  }
  
  private void scheduleDeferred(Deque<ReplicationMessage> deferred) {
    if (deferred != null) {
      while(!deferred.isEmpty()) {
        ReplicationMessage r = deferred.pop();
        try {
          Assert.assertTrue(r.getType() == ReplicationMessage.REPLICATE);
          replicatedMessageReceived(r);
        } catch (EntityException ee) {
          throw new RuntimeException(ee);
        }
      }
    }
  }
  
  private void moveToPassiveUnitialized(NodeID connectedTo) {
    if (!stateManager.isActiveCoordinator()) {
      stateManager.moveToPassiveSyncing(connectedTo);
    }
  }
  
  private void moveToPassiveStandBy() {
    if (!stateManager.isActiveCoordinator()) {
      stateManager.moveToPassiveStandbyState();
    }
  }
  
  private ServerEntityRequest makeNoop(EntityID eid, long version) {
    // Anything created within this class represents a replicated message.
    boolean isReplicatedMessage = true;
    return new ServerEntityRequestResponse(new EntityDescriptor(eid, ClientInstanceID.NULL_ID, version), ServerEntityAction.NOOP, TransactionID.NULL_ID, TransactionID.NULL_ID, ClientID.NULL_ID, ()->Optional.empty(), isReplicatedMessage);
  }
      
  private ServerEntityRequest make(ReplicationMessage rep) {
    return new BasicServerEntityRequest(decodeReplicationType(rep.getReplicationType()), rep.getSource(),  
      rep.getTransactionID(), rep.getOldestTransactionOnClient(), rep.getEntityDescriptor());
  }
  
  private void beforeSyncAction(ReplicationMessage rep) {
    EntityID eid = rep.getEntityDescriptor().getEntityID();
    switch (rep.getReplicationType()) {
      case SYNC_BEGIN:
        start();
        break;
      case SYNC_END:
        finish();
        break;
      case SYNC_ENTITY_BEGIN:
        start(eid);
        break;
      case SYNC_ENTITY_END:
        finish(eid);
        break;
      case SYNC_ENTITY_CONCURRENCY_BEGIN:
        start(eid, rep.getConcurrency());
        break;
      case SYNC_ENTITY_CONCURRENCY_END:
        finish(eid, rep.getConcurrency());// finish inline so messages are requeued from the proper sync
        break;
      default:
        break;
    }
  }

  private void ackReceived(ReplicationMessage rep) {
    if (!rep.messageFrom().equals(ServerID.NULL_ID)) {
      prepareAckForSend(rep.messageFrom(), rep.getMessageID(), ReplicationResultCode.RECEIVED);
    }
  }

  private void acknowledge(ReplicationMessage rep, ReplicationResultCode code) {
//  when is the right time to send the ack?
    if (!rep.messageFrom().equals(ServerID.NULL_ID)) {
      prepareAckForSend(rep.messageFrom(), rep.getMessageID(), code);
    }
  }

  private synchronized void prepareAckForSend(NodeID sender, MessageID respondTo, ReplicationResultCode code) {
    if (null == this.cachedBatchAck) {
      this.cachedBatchAck = ReplicationMessageAck.createBatchAck();
      this.cachedMessageAckFrom = sender;
    } else {
      Assert.assertTrue(this.cachedMessageAckFrom.equals(sender));
    }

    this.cachedBatchAck.addAck(respondTo, code);
    
    if (!isWaitingForNetwork) {
      synchronizedSendAckBatch();
    }
  }

  private synchronized void handleNetworkDone() {
    this.isWaitingForNetwork = false;
    if (null != this.cachedBatchAck) {
      synchronizedSendAckBatch();
    }
  }

  private void synchronizedSendAckBatch() {
    // Note that we want to set the flags _before_ making the calls since the unit test mock calls back, immediate.
    //  (it shouldn't make a different to any other call since this is already synchronized)
    NodeID cachedMessageAckFrom = this.cachedMessageAckFrom;
    this.cachedMessageAckFrom = null;
    ReplicationMessageAck cachedBatchAck = this.cachedBatchAck;
    this.cachedBatchAck = null;
    this.isWaitingForNetwork = true;
    try {
      groupManager.sendToWithSentCallback(cachedMessageAckFrom, cachedBatchAck, this.handleMessageSend);
    } catch (GroupException e) {
      // Active must have died.  Swallow the exception after logging.
      LOGGER.debug("active died on ack", e);
      this.isWaitingForNetwork = false;
    }
  }

  private static ServerEntityAction decodeReplicationType(SyncReplicationActivity.ActivityType networkType) {
    switch(networkType) {
      case SYNC_BEGIN:
      case SYNC_END:
      case NOOP:
        return ServerEntityAction.NOOP;
      case CREATE_ENTITY:
        return ServerEntityAction.CREATE_ENTITY;
      case RECONFIGURE_ENTITY:
        return ServerEntityAction.RECONFIGURE_ENTITY;
      case INVOKE_ACTION:
        return ServerEntityAction.INVOKE_ACTION;
      case DESTROY_ENTITY:
        return ServerEntityAction.DESTROY_ENTITY;
      case FETCH_ENTITY:
        return ServerEntityAction.FETCH_ENTITY;
      case RELEASE_ENTITY:
        return ServerEntityAction.RELEASE_ENTITY;
      case SYNC_ENTITY_BEGIN:
        return ServerEntityAction.RECEIVE_SYNC_ENTITY_START;
      case SYNC_ENTITY_CONCURRENCY_BEGIN:
        return ServerEntityAction.RECEIVE_SYNC_ENTITY_KEY_START;
      case SYNC_ENTITY_CONCURRENCY_PAYLOAD:
        return ServerEntityAction.RECEIVE_SYNC_PAYLOAD;
      case SYNC_ENTITY_CONCURRENCY_END:
        return ServerEntityAction.RECEIVE_SYNC_ENTITY_KEY_END;
      case SYNC_ENTITY_END:
        return ServerEntityAction.RECEIVE_SYNC_ENTITY_END;
      default:
        throw new AssertionError("bad replication type");
    }
  }  
  
 private class SyncState {
    private LinkedList<ReplicationMessage> defer = new LinkedList<>();
    
    private final Set<EntityID> syncdEntities = new HashSet<>();
    private final Set<Integer> syncdKeys = new HashSet<>();
    private EntityID syncing;
    private int currentKey = -1;
    private boolean finished = false;
    private boolean started = false;
    
    private void start() {
      started = true;
    }
    
    private void startEntity(EntityID eid) {
      assertStarted(null);
      Assert.assertNull(syncing);
      syncing = eid;
// these keys are never sync'd only replicated so add them to the set
      syncdKeys.add(ConcurrencyStrategy.MANAGEMENT_KEY);
      syncdKeys.add(ConcurrencyStrategy.UNIVERSAL_KEY);
      LOGGER.debug("Starting " + eid);
    }
    
    private void endEntity(EntityID eid) {
      assertStarted(null);
      Assert.assertEquals(syncing, eid);
      syncdEntities.add(eid);
      syncdKeys.clear();
      syncing = null;
      LOGGER.debug("Ending " + eid);
    }
    
    private void startConcurrency(EntityID eid, int concurrency) {
      assertStarted(null);
      Assert.assertEquals(syncing, eid);
      currentKey = concurrency;
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Starting " + eid + "/" + currentKey);
      }
    }
    
    private Deque<ReplicationMessage> endConcurrency(EntityID eid, int concurrency) {
      assertStarted(null);
      try {
        if (!eid.equals(syncing) || concurrency != currentKey) {
          throw new AssertionError();
        }
        Assert.assertEquals(syncing, eid);
        Assert.assertEquals(currentKey, concurrency);
        syncdKeys.add(concurrency);
        currentKey = -1;
        return defer;
      } finally {
        defer = new LinkedList<>();
      }
    }
    
    private void finish() {
      assertStarted(null);
      syncdEntities.clear();
      finished = true;
    }
    
    private boolean ignore(ReplicationMessage rep) {
      if (!started) {
 // this passive has never been sync'd to anything, ignore all messages
        return true;
      }
      if (finished) {
//  done with sync, need to apply everything now
        return false;
      }
      EntityID eid = rep.getEntityDescriptor().getEntityID();
//  everything else, check
      if (eid.equals(syncing)) {
        // Note that it is possible that the currently syncing entity has already been destroyed, in which case this message
        //  is either targeting something which doesn't exist or targets something which has been created over top of it.
        // In either case, we shouldn't ignore it.
        if (rep.getConcurrency() == currentKey) {
          return false;
        } else if (!syncdKeys.contains(rep.getConcurrency())) {
//  ignore, haven't gotten to this key yet
          return true;
        } else {
//  valid, already sync'd key, apply
          return false;
        }
      }
      if (rep.getReplicationType() == SyncReplicationActivity.ActivityType.CREATE_ENTITY) {
        syncdEntities.add(eid);
        return false;
      }
//  if not syncing or sync'd, just ignore it.
      return (!syncdEntities.contains(eid));
    }

    private boolean defer(ReplicationMessage rep) {
      assertStarted(rep);
      if (finished) {
//  done with sync, need to apply everything now
        return false;
      }
//  everything else, check
      EntityID eid = rep.getEntityDescriptor().getEntityID();
      if (syncdEntities.contains(eid)) {
        return false;
      } 
      
      if (rep.getReplicationType() == SyncReplicationActivity.ActivityType.CREATE_ENTITY) {
        Assert.fail("create received during a sync of an entity " + syncing);
      }
      
      if (eid.equals(syncing)) {
        if (syncdKeys.contains(rep.getConcurrency())) {
          return false;
        } else if (rep.getReplicationType() == SyncReplicationActivity.ActivityType.NOOP) {
//  NOOP requests cannot be deferred
          return false;
        } else if (rep.getReplicationType() == SyncReplicationActivity.ActivityType.DESTROY_ENTITY) {
          Assert.fail("destroy received during a sync of an entity " + syncing);
          return false;
        } else if (currentKey == rep.getConcurrency()) {
          defer.add(rep);
          return true;
        }
      }
      return false;
    }
    
    /**
     * Note that this state machine the started flag can be used to assert consistency.
     * 
     * The start flag starts the valid stream for a passive.  A passive can only accept valid 
     * messages after sync has started on the server.  Prior to that, everything is invalid
     * and can be safely ignored.  Messages can be received prior to the start sync message
     * because replication started as soon as the active detects a connect from a passive.
     * 
     * Sync start begins after the passive has successfully connected and requested to be sync'd
     * 
     * NOTE: it is possible in the multiple passive scenario, for a stream to start a new 
     * active but in this case, the server will have already been sync'd and thus valid
     */
    private void assertStarted(ReplicationMessage rep) {
      // These should short-circuit quickly, not creating an expensive check overhead.
      Assert.assertTrue(rep, started);
    }
  }
 
  public static class BasicServerEntityRequest implements ServerEntityRequest {
    private final ServerEntityAction action;
    private final ClientID source;
    private final TransactionID transaction;
    private final TransactionID oldest;
    private final EntityDescriptor descriptor;

    public BasicServerEntityRequest(ServerEntityAction action, ClientID source, TransactionID transaction, TransactionID oldest, EntityDescriptor descriptor) {
      this.action = action;
      this.source = source;
      this.transaction = transaction;
      this.oldest = oldest;
      this.descriptor = descriptor;
    }

    @Override
    public ServerEntityAction getAction() {
      return action;
    }

    @Override
    public ClientID getNodeID() {
      return source;
    }

    @Override
    public TransactionID getTransaction() {
      return transaction;
    }

    @Override
    public TransactionID getOldestTransactionOnClient() {
      return oldest;
    }

    @Override
    public ClientDescriptor getSourceDescriptor() {
      return new ClientDescriptorImpl(getNodeID(), this.descriptor);
    }

    @Override
    public Set<NodeID> replicateTo(Set<NodeID> passives) {
      return Collections.emptySet();
    }
  }
}
