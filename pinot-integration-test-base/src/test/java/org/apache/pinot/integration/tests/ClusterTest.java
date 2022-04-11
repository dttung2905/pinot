/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.integration.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.apache.pinot.broker.broker.helix.HelixBrokerStarter;
import org.apache.pinot.common.exception.HttpErrorStatusException;
import org.apache.pinot.common.utils.FileUploadDownloadClient;
import org.apache.pinot.common.utils.http.HttpClient;
import org.apache.pinot.controller.helix.ControllerTest;
import org.apache.pinot.minion.MinionStarter;
import org.apache.pinot.plugin.inputformat.avro.AvroRecordExtractor;
import org.apache.pinot.plugin.inputformat.avro.AvroUtils;
import org.apache.pinot.server.starter.helix.DefaultHelixStarterServerConfig;
import org.apache.pinot.server.starter.helix.HelixServerStarter;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.data.readers.RecordExtractor;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.stream.StreamMessageDecoder;
import org.apache.pinot.spi.utils.CommonConstants.Broker;
import org.apache.pinot.spi.utils.CommonConstants.Helix;
import org.apache.pinot.spi.utils.CommonConstants.Minion;
import org.apache.pinot.spi.utils.CommonConstants.Server;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.pinot.spi.utils.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;


/**
 * Base class for integration tests that involve a complete Pinot cluster.
 */
public abstract class ClusterTest extends ControllerTest {
  protected static final int DEFAULT_BROKER_PORT = 18099;
  protected static final Random RANDOM = new Random(System.currentTimeMillis());

  protected String _brokerBaseApiUrl;

  private List<HelixBrokerStarter> _brokerStarters;
  private List<HelixServerStarter> _serverStarters;
  private MinionStarter _minionStarter;
  private List<Integer> _brokerPorts;

  protected PinotConfiguration getDefaultBrokerConfiguration() {
    return new PinotConfiguration();
  }

  protected void overrideBrokerConf(PinotConfiguration brokerConf) {
    // Do nothing, to be overridden by tests if they need something specific
  }

  protected void startBroker()
      throws Exception {
    startBrokers(1);
  }

  protected void startBrokers(int numBrokers)
      throws Exception {
    _brokerStarters = new ArrayList<>(numBrokers);
    _brokerPorts = new ArrayList<>();
    for (int i = 0; i < numBrokers; i++) {
      HelixBrokerStarter brokerStarter = startOneBroker(i);
      _brokerStarters.add(brokerStarter);
      _brokerPorts.add(brokerStarter.getPort());
    }
    _brokerBaseApiUrl = "http://localhost:" + _brokerPorts.get(0);
  }

  protected HelixBrokerStarter startOneBroker(int brokerId)
      throws Exception {
    PinotConfiguration brokerConf = getDefaultBrokerConfiguration();
    brokerConf.setProperty(Helix.CONFIG_OF_CLUSTER_NAME, getHelixClusterName());
    brokerConf.setProperty(Helix.CONFIG_OF_ZOOKEEPR_SERVER, getZkUrl());
    brokerConf.setProperty(Broker.CONFIG_OF_BROKER_TIMEOUT_MS, 60 * 1000L);
    brokerConf.setProperty(Helix.KEY_OF_BROKER_QUERY_PORT, NetUtils.findOpenPort(DEFAULT_BROKER_PORT + brokerId));
    brokerConf.setProperty(Broker.CONFIG_OF_DELAY_SHUTDOWN_TIME_MS, 0);
    overrideBrokerConf(brokerConf);

    HelixBrokerStarter brokerStarter = new HelixBrokerStarter();
    brokerStarter.init(brokerConf);
    brokerStarter.start();
    return brokerStarter;
  }

  protected void startBrokerHttps()
      throws Exception {
    _brokerStarters = new ArrayList<>();
    _brokerPorts = new ArrayList<>();

    PinotConfiguration brokerConf = getDefaultBrokerConfiguration();
    brokerConf.setProperty(Helix.CONFIG_OF_CLUSTER_NAME, getHelixClusterName());
    brokerConf.setProperty(Helix.CONFIG_OF_ZOOKEEPR_SERVER, getZkUrl());
    brokerConf.setProperty(Broker.CONFIG_OF_BROKER_TIMEOUT_MS, 60 * 1000L);
    brokerConf.setProperty(Broker.CONFIG_OF_BROKER_HOSTNAME, LOCAL_HOST);
    brokerConf.setProperty(Broker.CONFIG_OF_DELAY_SHUTDOWN_TIME_MS, 0);
    overrideBrokerConf(brokerConf);

    HelixBrokerStarter brokerStarter = new HelixBrokerStarter();
    brokerStarter.init(brokerConf);
    brokerStarter.start();
    _brokerStarters.add(brokerStarter);

    // TLS configs require hard-coding
    _brokerPorts.add(DEFAULT_BROKER_PORT);
    _brokerBaseApiUrl = "https://localhost:" + DEFAULT_BROKER_PORT;
  }

  protected int getRandomBrokerPort() {
    return _brokerPorts.get(RANDOM.nextInt(_brokerPorts.size()));
  }

  protected int getBrokerPort(int index) {
    return _brokerPorts.get(index);
  }

  protected List<Integer> getBrokerPorts() {
    return ImmutableList.copyOf(_brokerPorts);
  }

  protected PinotConfiguration getDefaultServerConfiguration() {
    PinotConfiguration configuration = DefaultHelixStarterServerConfig.loadDefaultServerConf();

    configuration.setProperty(Helix.KEY_OF_SERVER_NETTY_HOST, LOCAL_HOST);
    configuration.setProperty(Server.CONFIG_OF_SEGMENT_FORMAT_VERSION, "v3");
    configuration.setProperty(Server.CONFIG_OF_SHUTDOWN_ENABLE_QUERY_CHECK, false);

    return configuration;
  }

  protected void overrideServerConf(PinotConfiguration serverConf) {
    // Do nothing, to be overridden by tests if they need something specific
  }

  protected void startServer()
      throws Exception {
    startServers(1);
  }

  protected void startServers(int numServers)
      throws Exception {
    FileUtils.deleteQuietly(new File(Server.DEFAULT_INSTANCE_BASE_DIR));
    _serverStarters = new ArrayList<>(numServers);
    for (int i = 0; i < numServers; i++) {
      _serverStarters.add(startOneServer(i));
    }
  }

  protected HelixServerStarter startOneServer(int serverId)
      throws Exception {
    PinotConfiguration serverConf = getDefaultServerConfiguration();
    serverConf.setProperty(Helix.CONFIG_OF_CLUSTER_NAME, getHelixClusterName());
    serverConf.setProperty(Helix.CONFIG_OF_ZOOKEEPR_SERVER, getZkUrl());
    serverConf.setProperty(Server.CONFIG_OF_INSTANCE_DATA_DIR, Server.DEFAULT_INSTANCE_DATA_DIR + "-" + serverId);
    serverConf.setProperty(Server.CONFIG_OF_INSTANCE_SEGMENT_TAR_DIR,
        Server.DEFAULT_INSTANCE_SEGMENT_TAR_DIR + "-" + serverId);
    serverConf.setProperty(Server.CONFIG_OF_ADMIN_API_PORT, Server.DEFAULT_ADMIN_API_PORT - serverId);
    serverConf.setProperty(Server.CONFIG_OF_NETTY_PORT, Helix.DEFAULT_SERVER_NETTY_PORT + serverId);
    serverConf.setProperty(Server.CONFIG_OF_GRPC_PORT, Server.DEFAULT_GRPC_PORT + serverId);
    // Thread time measurement is disabled by default, enable it in integration tests.
    // TODO: this can be removed when we eventually enable thread time measurement by default.
    serverConf.setProperty(Server.CONFIG_OF_ENABLE_THREAD_CPU_TIME_MEASUREMENT, true);
    overrideServerConf(serverConf);

    HelixServerStarter serverStarter = new HelixServerStarter();
    serverStarter.init(serverConf);
    serverStarter.start();
    return serverStarter;
  }

  protected void startServerHttps()
      throws Exception {
    FileUtils.deleteQuietly(new File(Server.DEFAULT_INSTANCE_BASE_DIR));
    _serverStarters = new ArrayList<>();

    PinotConfiguration serverConf = getDefaultServerConfiguration();
    serverConf.setProperty(Helix.CONFIG_OF_CLUSTER_NAME, getHelixClusterName());
    serverConf.setProperty(Helix.CONFIG_OF_ZOOKEEPR_SERVER, getZkUrl());
    overrideServerConf(serverConf);

    HelixServerStarter serverStarter = new HelixServerStarter();
    serverStarter.init(serverConf);
    serverStarter.start();
    _serverStarters.add(serverStarter);
  }

  protected List<HelixServerStarter> getServerStarters() {
    return _serverStarters;
  }

  protected PinotConfiguration getDefaultMinionConfiguration() {
    return new PinotConfiguration();
  }

  // NOTE: We don't allow multiple Minion instances in the same JVM because Minion uses singleton class MinionContext
  //       to manage the instance level configs
  protected void startMinion()
      throws Exception {
    FileUtils.deleteQuietly(new File(Minion.DEFAULT_INSTANCE_BASE_DIR));
    PinotConfiguration minionConf = getDefaultMinionConfiguration();
    minionConf.setProperty(Helix.CONFIG_OF_CLUSTER_NAME, getHelixClusterName());
    minionConf.setProperty(Helix.CONFIG_OF_ZOOKEEPR_SERVER, getZkUrl());
    _minionStarter = new MinionStarter();
    _minionStarter.init(minionConf);
    _minionStarter.start();
  }

  protected void stopBroker() {
    assertNotNull(_brokerStarters, "Brokers are not started");
    for (HelixBrokerStarter brokerStarter : _brokerStarters) {
      brokerStarter.stop();
    }
    _brokerStarters = null;
  }

  protected void stopServer() {
    assertNotNull(_serverStarters, "Servers are not started");
    for (HelixServerStarter serverStarter : _serverStarters) {
      serverStarter.stop();
    }
    FileUtils.deleteQuietly(new File(Server.DEFAULT_INSTANCE_BASE_DIR));
    _serverStarters = null;
  }

  protected void stopMinion() {
    assertNotNull(_minionStarter, "Minion is not started");
    _minionStarter.stop();
    FileUtils.deleteQuietly(new File(Minion.DEFAULT_INSTANCE_BASE_DIR));
    _minionStarter = null;
  }

  protected void restartServers()
      throws Exception {
    assertNotNull(_serverStarters, "Servers are not started");
    for (HelixServerStarter serverStarter : _serverStarters) {
      serverStarter.stop();
    }
    int numServers = _serverStarters.size();
    _serverStarters.clear();
    for (int i = 0; i < numServers; i++) {
      _serverStarters.add(startOneServer(i));
    }
  }

  /**
   * Upload all segments inside the given directory to the cluster.
   *
   * @param tarDir Segment directory
   */
  protected void uploadSegments(String tableName, File tarDir)
      throws Exception {
    File[] segmentTarFiles = tarDir.listFiles();
    assertNotNull(segmentTarFiles);
    int numSegments = segmentTarFiles.length;
    assertTrue(numSegments > 0);

    URI uploadSegmentHttpURI = FileUploadDownloadClient.getUploadSegmentHttpURI(LOCAL_HOST, _controllerPort);
    try (FileUploadDownloadClient fileUploadDownloadClient = new FileUploadDownloadClient()) {
      if (numSegments == 1) {
        File segmentTarFile = segmentTarFiles[0];
        if (System.currentTimeMillis() % 2 == 0) {
          assertEquals(
              fileUploadDownloadClient.uploadSegment(uploadSegmentHttpURI, segmentTarFile.getName(), segmentTarFile,
                  tableName).getStatusCode(), HttpStatus.SC_OK);
        } else {
          assertEquals(
              uploadSegmentWithOnlyMetadata(tableName, uploadSegmentHttpURI, fileUploadDownloadClient, segmentTarFile),
              HttpStatus.SC_OK);
        }
      } else {
        // Upload all segments in parallel
        ExecutorService executorService = Executors.newFixedThreadPool(numSegments);
        List<Future<Integer>> futures = new ArrayList<>(numSegments);
        for (File segmentTarFile : segmentTarFiles) {
          futures.add(executorService.submit(() -> {
            if (System.currentTimeMillis() % 2 == 0) {
              return fileUploadDownloadClient.uploadSegment(uploadSegmentHttpURI, segmentTarFile.getName(),
                  segmentTarFile, tableName).getStatusCode();
            } else {
              return uploadSegmentWithOnlyMetadata(tableName, uploadSegmentHttpURI, fileUploadDownloadClient,
                  segmentTarFile);
            }
          }));
        }
        executorService.shutdown();
        for (Future<Integer> future : futures) {
          assertEquals((int) future.get(), HttpStatus.SC_OK);
        }
      }
    }
  }

  /**
   * tarDirPaths contains a list of directories that contain segment files. API uploads all segments inside the given
   * list of directories to the cluster.
   *
   * @param tarDirPaths List of directories containing segments
   */
  protected void uploadSegments(String tableName, List<File> tarDirPaths, TableType tableType,
      boolean enableParallelPushProtection)
      throws Exception {
    List<File> segmentTarFiles = new ArrayList<>();

    for (File tarDir : tarDirPaths) {
      Collections.addAll(segmentTarFiles, tarDir.listFiles());
    }
    assertNotNull(segmentTarFiles);
    int numSegments = segmentTarFiles.size();
    assertTrue(numSegments > 0);

    URI uploadSegmentHttpURI = FileUploadDownloadClient.getUploadSegmentHttpURI(LOCAL_HOST, _controllerPort);
    try (FileUploadDownloadClient fileUploadDownloadClient = new FileUploadDownloadClient()) {
      if (numSegments == 1) {
        File segmentTarFile = segmentTarFiles.get(0);
        assertEquals(
            fileUploadDownloadClient.uploadSegment(uploadSegmentHttpURI, segmentTarFile.getName(), segmentTarFile,
                tableName, tableType.OFFLINE, enableParallelPushProtection, true).getStatusCode(), HttpStatus.SC_OK);
      } else {
        // Upload all segments in parallel
        ExecutorService executorService = Executors.newFixedThreadPool(numSegments);
        List<Future<Integer>> futures = new ArrayList<>(numSegments);
        for (File segmentTarFile : segmentTarFiles) {
          futures.add(executorService.submit(() -> {
            return fileUploadDownloadClient.uploadSegment(uploadSegmentHttpURI, segmentTarFile.getName(),
                segmentTarFile, tableName, tableType.OFFLINE, enableParallelPushProtection, true).getStatusCode();
          }));
        }
        executorService.shutdown();
        for (Future<Integer> future : futures) {
          assertEquals((int) future.get(), HttpStatus.SC_OK);
        }
      }
    }
  }

  private int uploadSegmentWithOnlyMetadata(String tableName, URI uploadSegmentHttpURI,
      FileUploadDownloadClient fileUploadDownloadClient, File segmentTarFile)
      throws IOException, HttpErrorStatusException {
    List<Header> headers = ImmutableList.of(new BasicHeader(FileUploadDownloadClient.CustomHeaders.DOWNLOAD_URI,
        "file://" + segmentTarFile.getParentFile().getAbsolutePath() + "/" + URLEncoder.encode(segmentTarFile.getName(),
            StandardCharsets.UTF_8.toString())), new BasicHeader(FileUploadDownloadClient.CustomHeaders.UPLOAD_TYPE,
        FileUploadDownloadClient.FileUploadType.METADATA.toString()));
    // Add table name as a request parameter
    NameValuePair tableNameValuePair =
        new BasicNameValuePair(FileUploadDownloadClient.QueryParameters.TABLE_NAME, tableName);
    List<NameValuePair> parameters = Arrays.asList(tableNameValuePair);
    return fileUploadDownloadClient.uploadSegmentMetadata(uploadSegmentHttpURI, segmentTarFile.getName(),
        segmentTarFile, headers, parameters, HttpClient.DEFAULT_SOCKET_TIMEOUT_MS).getStatusCode();
  }

  public static class AvroFileSchemaKafkaAvroMessageDecoder implements StreamMessageDecoder<byte[]> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AvroFileSchemaKafkaAvroMessageDecoder.class);
    public static File _avroFile;
    private org.apache.avro.Schema _avroSchema;
    private RecordExtractor _recordExtractor;
    private DecoderFactory _decoderFactory = new DecoderFactory();
    private DatumReader<GenericData.Record> _reader;

    @Override
    public void init(Map<String, String> props, Set<String> fieldsToRead, String topicName)
        throws Exception {
      // Load Avro schema
      try (DataFileStream<GenericRecord> reader = AvroUtils.getAvroReader(_avroFile)) {
        _avroSchema = reader.getSchema();
      }
      _recordExtractor = new AvroRecordExtractor();
      _recordExtractor.init(fieldsToRead, null);
      _reader = new GenericDatumReader<>(_avroSchema);
    }

    @Override
    public GenericRow decode(byte[] payload, GenericRow destination) {
      return decode(payload, 0, payload.length, destination);
    }

    @Override
    public GenericRow decode(byte[] payload, int offset, int length, GenericRow destination) {
      try {
        GenericData.Record avroRecord =
            _reader.read(null, _decoderFactory.binaryDecoder(payload, offset, length, null));
        return _recordExtractor.extract(avroRecord, destination);
      } catch (Exception e) {
        LOGGER.error("Caught exception", e);
        throw new RuntimeException(e);
      }
    }
  }

  protected JsonNode getDebugInfo(final String uri)
      throws Exception {
    return JsonUtils.stringToJsonNode(sendGetRequest(_brokerBaseApiUrl + "/" + uri));
  }

  /**
   * Queries the broker's sql query endpoint (/query/sql)
   */
  protected JsonNode postQuery(String query)
      throws Exception {
    return postQuery(query, _brokerBaseApiUrl);
  }

  /**
   * Queries the broker's sql query endpoint (/sql)
   */
  public static JsonNode postQuery(String query, String brokerBaseApiUrl)
      throws Exception {
    return postQuery(query, brokerBaseApiUrl, null);
  }

  /**
   * Queries the broker's sql query endpoint (/sql)
   */
  public static JsonNode postQuery(String query, String brokerBaseApiUrl, Map<String, String> headers)
      throws Exception {
    ObjectNode payload = JsonUtils.newObjectNode();
    payload.put("sql", query);
    payload.put("queryOptions", "groupByMode=sql;responseFormat=sql");

    return JsonUtils.stringToJsonNode(sendPostRequest(brokerBaseApiUrl + "/query/sql", payload.toString(), headers));
  }
}
