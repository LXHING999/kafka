/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package kafka.server

import java.net.Socket
import java.nio.ByteBuffer
import java.util.{Collections, LinkedHashMap, Properties}
import java.util.concurrent.{Executors, Future, TimeUnit}

import kafka.admin.AdminUtils
import kafka.log.LogConfig
import kafka.network.RequestChannel.Session
import kafka.security.auth._
import kafka.utils.TestUtils
import org.apache.kafka.common.acl.{AccessControlEntry, AccessControlEntryFilter, AclBinding, AclBindingFilter, AclOperation, AclPermissionType}
import org.apache.kafka.common.resource.{ResourceFilter, Resource => AdminResource, ResourceType => AdminResourceType}
import org.apache.kafka.common.{Node, TopicPartition}
import org.apache.kafka.common.metrics.{KafkaMetric, Quota, Sensor}
import org.apache.kafka.common.network.{Authenticator, ListenerName, TransportLayer}
import org.apache.kafka.common.protocol.{ApiKeys, SecurityProtocol}
import org.apache.kafka.common.protocol.types.Struct
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.CreateAclsRequest.AclCreation
import org.apache.kafka.common.requests.{Resource => RResource, ResourceType => RResourceType, _}
import org.apache.kafka.common.security.auth.{DefaultPrincipalBuilder, KafkaPrincipal}
import org.junit.Assert._
import org.junit.{After, Before, Test}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer


class RequestQuotaTest extends BaseRequestTest {

  override def numBrokers: Int = 1

  private val topic = "topic-1"
  private val numPartitions = 1
  private val tp = new TopicPartition(topic, 0)
  private val unthrottledClientId = "unthrottled-client"
  private val brokerId: Integer = 0
  private var leaderNode: KafkaServer = null

  // Run tests concurrently since a throttle could be up to 1 second because quota percentage allocated is very low
  case class Task(apiKey: ApiKeys, future: Future[_])
  private val executor = Executors.newCachedThreadPool
  private val tasks = new ListBuffer[Task]

  override def propertyOverrides(properties: Properties): Unit = {
    properties.put(KafkaConfig.ControlledShutdownEnableProp, "false")
    properties.put(KafkaConfig.OffsetsTopicReplicationFactorProp, "1")
    properties.put(KafkaConfig.OffsetsTopicPartitionsProp, "1")
    properties.put(KafkaConfig.GroupMinSessionTimeoutMsProp, "100")
    properties.put(KafkaConfig.GroupInitialRebalanceDelayMsProp, "0")
    properties.put(KafkaConfig.AuthorizerClassNameProp, classOf[RequestQuotaTest.TestAuthorizer].getName)
    properties.put(KafkaConfig.PrincipalBuilderClassProp, classOf[RequestQuotaTest.TestPrincipalBuilder].getName)
  }

  @Before
  override def setUp() {
    RequestQuotaTest.principal = KafkaPrincipal.ANONYMOUS
    super.setUp()

    TestUtils.createTopic(zkUtils, topic, numPartitions, 1, servers)
    leaderNode = servers.head

    // Change default client-id request quota to a small value and a single unthrottledClient with a large quota
    val quotaProps = new Properties()
    quotaProps.put(DynamicConfig.Client.RequestPercentageOverrideProp, "0.01")
    AdminUtils.changeClientIdConfig(zkUtils, "<default>", quotaProps)
    quotaProps.put(DynamicConfig.Client.RequestPercentageOverrideProp, "2000")
    AdminUtils.changeClientIdConfig(zkUtils, unthrottledClientId, quotaProps)

    TestUtils.retry(10000) {
      val quotaManager = servers(0).apis.quotas.request
      assertEquals(s"Default request quota not set", Quota.upperBound(0.01), quotaManager.quota("some-user", "some-client"))
      assertEquals(s"Request quota override not set", Quota.upperBound(2000), quotaManager.quota("some-user", unthrottledClientId))
    }
  }

  @After
  override def tearDown() {
    try executor.shutdownNow()
    finally super.tearDown()
  }

  @Test
  def testResponseThrottleTime() {
    for (apiKey <- RequestQuotaTest.ClientActions)
      submitTest(apiKey, () => checkRequestThrottleTime(apiKey))

    waitAndCheckResults()
  }

  @Test
  def testUnthrottledClient() {
    for (apiKey <- RequestQuotaTest.ClientActions)
      submitTest(apiKey, () => checkUnthrottledClient(apiKey))

    waitAndCheckResults()
  }

  @Test
  def testExemptRequestTime() {
    for (apiKey <- RequestQuotaTest.ClusterActions)
      submitTest(apiKey, () => checkExemptRequestMetric(apiKey))

    waitAndCheckResults()
  }

  @Test
  def testUnauthorizedThrottle() {
    RequestQuotaTest.principal = RequestQuotaTest.UnauthorizedPrincipal

    for (apiKey <- ApiKeys.values)
      submitTest(apiKey, () => checkUnauthorizedRequestThrottle(apiKey))

    waitAndCheckResults()
  }

  private def throttleTimeMetricValue(clientId: String): Double = {
    val metricName = leaderNode.metrics.metricName("throttle-time",
                                  QuotaType.Request.toString,
                                  "",
                                  "user", "",
                                  "client-id", clientId)
    val sensor = leaderNode.quotaManagers.request.getOrCreateQuotaSensors("ANONYMOUS", clientId).throttleTimeSensor
    metricValue(leaderNode.metrics.metrics.get(metricName), sensor)
  }

  private def requestTimeMetricValue(clientId: String): Double = {
    val metricName = leaderNode.metrics.metricName("request-time",
                                  QuotaType.Request.toString,
                                  "",
                                  "user", "",
                                  "client-id", clientId)
    val sensor = leaderNode.quotaManagers.request.getOrCreateQuotaSensors("ANONYMOUS", clientId).quotaSensor
    metricValue(leaderNode.metrics.metrics.get(metricName), sensor)
  }

  private def exemptRequestMetricValue: Double = {
    val metricName = leaderNode.metrics.metricName("exempt-request-time", QuotaType.Request.toString, "")
    metricValue(leaderNode.metrics.metrics.get(metricName), leaderNode.quotaManagers.request.exemptSensor)
  }

  private def metricValue(metric: KafkaMetric, sensor: Sensor): Double = {
    sensor.synchronized {
      if (metric == null) -1.0 else metric.value
    }
  }

  private def requestBuilder(apiKey: ApiKeys): AbstractRequest.Builder[_ <: AbstractRequest] = {
    apiKey match {
        case ApiKeys.PRODUCE =>
          new ProduceRequest.Builder(RecordBatch.CURRENT_MAGIC_VALUE, 1, 5000,
            collection.mutable.Map(tp -> MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("test".getBytes))).asJava)

        case ApiKeys.FETCH =>
          val partitionMap = new LinkedHashMap[TopicPartition, FetchRequest.PartitionData]
          partitionMap.put(tp, new FetchRequest.PartitionData(0, 0, 100))
          FetchRequest.Builder.forConsumer(0, 0, partitionMap)

        case ApiKeys.METADATA =>
          new MetadataRequest.Builder(List(topic).asJava, true)

        case ApiKeys.LIST_OFFSETS =>
          ListOffsetRequest.Builder.forConsumer(false, IsolationLevel.READ_UNCOMMITTED)
            .setTargetTimes(Map(tp -> (0L: java.lang.Long)).asJava)

        case ApiKeys.LEADER_AND_ISR =>
          new LeaderAndIsrRequest.Builder(brokerId, Int.MaxValue,
            Map(tp -> new PartitionState(Int.MaxValue, brokerId, Int.MaxValue, List(brokerId).asJava, 2, Seq(brokerId).asJava)).asJava,
            Set(new Node(brokerId, "localhost", 0)).asJava)

        case ApiKeys.STOP_REPLICA =>
          new StopReplicaRequest.Builder(brokerId, Int.MaxValue, true, Set(tp).asJava)

        case ApiKeys.UPDATE_METADATA_KEY =>
          val partitionState = Map(tp -> new PartitionState(Int.MaxValue, brokerId, Int.MaxValue, List(brokerId).asJava, 2, Seq(brokerId).asJava)).asJava
          val securityProtocol = SecurityProtocol.PLAINTEXT
          val brokers = Set(new UpdateMetadataRequest.Broker(brokerId,
            Seq(new UpdateMetadataRequest.EndPoint("localhost", 0, securityProtocol,
            ListenerName.forSecurityProtocol(securityProtocol))).asJava, null)).asJava
          new UpdateMetadataRequest.Builder(ApiKeys.UPDATE_METADATA_KEY.latestVersion, brokerId, Int.MaxValue, partitionState, brokers)

        case ApiKeys.CONTROLLED_SHUTDOWN_KEY =>
          new ControlledShutdownRequest.Builder(brokerId)

        case ApiKeys.OFFSET_COMMIT =>
          new OffsetCommitRequest.Builder("test-group",
            Map(tp -> new OffsetCommitRequest.PartitionData(0, "metadata")).asJava).
            setMemberId("").setGenerationId(1).setRetentionTime(1000)

        case ApiKeys.OFFSET_FETCH =>
          new OffsetFetchRequest.Builder("test-group", List(tp).asJava)

        case ApiKeys.FIND_COORDINATOR =>
          new FindCoordinatorRequest.Builder(FindCoordinatorRequest.CoordinatorType.GROUP, "test-group")

        case ApiKeys.JOIN_GROUP =>
          new JoinGroupRequest.Builder("test-join-group", 200, "", "consumer",
            List(new JoinGroupRequest.ProtocolMetadata("consumer-range", ByteBuffer.wrap("test".getBytes()))).asJava)
           .setRebalanceTimeout(100)

        case ApiKeys.HEARTBEAT =>
          new HeartbeatRequest.Builder("test-group", 1, "")

        case ApiKeys.LEAVE_GROUP =>
          new LeaveGroupRequest.Builder("test-leave-group", "")

        case ApiKeys.SYNC_GROUP =>
          new SyncGroupRequest.Builder("test-sync-group", 1, "", Map[String, ByteBuffer]().asJava)

        case ApiKeys.DESCRIBE_GROUPS =>
          new DescribeGroupsRequest.Builder(List("test-group").asJava)

        case ApiKeys.LIST_GROUPS =>
          new ListGroupsRequest.Builder()

        case ApiKeys.SASL_HANDSHAKE =>
          new SaslHandshakeRequest.Builder("PLAIN")

        case ApiKeys.API_VERSIONS =>
          new ApiVersionsRequest.Builder

        case ApiKeys.CREATE_TOPICS =>
          new CreateTopicsRequest.Builder(Map("topic-2" -> new CreateTopicsRequest.TopicDetails(1, 1.toShort)).asJava, 0)

        case ApiKeys.DELETE_TOPICS =>
          new DeleteTopicsRequest.Builder(Set("topic-2").asJava, 5000)

        case ApiKeys.DELETE_RECORDS =>
          new DeleteRecordsRequest.Builder(5000, Map(tp -> (0L: java.lang.Long)).asJava)

        case ApiKeys.INIT_PRODUCER_ID =>
          new InitProducerIdRequest.Builder("abc")

        case ApiKeys.OFFSET_FOR_LEADER_EPOCH =>
          new OffsetsForLeaderEpochRequest.Builder().add(tp, 0)

        case ApiKeys.ADD_PARTITIONS_TO_TXN =>
          new AddPartitionsToTxnRequest.Builder("test-transactional-id", 1, 0, List(tp).asJava)

        case ApiKeys.ADD_OFFSETS_TO_TXN =>
          new AddOffsetsToTxnRequest.Builder("test-transactional-id", 1, 0, "test-txn-group")

        case ApiKeys.END_TXN =>
          new EndTxnRequest.Builder("test-transactional-id", 1, 0, TransactionResult.forId(false))

        case ApiKeys.WRITE_TXN_MARKERS =>
          new WriteTxnMarkersRequest.Builder(List.empty.asJava)

        case ApiKeys.TXN_OFFSET_COMMIT =>
          new TxnOffsetCommitRequest.Builder("test-transactional-id", "test-txn-group", 2, 0,
            Map.empty[TopicPartition, TxnOffsetCommitRequest.CommittedOffset].asJava)

        case ApiKeys.DESCRIBE_ACLS =>
          new DescribeAclsRequest.Builder(AclBindingFilter.ANY)

        case ApiKeys.CREATE_ACLS =>
          new CreateAclsRequest.Builder(Collections.singletonList(new AclCreation(new AclBinding(
            new AdminResource(AdminResourceType.TOPIC, "mytopic"),
            new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.WRITE, AclPermissionType.DENY)))))

        case ApiKeys.DELETE_ACLS =>
          new DeleteAclsRequest.Builder(Collections.singletonList(new AclBindingFilter(
            new ResourceFilter(AdminResourceType.TOPIC, null),
            new AccessControlEntryFilter("User:ANONYMOUS", "*", AclOperation.ANY, AclPermissionType.DENY))))

        case ApiKeys.DESCRIBE_CONFIGS =>
          new DescribeConfigsRequest.Builder(Collections.singleton(new RResource(RResourceType.TOPIC, tp.topic)))

        case ApiKeys.ALTER_CONFIGS =>
          new AlterConfigsRequest.Builder(
            Collections.singletonMap(new RResource(RResourceType.TOPIC, tp.topic),
              new AlterConfigsRequest.Config(Collections.singleton(
                new AlterConfigsRequest.ConfigEntry(LogConfig.MaxMessageBytesProp, "1000000")
              ))), true)

        case _ =>
          throw new IllegalArgumentException("Unsupported API key " + apiKey)
    }
  }

  private def requestResponse(socket: Socket, clientId: String, correlationId: Int, requestBuilder: AbstractRequest.Builder[_ <: AbstractRequest]): Struct = {
    val apiKey = requestBuilder.apiKey
    val request = requestBuilder.build()
    val header = new RequestHeader(apiKey.id, request.version, clientId, correlationId)
    val response = requestAndReceive(socket, request.serialize(header).array)
    val responseBuffer = skipResponseHeader(response)
    apiKey.parseResponse(request.version, responseBuffer)
  }

  case class Client(clientId: String, apiKey: ApiKeys) {
    var correlationId: Int = 0
    val builder = requestBuilder(apiKey)
    def runUntil(until: (Struct) => Boolean): Boolean = {
      val startMs = System.currentTimeMillis
      var done = false
      val socket = connect()
      try {
        while (!done && System.currentTimeMillis < startMs + 10000) {
          correlationId += 1
          val response = requestResponse(socket, clientId, correlationId, builder)
          done = until.apply(response)
        }
      } finally {
        socket.close()
      }
      done
    }

    override def toString: String = {
      val requestTime = requestTimeMetricValue(clientId)
      val throttleTime = throttleTimeMetricValue(clientId)
      s"Client $clientId apiKey ${apiKey} requests $correlationId requestTime $requestTime throttleTime $throttleTime"
    }
  }

  private def submitTest(apiKey: ApiKeys, test: () => Unit) {
    val future = executor.submit(new Runnable() {
      def run() {
        test.apply()
      }
    })
    tasks += Task(apiKey, future)
  }

  private def waitAndCheckResults() {
    for (task <- tasks) {
      try {
        task.future.get(15, TimeUnit.SECONDS)
      } catch {
        case e: Throwable => {
          error(s"Test failed for api-key ${task.apiKey} with exception $e")
          throw e
        }
      }
    }
  }

  private def responseThrottleTime(apiKey: ApiKeys, response: Struct): Int = {
    apiKey match {
      case ApiKeys.PRODUCE => new ProduceResponse(response).getThrottleTime
      case ApiKeys.FETCH => new FetchResponse(response).throttleTimeMs
      case ApiKeys.LIST_OFFSETS => new ListOffsetResponse(response).throttleTimeMs
      case ApiKeys.METADATA => new MetadataResponse(response).throttleTimeMs
      case ApiKeys.OFFSET_COMMIT => new OffsetCommitResponse(response).throttleTimeMs
      case ApiKeys.OFFSET_FETCH => new OffsetFetchResponse(response).throttleTimeMs
      case ApiKeys.FIND_COORDINATOR => new FindCoordinatorResponse(response).throttleTimeMs
      case ApiKeys.JOIN_GROUP => new JoinGroupResponse(response).throttleTimeMs
      case ApiKeys.HEARTBEAT => new HeartbeatResponse(response).throttleTimeMs
      case ApiKeys.LEAVE_GROUP => new LeaveGroupResponse(response).throttleTimeMs
      case ApiKeys.SYNC_GROUP => new SyncGroupResponse(response).throttleTimeMs
      case ApiKeys.DESCRIBE_GROUPS => new DescribeGroupsResponse(response).throttleTimeMs
      case ApiKeys.LIST_GROUPS => new ListGroupsResponse(response).throttleTimeMs
      case ApiKeys.API_VERSIONS => new ApiVersionsResponse(response).throttleTimeMs
      case ApiKeys.CREATE_TOPICS => new CreateTopicsResponse(response).throttleTimeMs
      case ApiKeys.DELETE_TOPICS => new DeleteTopicsResponse(response).throttleTimeMs
      case ApiKeys.DELETE_RECORDS => new DeleteRecordsResponse(response).throttleTimeMs
      case ApiKeys.INIT_PRODUCER_ID => new InitProducerIdResponse(response).throttleTimeMs
      case ApiKeys.ADD_PARTITIONS_TO_TXN => new AddPartitionsToTxnResponse(response).throttleTimeMs
      case ApiKeys.ADD_OFFSETS_TO_TXN => new AddOffsetsToTxnResponse(response).throttleTimeMs
      case ApiKeys.END_TXN => new EndTxnResponse(response).throttleTimeMs
      case ApiKeys.TXN_OFFSET_COMMIT => new TxnOffsetCommitResponse(response).throttleTimeMs
      case ApiKeys.DESCRIBE_ACLS => new DescribeAclsResponse(response).throttleTimeMs
      case ApiKeys.CREATE_ACLS => new CreateAclsResponse(response).throttleTimeMs
      case ApiKeys.DELETE_ACLS => new DeleteAclsResponse(response).throttleTimeMs
      case ApiKeys.DESCRIBE_CONFIGS => new DescribeConfigsResponse(response).throttleTimeMs
      case ApiKeys.ALTER_CONFIGS => new AlterConfigsResponse(response).throttleTimeMs
      case requestId => throw new IllegalArgumentException(s"No throttle time for $requestId")
    }
  }

  private def checkRequestThrottleTime(apiKey: ApiKeys) {

    // Request until throttled using client-id with default small quota
    val clientId = apiKey.toString
    val client = Client(clientId, apiKey)
    val throttled = client.runUntil(response => responseThrottleTime(apiKey, response) > 0)

    assertTrue(s"Response not throttled: $client", throttled)
    assertTrue(s"Throttle time metrics not updated: $client" , throttleTimeMetricValue(clientId) > 0)
  }

  private def checkUnthrottledClient(apiKey: ApiKeys) {

    // Test that request from client with large quota is not throttled
    val unthrottledClient = Client(unthrottledClientId, apiKey)
    unthrottledClient.runUntil(response => responseThrottleTime(apiKey, response) <= 0.0)
    assertEquals(1, unthrottledClient.correlationId)
    assertTrue(s"Client should not have been throttled: $unthrottledClient", throttleTimeMetricValue(unthrottledClientId) <= 0.0)
  }

  private def checkExemptRequestMetric(apiKey: ApiKeys) {
    val exemptTarget = exemptRequestMetricValue + 0.02
    val clientId = apiKey.toString
    val client = Client(clientId, apiKey)
    val updated = client.runUntil(response => exemptRequestMetricValue > exemptTarget)

    assertTrue(s"Exempt-request-time metric not updated: $client", updated)
    assertTrue(s"Client should not have been throttled: $client", throttleTimeMetricValue(clientId) <= 0.0)
  }

  private def checkUnauthorizedRequestThrottle(apiKey: ApiKeys) {
    val clientId = "unauthorized-" + apiKey.toString
    val client = Client(clientId, apiKey)
    val throttled = client.runUntil(response => throttleTimeMetricValue(clientId) > 0.0)
    assertTrue(s"Unauthorized client should have been throttled: $client", throttled)
  }
}

object RequestQuotaTest {
  val ClusterActions = ApiKeys.values.toSet.filter(apiKey => apiKey.clusterAction)
  val ClientActions = ApiKeys.values.toSet -- ClusterActions - ApiKeys.SASL_HANDSHAKE

  val UnauthorizedPrincipal = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "Unauthorized")
  // Principal used for all client connections. This is modified by tests which
  // check unauthorized code path
  var principal = KafkaPrincipal.ANONYMOUS
  class TestAuthorizer extends SimpleAclAuthorizer {
    override def authorize(session: Session, operation: Operation, resource: Resource): Boolean = {
      session.principal != UnauthorizedPrincipal
    }
  }
  class TestPrincipalBuilder extends DefaultPrincipalBuilder {
    override def buildPrincipal(transportLayer: TransportLayer,  authenticator: Authenticator) = {
      principal
    }
  }
}
