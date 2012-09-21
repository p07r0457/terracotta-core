/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.express.tests.map;

import com.tc.test.config.model.TestConfig;

public class ClusteredCacheTTICrashStrongTest extends AbstractClusteredCacheTTICrashTest {

  public ClusteredCacheTTICrashStrongTest(TestConfig testConfig) {
    super(testConfig, ClusteredCacheTTICrashTestStrongClient.class);
  }

}