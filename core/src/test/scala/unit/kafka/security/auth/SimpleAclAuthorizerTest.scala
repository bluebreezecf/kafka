/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.security.auth

import java.util.UUID

import kafka.network.RequestChannel.Session
import kafka.security.auth.Acl.WildCardHost
import kafka.server.KafkaConfig
import kafka.utils.{ZkUtils, TestUtils}
import kafka.zk.ZooKeeperTestHarness
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.junit.Assert._
import org.junit.{Before, Test}

class SimpleAclAuthorizerTest extends ZooKeeperTestHarness {

  var simpleAclAuthorizer = new SimpleAclAuthorizer
  val testPrincipal = Acl.WildCardPrincipal
  val testHostName = "test.host.com"
  var session = new Session(testPrincipal, testHostName)
  var resource: Resource = null
  val superUsers = "User:superuser1, User:superuser2"
  val username = "alice"
  var config: KafkaConfig = null

  @Before
  override def setUp() {
    super.setUp()

    val props = TestUtils.createBrokerConfig(0, zkConnect)
    props.put(SimpleAclAuthorizer.SuperUsersProp, superUsers)

    config = KafkaConfig.fromProps(props)
    simpleAclAuthorizer.configure(config.originals)
    resource = new Resource(Topic, UUID.randomUUID().toString)
  }

  @Test
  def testTopicAcl() {
    val user1 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val user2 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "rob")
    val user3 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "batman")
    val host1 = "host1"
    val host2 = "host2"

    //user1 has READ access from host1 and host2.
    val acl1 = new Acl(user1, Allow, host1, Read)
    val acl2 = new Acl(user1, Allow, host2, Read)

    //user1 does not have  READ access from host1.
    val acl3 = new Acl(user1, Deny, host1, Read)

    //user1 has Write access from host1 only.
    val acl4 = new Acl(user1, Allow, host1, Write)

    //user1 has DESCRIBE access from all hosts.
    val acl5 = new Acl(user1, Allow, WildCardHost, Describe)

    //user2 has READ access from all hosts.
    val acl6 = new Acl(user2, Allow, WildCardHost, Read)

    //user3 has WRITE access from all hosts.
    val acl7 = new Acl(user3, Allow, WildCardHost, Write)

    val acls = Set[Acl](acl1, acl2, acl3, acl4, acl5, acl6, acl7)

    changeAclAndVerify(Set.empty[Acl], acls, Set.empty[Acl])

    val host1Session = new Session(user1, host1)
    val host2Session = new Session(user1, host2)

    assertTrue("User1 should have READ access from host2", simpleAclAuthorizer.authorize(host2Session, Read, resource))
    assertFalse("User1 should not have READ access from host1 due to denyAcl", simpleAclAuthorizer.authorize(host1Session, Read, resource))
    assertTrue("User1 should have WRITE access from host1", simpleAclAuthorizer.authorize(host1Session, Write, resource))
    assertFalse("User1 should not have WRITE access from host2 as no allow acl is defined", simpleAclAuthorizer.authorize(host2Session, Write, resource))
    assertTrue("User1 should not have DESCRIBE access from host1", simpleAclAuthorizer.authorize(host1Session, Describe, resource))
    assertTrue("User1 should have DESCRIBE access from host2", simpleAclAuthorizer.authorize(host2Session, Describe, resource))
    assertFalse("User1 should not have edit access from host1", simpleAclAuthorizer.authorize(host1Session, Alter, resource))
    assertFalse("User1 should not have edit access from host2", simpleAclAuthorizer.authorize(host2Session, Alter, resource))

    //test if user has READ and write access they also get describe access
    val user2Session = new Session(user2, host1)
    val user3Session = new Session(user3, host1)
    assertTrue("User2 should have DESCRIBE access from host1", simpleAclAuthorizer.authorize(user2Session, Describe, resource))
    assertTrue("User3 should have DESCRIBE access from host2", simpleAclAuthorizer.authorize(user3Session, Describe, resource))
    assertTrue("User2 should have READ access from host1", simpleAclAuthorizer.authorize(user2Session, Read, resource))
    assertTrue("User3 should have WRITE access from host2", simpleAclAuthorizer.authorize(user3Session, Write, resource))
  }

  @Test
  def testDenyTakesPrecedence() {
    val user = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val host = "random-host"
    val session = new Session(user, host)

    val allowAll = Acl.AllowAllAcl
    val denyAcl = new Acl(user, Deny, host, All)
    val acls = Set[Acl](allowAll, denyAcl)

    changeAclAndVerify(Set.empty[Acl], acls, Set.empty[Acl])

    assertFalse("deny should take precedence over allow.", simpleAclAuthorizer.authorize(session, Read, resource))
  }

  @Test
  def testAllowAllAccess() {
    val allowAllAcl = Acl.AllowAllAcl

    changeAclAndVerify(Set.empty[Acl], Set[Acl](allowAllAcl), Set.empty[Acl])

    val session = new Session(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "random"), "random.host")
    assertTrue("allow all acl should allow access to all.", simpleAclAuthorizer.authorize(session, Read, resource))
  }

  @Test
  def testSuperUserHasAccess() {
    val denyAllAcl = new Acl(Acl.WildCardPrincipal, Deny, WildCardHost, All)

    changeAclAndVerify(Set.empty[Acl], Set[Acl](denyAllAcl), Set.empty[Acl])

    val session1 = new Session(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "superuser1"), "random.host")
    val session2 = new Session(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "superuser2"), "random.host")

    assertTrue("superuser always has access, no matter what acls.", simpleAclAuthorizer.authorize(session1, Read, resource))
    assertTrue("superuser always has access, no matter what acls.", simpleAclAuthorizer.authorize(session2, Read, resource))
  }

  @Test
  def testWildCardAcls(): Unit = {
    assertFalse("when acls = [],  authorizer should fail close.", simpleAclAuthorizer.authorize(session, Read, resource))

    val user1 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val host1 = "host1"
    val readAcl = new Acl(user1, Allow, host1, Read)
    val wildCardResource = new Resource(resource.resourceType, Resource.WildCardResource)

    val acls = changeAclAndVerify(Set.empty[Acl], Set[Acl](readAcl), Set.empty[Acl], wildCardResource)

    val host1Session = new Session(user1, host1)
    assertTrue("User1 should have Read access from host1", simpleAclAuthorizer.authorize(host1Session, Read, resource))

    //allow Write to specific topic.
    val writeAcl = new Acl(user1, Allow, host1, Write)
    changeAclAndVerify(Set.empty[Acl], Set[Acl](writeAcl), Set.empty[Acl])

    //deny Write to wild card topic.
    val denyWriteOnWildCardResourceAcl = new Acl(user1, Deny, host1, Write)
    changeAclAndVerify(acls, Set[Acl](denyWriteOnWildCardResourceAcl), Set.empty[Acl], wildCardResource)

    assertFalse("User1 should not have Write access from host1", simpleAclAuthorizer.authorize(host1Session, Write, resource))
  }

  @Test
  def testNoAclFound() {
    assertFalse("when acls = [],  authorizer should fail close.", simpleAclAuthorizer.authorize(session, Read, resource))
  }

  @Test
  def testNoAclFoundOverride() {
    val props = TestUtils.createBrokerConfig(1, zkConnect)
    props.put(SimpleAclAuthorizer.AllowEveryoneIfNoAclIsFoundProp, "true")

    val cfg = KafkaConfig.fromProps(props)
    val testAuthoizer: SimpleAclAuthorizer = new SimpleAclAuthorizer
    testAuthoizer.configure(cfg.originals)
    assertTrue("when acls = null or [],  authorizer should fail open with allow.everyone = true.", testAuthoizer.authorize(session, Read, resource))
  }

  @Test
  def testAclManagementAPIs() {
    val user1 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val user2 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "bob")
    val host1 = "host1"
    val host2 = "host2"

    val acl1 = new Acl(user1, Allow, host1, Read)
    val acl2 = new Acl(user1, Allow, host1, Write)
    val acl3 = new Acl(user2, Allow, host2, Read)
    val acl4 = new Acl(user2, Allow, host2, Write)

    var acls = changeAclAndVerify(Set.empty[Acl], Set[Acl](acl1, acl2, acl3, acl4), Set.empty[Acl])

    //test addAcl is additive
    val acl5 = new Acl(user2, Allow, WildCardHost, Read)
    acls = changeAclAndVerify(acls, Set[Acl](acl5), Set.empty[Acl])

    //test get by principal name.
    TestUtils.waitUntilTrue(() => Map(resource -> Set(acl1, acl2)) == simpleAclAuthorizer.getAcls(user1), "changes not propagated in timeout period")
    TestUtils.waitUntilTrue(() => Map(resource -> Set(acl3, acl4, acl5)) == simpleAclAuthorizer.getAcls(user2), "changes not propagated in timeout period")

    val resourceToAcls = Map[Resource, Set[Acl]](
      new Resource(Topic, Resource.WildCardResource) -> Set[Acl](new Acl(user2, Allow, WildCardHost, Read)),
      new Resource(Cluster, Resource.WildCardResource) -> Set[Acl](new Acl(user2, Allow, host1, Read)),
      new Resource(Group, Resource.WildCardResource) -> acls,
      new Resource(Group, "test-ConsumerGroup") -> acls
    )

    resourceToAcls foreach { case (key, value) => changeAclAndVerify(Set.empty[Acl], value, Set.empty[Acl], key) }
    TestUtils.waitUntilTrue(() => resourceToAcls + (resource -> acls) == simpleAclAuthorizer.getAcls(), "changes not propagated in timeout period.")

    //test remove acl from existing acls.
    acls = changeAclAndVerify(acls, Set.empty[Acl], Set(acl1, acl5))

    //test remove all acls for resource
    simpleAclAuthorizer.removeAcls(resource)
    TestUtils.waitAndVerifyAcls(Set.empty[Acl], simpleAclAuthorizer, resource)
    assertTrue(!zkUtils.pathExists(simpleAclAuthorizer.toResourcePath(resource)))

    //test removing last acl also deletes zookeeper path
    acls = changeAclAndVerify(Set.empty[Acl], Set(acl1), Set.empty[Acl])
    changeAclAndVerify(acls, Set.empty[Acl], acls)
    assertTrue(!zkUtils.pathExists(simpleAclAuthorizer.toResourcePath(resource)))
  }

  @Test
  def testLoadCache() {
    val user1 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val acl1 = new Acl(user1, Allow, "host-1", Read)
    val acls = Set[Acl](acl1)
    simpleAclAuthorizer.addAcls(acls, resource)

    val user2 = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "bob")
    val resource1 = new Resource(Topic, "test-2")
    val acl2 = new Acl(user2, Deny, "host3", Read)
    val acls1 = Set[Acl](acl2)
    simpleAclAuthorizer.addAcls(acls1, resource1)

    zkUtils.deletePathRecursive(SimpleAclAuthorizer.AclChangedZkPath)
    val authorizer = new SimpleAclAuthorizer
    authorizer.configure(config.originals)

    assertEquals(acls, authorizer.getAcls(resource))
    assertEquals(acls1, authorizer.getAcls(resource1))
  }

  private def changeAclAndVerify(originalAcls: Set[Acl], addedAcls: Set[Acl], removedAcls: Set[Acl], resource: Resource = resource): Set[Acl] = {
    var acls = originalAcls

    if(addedAcls.nonEmpty) {
      simpleAclAuthorizer.addAcls(addedAcls, resource)
      acls ++= addedAcls
    }

    if(removedAcls.nonEmpty) {
      simpleAclAuthorizer.removeAcls(removedAcls, resource)
      acls --=removedAcls
    }

    TestUtils.waitAndVerifyAcls(acls, simpleAclAuthorizer, resource)

    acls
  }
}