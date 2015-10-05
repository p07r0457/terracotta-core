package com.tc.objectserver.entity;

import com.tc.l2.msg.PassiveSyncMessage;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import org.terracotta.entity.ClientDescriptor;
import org.terracotta.entity.ActiveServerEntity;
import org.terracotta.entity.CommonServerEntity;
import org.terracotta.entity.PassiveServerEntity;
import org.terracotta.entity.ServerEntityService;
import org.terracotta.entity.ServiceRegistry;

import com.tc.net.NodeID;
import com.tc.object.EntityDescriptor;
import com.tc.object.EntityID;
import com.tc.objectserver.api.ManagedEntity;
import com.tc.objectserver.api.ServerEntityRequest;
import com.tc.util.Assert;
import java.util.Collections;
import java.util.Iterator;
import org.terracotta.entity.ConcurrencyStrategy;

public class ManagedEntityImpl implements ManagedEntity {
  private final RequestProcessor executor;

  private final EntityID id;
  private final long version;
  private final ServiceRegistry registry;
  private final ClientEntityStateManager clientEntityStateManager;
  private final ServerEntityService<? extends ActiveServerEntity, ? extends PassiveServerEntity> factory;
  // isInActiveState defines which entity type to check/create - we need the flag to represent the pre-create state.
  private boolean isInActiveState;
  private volatile ActiveServerEntity activeServerEntity;
  private volatile PassiveServerEntity passiveServerEntity;
  // NOTE:  This may be removed in the future if we change how we access the config from the ServerEntityService but
  //  it presently holds the config we used when we first created passiveServerEntity (if it isn't null).  It is used
  //  when we promote to an active.
  private byte[] configFromPassiveCreate;

  ManagedEntityImpl(EntityID id, long version, ServiceRegistry registry, ClientEntityStateManager clientEntityStateManager,
                    RequestProcessor process, 
                    ServerEntityService<? extends ActiveServerEntity, ? extends PassiveServerEntity> factory,
                    boolean isInActiveState) {
    this.id = id;
    this.version = version;
    this.registry = registry;
    this.clientEntityStateManager = clientEntityStateManager;
    this.factory = factory;
    this.executor = process;
    this.isInActiveState = isInActiveState;
  }

  @Override
  public EntityID getID() {
    return id;
  }

  @Override
  public long getVersion() {
    return version;
  }

  @Override
  public void addRequest(ServerEntityRequest request) {
    ClientDescriptor client = request.getSourceDescriptor();
    executor.scheduleRequest(this, getEntityDescriptorForSource(client), activeServerEntity != null ? activeServerEntity.getConcurrencyStrategy() : null, request);
  }

  @Override
  public void reconnectClient(NodeID nodeID, ClientDescriptor clientDescriptor, byte[] extendedReconnectData) {
    EntityDescriptor entityDescriptor = getEntityDescriptorForSource(clientDescriptor);
    clientEntityStateManager.addReference(nodeID, entityDescriptor);
    this.activeServerEntity.connected(clientDescriptor);
    this.activeServerEntity.handleReconnect(clientDescriptor, extendedReconnectData);
  }
  

  /**
   * Pull one request off the request sequencer and service it. 
   *  
   * TODO: Should we return a boolean here for scheduling hints?  
   * @param request
   */
  public void invoke(ServerEntityRequest request) {
      try {
        switch (request.getAction()) {
          case CREATE_ENTITY:
            createEntity(request);
            break;
          case INVOKE_ACTION:
            performAction(request);
            break;
          case FETCH_ENTITY:
            getEntity(request);
            break;
          case RELEASE_ENTITY:
            releaseEntity(request);
            break;
          case DESTROY_ENTITY:
//  TODO: need a way to flush the concurrency queues to make sure no other actions
//            are scheduled on this entity before destruction
            destroyEntity(request);
            break;
          case PROMOTE_ENTITY_TO_ACTIVE:
            promoteEntity(request);
            break;
          case SYNC_ENTITY:
//  use typing for this distinction since it is server generated?
            performSync(request);
          case LOAD_EXISTING_ENTITY:
            loadExisting(request);
            break;
          default:
            throw new IllegalArgumentException("Unknown request " + request);
        }
      } catch (Exception e) {
        request.failure(e);
      }
  }
  
  private void destroyEntity(ServerEntityRequest request) {
    CommonServerEntity commonServerEntity = this.isInActiveState
        ? activeServerEntity
        : passiveServerEntity;
    if (null != commonServerEntity) {
      ClientDescriptor sourceDescriptor = request.getSourceDescriptor();
      EntityDescriptor entityDescriptor = getEntityDescriptorForSource(sourceDescriptor);
      clientEntityStateManager.removeReference(request.getNodeID(), entityDescriptor);
      commonServerEntity.destroy();
    }
    request.complete();
  }

  private void createEntity(ServerEntityRequest createEntityRequest) {
    byte[] configuration = createEntityRequest.getPayload();
    CommonServerEntity entityToCreate = null;
    // Create the appropriate kind of entity, based on our active/passive state.
    if (this.isInActiveState) {
      if (null != this.activeServerEntity) {
        throw new IllegalStateException("Active entity " + id + " already exists.");
      } else {
        this.activeServerEntity = factory.createActiveEntity(registry, configuration);
        entityToCreate = this.activeServerEntity;
      }
    } else {
      if (null != this.passiveServerEntity) {
        throw new IllegalStateException("Passive entity " + id + " already exists.");
      } else {
        this.passiveServerEntity = factory.createPassiveEntity(registry, configuration);
        // Store the configuration in case we promote.
        this.configFromPassiveCreate = configuration;
        entityToCreate = this.passiveServerEntity;
      }
    }
    createEntityRequest.complete();
    // We currently don't support loading an entity from a persistent back-end and this call is in response to creating a new
    //  instance so make that call.
    entityToCreate.createNew();
  }

  //  TODO: stub implementation.  This is supposed to send the data to the passive server for sync
  private void performSync(ServerEntityRequest wrappedRequest) {
    if (this.isInActiveState) {
      if (null == this.activeServerEntity) {
        throw new IllegalStateException("Actions on a non-existent entity.");
      } else {
        int concurrency = PassiveSyncServerEntityRequest.getConcurrency(wrappedRequest.getPayload());
        for (byte[] payload : this.activeServerEntity.sync(concurrency)) {
          ((PassiveSyncServerEntityRequest)wrappedRequest).sendToPassive(new PassiveSyncMessage(id, concurrency, payload));
        }
        wrappedRequest.complete();
      }
    } else {
      if (null == this.passiveServerEntity) {
        throw new IllegalStateException("Actions on a non-existent entity.");
      } else {
//  doing nothing for sync
      }
    }
  }
  
  private void performAction(ServerEntityRequest wrappedRequest) {
    if (this.isInActiveState) {
      if (null == this.activeServerEntity) {
        throw new IllegalStateException("Actions on a non-existent entity.");
      } else {
        wrappedRequest.complete(this.activeServerEntity.invoke(wrappedRequest.getSourceDescriptor(), wrappedRequest.getPayload()));
      }
    } else {
      if (null == this.passiveServerEntity) {
        throw new IllegalStateException("Actions on a non-existent entity.");
      } else {
        this.passiveServerEntity.invoke(wrappedRequest.getPayload());
        wrappedRequest.complete();
      }
    }
  }

  private void getEntity(ServerEntityRequest getEntityRequest) {
    if (this.isInActiveState) {
      if (null != this.activeServerEntity) {
        ClientDescriptor sourceDescriptor = getEntityRequest.getSourceDescriptor();
        EntityDescriptor entityDescriptor = getEntityDescriptorForSource(sourceDescriptor);
        clientEntityStateManager.addReference(getEntityRequest.getNodeID(), entityDescriptor);
        this.activeServerEntity.connected(sourceDescriptor);
        getEntityRequest.complete(this.activeServerEntity.getConfig());
      } else {
        getEntityRequest.complete();
      }
    } else {
      throw new IllegalStateException("GET called on passive entity.");
    }
  }

  private void releaseEntity(ServerEntityRequest request) {
    if (this.isInActiveState) {
      if (null != this.activeServerEntity) {
        ClientDescriptor sourceDescriptor = request.getSourceDescriptor();
        EntityDescriptor entityDescriptor = getEntityDescriptorForSource(sourceDescriptor);
        clientEntityStateManager.removeReference(request.getNodeID(), entityDescriptor);
        this.activeServerEntity.disconnected(sourceDescriptor);
      }
      request.complete();
    } else {
      throw new IllegalStateException("RELEASE called on passive entity.");
    }
  }
  
  private EntityDescriptor getEntityDescriptorForSource(ClientDescriptor sourceDescriptor) {
    // We are in internal code so downcast the descriptor.
    ClientDescriptorImpl rawDescriptor = (ClientDescriptorImpl)sourceDescriptor;
    return rawDescriptor.getEntityDescriptor();
  }

  private void promoteEntity(ServerEntityRequest request) {
    // Can't enter active state twice.
    Assert.assertFalse(this.isInActiveState);
    Assert.assertNull(this.activeServerEntity);
    
    this.isInActiveState = true;
    if (null != this.passiveServerEntity) {
      byte[] configuration = this.configFromPassiveCreate;
      this.activeServerEntity = factory.createActiveEntity(this.registry, configuration);
      this.activeServerEntity.loadExisting();
      this.passiveServerEntity = null;
      this.configFromPassiveCreate = null;
    }
    request.complete();
  }

  @Override
  public void sync(NodeID passive, GroupManager mgr) throws GroupException {
    mgr.sendTo(passive, new PassiveSyncMessage(id, version, true));
// TODO:  This is a stub, the real implementation is to be designed
    for (Integer concurrency : activeServerEntity.getConcurrencyStrategy()) {
      mgr.sendTo(passive, new PassiveSyncMessage(id, concurrency, true));
      PassiveSyncServerEntityRequest req = new PassiveSyncServerEntityRequest(id, version, concurrency, mgr, passive);
      executor.scheduleRequest(this, getEntityDescriptorForSource(req.getSourceDescriptor()), new DirectConcurrencyStrategy(concurrency), req);
      req.waitFor();
      mgr.sendTo(passive, new PassiveSyncMessage(id, concurrency, false));
    }
    mgr.sendTo(passive, new PassiveSyncMessage(id, version, false));
  }

  private void loadExisting(ServerEntityRequest loadEntityRequest) {
    byte[] configuration = loadEntityRequest.getPayload();
    CommonServerEntity entityToLoad = null;
    // Create the appropriate kind of entity, based on our active/passive state.
    if (this.isInActiveState) {
      if (null != this.activeServerEntity) {
        throw new IllegalStateException("Active entity " + id + " already exists.");
      } else {
        this.activeServerEntity = factory.createActiveEntity(registry, configuration);
        entityToLoad = this.activeServerEntity;
      }
    } else {
      if (null != this.passiveServerEntity) {
        throw new IllegalStateException("Passive entity " + id + " already exists.");
      } else {
        this.passiveServerEntity = factory.createPassiveEntity(registry, configuration);
        // Store the configuration in case we promote.
        this.configFromPassiveCreate = configuration;
        entityToLoad = this.passiveServerEntity;
      }
    }
    loadEntityRequest.complete();
    entityToLoad.loadExisting();
  }
  
  private static class DirectConcurrencyStrategy implements ConcurrencyStrategy {
    private final int target;

    public DirectConcurrencyStrategy(int target) {
      this.target = target;
    }
    
    @Override
    public int concurrencyKey(byte[] payload) {
      return target;
    }

    @Override
    public Iterator<Integer> iterator() {
      return Collections.singleton(target).iterator();
    }
    
  }
}
