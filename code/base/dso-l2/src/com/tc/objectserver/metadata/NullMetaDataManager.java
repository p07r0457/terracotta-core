/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.metadata;

import com.tc.object.dna.api.MetaDataReader;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.tx.ServerTransaction;

public class NullMetaDataManager implements MetaDataManager {

  public void processMetaDatas(ServerTransaction txn, MetaDataReader[] readers) {
    //
  }

  public boolean metaDataProcessingCompleted(TransactionID id) {
    return false;
  }

}