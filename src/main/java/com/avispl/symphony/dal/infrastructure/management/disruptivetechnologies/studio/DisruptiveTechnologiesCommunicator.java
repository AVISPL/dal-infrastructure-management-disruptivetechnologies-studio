	/*
	 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
	 */

	package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio;

	import java.io.IOException;
	import java.net.ConnectException;
	import java.net.Socket;
	import java.net.SocketTimeoutException;
	import java.net.URLEncoder;
	import java.net.UnknownHostException;
	import java.time.OffsetDateTime;
	import java.time.ZoneOffset;
	import java.time.format.DateTimeFormatter;
	import java.util.ArrayList;
	import java.util.Collections;
	import java.util.Date;
	import java.util.HashMap;
	import java.util.HashSet;
	import java.util.List;
	import java.util.Map;
	import java.util.Objects;
	import java.util.Properties;
	import java.util.Set;
	import java.util.concurrent.ExecutorService;
	import java.util.concurrent.Executors;
	import java.util.concurrent.TimeUnit;
	import java.util.concurrent.locks.ReentrantLock;
	import java.util.stream.Collectors;

	import org.springframework.http.HttpHeaders;
	import org.springframework.http.HttpMethod;
	import org.springframework.util.CollectionUtils;

	import com.auth0.jwt.JWT;
	import com.auth0.jwt.algorithms.Algorithm;
	import com.fasterxml.jackson.databind.JsonNode;
	import com.fasterxml.jackson.databind.ObjectMapper;
	import javax.security.auth.login.FailedLoginException;

	import com.avispl.symphony.api.dal.control.Controller;
	import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
	import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
	import com.avispl.symphony.api.dal.dto.monitor.Statistics;
	import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
	import com.avispl.symphony.api.dal.error.CommandFailureException;
	import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
	import com.avispl.symphony.api.dal.monitor.Monitorable;
	import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
	import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
	import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
	import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
	import com.avispl.symphony.dal.communicator.RestCommunicator;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.AggregatorInformation;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.DisruptiveTechnologiesCommand;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.DisruptiveTechnologiesConstant;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.LoginInfo;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.PingMode;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.metric.CloudConnector;
	import com.avispl.symphony.dal.util.StringUtils;


	/**
	 * DisruptiveTechnologiesCommunicator
	 * Supported features are:
	 * Monitoring Aggregator Device:
	 *  <ul>
	 *    <li> Generic </li>
	 *    <li> Cloud Connector </li>
	 *  <ul>
	 *
	 * Subscription Group:
	 * <ul>
	 * <li> - CreationDate</li>
	 * <li> - ExpireDate</li>
	 * <li> - LastModifiedDate</li>
	 * <li> - Level</li>
	 * </ul>
	 *
	 * General Info Aggregated Device:
	 * <ul>
	 *   Monitoring with sensors:
	 *   <li> CO2 </li>
	 *   <li> Contact </li>
	 *   <li> Counting Proximity </li>
	 *   <li> Counting Touch </li>
	 *   <li> Desk Occupancy </li>
	 *   <li> Humidity </li>
	 *   <li> Motion </li>
	 *   <li> Proximity </li>
	 *   <li> Temperature </li>
	 *   <li> Touch </li>
	 *   <li> Water Detector </li>
	 * </ul>
	 *
	 * @author Harry / Symphony Dev Team<br>
	 * Created on 23/10/2024
	 * @since 1.0.0
	 */
	public class DisruptiveTechnologiesCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {
		/**
		 * Process that is running constantly and triggers collecting data from Disruptive Technologies API endpoints, based on the given timeouts and thresholds.
		 *
		 * @author Harry
		 * @since 1.0.0
		 */

		/**
		 * How much time last monitoring cycle took to finish
		 */
		private Long lastMonitoringCycleDuration;

		/** Adapter metadata properties - adapter version and build date */
		private Properties adapterProperties;

		/**
		 * Device adapter instantiation timestamp.
		 */
		private long adapterInitializationTimestamp;

		/**
		 * Indicates whether a device is considered as paused.
		 * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
		 * collection unless the {@link DisruptiveTechnologiesCommunicator#retrieveMultipleStatistics()} method is called which will change it
		 * to a correct value
		 */
		private volatile boolean devicePaused = true;

		/**
		 * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
		 * new devices' statistics loop will be launched before the next monitoring iteration. To avoid that -
		 * this variable stores a timestamp which validates it, so when the devices' statistics is done collecting, variable
		 * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
		 */
		private long nextDevicesCollectionIterationTimestamp;

		/**
		 * This parameter holds timestamp of when we need to stop performing API calls
		 * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
		 */
		private volatile long validRetrieveStatisticsTimestamp;

		/**
		 * Aggregator inactivity timeout. If the {@link DisruptiveTechnologiesCommunicator#retrieveMultipleStatistics()}  method is not
		 * called during this period of time - device is considered to be paused, thus the Cloud API
		 * is not supposed to be called
		 */
		private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

		/**
		 * Update the status of the device.
		 * The device is considered as paused if did not receive any retrieveMultipleStatistics()
		 * calls during {@link DisruptiveTechnologiesCommunicator}
		 */
		private synchronized void updateAggregatorStatus() {
			devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
		}

		/**
		 * Uptime time stamp to valid one
		 */
		private synchronized void updateValidRetrieveStatisticsTimestamp() {
			validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
			updateAggregatorStatus();
		}

		/**
		 * Executor that runs all the async operations, that is posting and
		 */
		private ExecutorService executorService;

		/**
		 * the login info
		 */
		private LoginInfo loginInfo;

		/**
		 * A private field that represents an instance of the DisruptiveTechnologiesCloudLoader class, which is responsible for loading device data for DisruptiveTechnologiesCloud
		 */
		private DisruptiveTechnologiesCloudDataLoader deviceDataLoader;

		/**
		 * A private final ReentrantLock instance used to provide exclusive access to a shared resource
		 * that can be accessed by multiple threads concurrently. This lock allows multiple reentrant
		 * locks on the same shared resource by the same thread.
		 */
		private final ReentrantLock reentrantLock = new ReentrantLock();

		/**
		 * Id of project
		 */
		private String projectID;

		/**
		 * Private variable representing the local extended statistics.
		 */
		private ExtendedStatistics localExtendedStatistics;

		/**
		 * A cache that maps route names to their corresponding values.
		 */
		private final Map<String, String> cacheValue = new HashMap<>();

		/**
		 * A set containing cloud info.
		 */
		private Set<String> allCloudSet = new HashSet<>();

		/**
		 * A set containing sensor info.
		 */
		private Set<String> allSensorNameSet = new HashSet<>();

		/**
		 * List of aggregated device
		 */
		private List<AggregatedDevice> aggregatedDeviceList = Collections.synchronizedList(new ArrayList<>());

		/**
		 * An instance of the AggregatedDeviceProcessor class used to process and aggregate device-related data.
		 */
		private AggregatedDeviceProcessor aggregatedDeviceProcessor;


		/**
		 * A mapper for reading and writing JSON using Jackson library.
		 * ObjectMapper provides functionality for converting between Java objects and JSON.
		 * It can be used to serialize objects to JSON format, and deserialize JSON data to objects.
		 */
		ObjectMapper objectMapper = new ObjectMapper();

		/**
		 * cache data for aggregated
		 */
		private List<AggregatedDevice> cachedData = Collections.synchronizedList(new ArrayList<>());

		/**
		 * A JSON node containing the response from an aggregator.
		 */
		private JsonNode aggregatedResponse;

		class DisruptiveTechnologiesCloudDataLoader implements Runnable {
			private volatile boolean inProgress;
			private volatile boolean flag = false;

			public DisruptiveTechnologiesCloudDataLoader() {
				inProgress = true;
			}

			@Override
			public void run() {
				loop:
				while (inProgress) {
					long startCycle = System.currentTimeMillis();
					try {
						try {
							TimeUnit.MILLISECONDS.sleep(500);
						} catch (InterruptedException e) {
							logger.info(String.format("Sleep for 0.5 second was interrupted with error message: %s", e.getMessage()));
						}

						if (!inProgress) {
							break loop;
						}

						// next line will determine whether DT Studio monitoring was paused
						updateAggregatorStatus();
						if (devicePaused) {
							continue loop;
						}
						if (logger.isDebugEnabled()) {
							logger.debug("Fetching other than aggregated device list");
						}

						long currentTimestamp = System.currentTimeMillis();
						if (!flag && nextDevicesCollectionIterationTimestamp <= currentTimestamp) {
							populateDeviceDetails();
							flag = true;
						}

						while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
							try {
								TimeUnit.MILLISECONDS.sleep(1000);
							} catch (InterruptedException e) {
								logger.info(String.format("Sleep for 1 second was interrupted with error message: %s", e.getMessage()));
							}
						}

						if (!inProgress) {
							break loop;
						}
						if (flag) {
							nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;
							lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle) / 1000;
							logger.debug("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);
							flag = false;
						}

						if (logger.isDebugEnabled()) {
							logger.debug("Finished collecting devices statistics cycle at " + new Date());
						}
					} catch (Exception e) {
						logger.error("Unexpected error occurred during main device collection cycle", e);
					}
				}
				logger.debug("Main device collection loop is completed, in progress marker: " + inProgress);
				// Finished collecting
			}

			/**
			 * Triggers main loop to stop
			 */
			public void stop() {
				inProgress = false;
			}
		}


		/**
		 * tenantId configuration specify selected tenant to fetch all devices
		 */
		private String projectId = DisruptiveTechnologiesConstant.EMPTY;

		/**
		 * Retrieves {@link #projectId}
		 *
		 * @return value of {@link #projectId}
		 */
		public String getProjectId() {
			return projectId;
		}

		/**
		 * Sets {@link #projectId} value
		 *
		 * @param projectId new value of {@link #projectId}
		 */
		public void setProjectId(String projectId) {
			this.projectId = projectId;
		}

		/**
		 * ping mode
		 */
		private PingMode pingMode = PingMode.ICMP;

		/**
		 * Retrieves {@link #pingMode}
		 *
		 * @return value of {@link #pingMode}
		 */
		public String getPingMode() {
			return pingMode.name();
		}

		/**
		 * Sets {@link #pingMode} value
		 *
		 * @param pingMode new value of {@link #pingMode}
		 */
		public void setPingMode(String pingMode) {
			this.pingMode = PingMode.ofString(pingMode);
		}

		/**
		 * Constructs a new instance of DisruptiveTechnologiesCommunicator.
		 *
		 * @throws IOException If an I/O error occurs while loading the properties mapping YAML file.
		 */
		public DisruptiveTechnologiesCommunicator() throws IOException {
			Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML(DisruptiveTechnologiesConstant.MODEL_MAPPING_AGGREGATED_DEVICE, getClass());
			aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
			adapterProperties = new Properties();
			adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
			this.setTrustAllCertificates(true);
		}

		/**
		 * {@inheritDoc}
		 * <p>
		 *
		 * Check for available devices before retrieving the value
		 * ping latency information to Symphony
		 */
		@Override
		public int ping() throws Exception {
			if (this.pingMode == PingMode.ICMP) {
				return super.ping();
			} else if (this.pingMode == PingMode.TCP) {
				if (isInitialized()) {
					long pingResultTotal = 0L;

					for (int i = 0; i < this.getPingAttempts(); i++) {
						long startTime = System.currentTimeMillis();

						try (Socket puSocketConnection = new Socket(this.host, this.getPort())) {
							puSocketConnection.setSoTimeout(this.getPingTimeout());
							if (puSocketConnection.isConnected()) {
								long pingResult = System.currentTimeMillis() - startTime;
								pingResultTotal += pingResult;
								if (this.logger.isTraceEnabled()) {
									this.logger.trace(String.format("PING OK: Attempt #%s to connect to %s on port %s succeeded in %s ms", i + 1, host, this.getPort(), pingResult));
								}
							} else {
								if (this.logger.isDebugEnabled()) {
									this.logger.debug(String.format("PING DISCONNECTED: Connection to %s did not succeed within the timeout period of %sms", host, this.getPingTimeout()));
								}
								return this.getPingTimeout();
							}
						} catch (SocketTimeoutException | ConnectException tex) {
							throw new RuntimeException("Socket connection timed out", tex);
						} catch (UnknownHostException ex) {
							throw new UnknownHostException(String.format("Connection timed out, UNKNOWN host %s", host));
						} catch (Exception e) {
							if (this.logger.isWarnEnabled()) {
								this.logger.warn(String.format("PING TIMEOUT: Connection to %s did not succeed, UNKNOWN ERROR %s: ", host, e.getMessage()));
							}
							return this.getPingTimeout();
						}
					}
					return Math.max(1, Math.toIntExact(pingResultTotal / this.getPingAttempts()));
				} else {
					throw new IllegalStateException("Cannot use device class without calling init() first");
				}
			} else {
				throw new IllegalArgumentException("Unknown PING Mode: " + pingMode);
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void controlProperty(ControllableProperty controllableProperty) throws Exception {
			reentrantLock.lock();
			reentrantLock.unlock();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void controlProperties(List<ControllableProperty> controllableProperties) throws Exception {
			if (CollectionUtils.isEmpty(controllableProperties)) {
				throw new IllegalArgumentException("ControllableProperties can not be null or empty");
			}
			for (ControllableProperty p : controllableProperties) {
				try {
					controlProperty(p);
				} catch (Exception e) {
					logger.error(String.format("Error when control property %s", p.getProperty()), e);
				}
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
			return retrieveMultipleStatistics()
					.stream()
					.filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
					.collect(Collectors.toList());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
			if (executorService == null) {
				executorService = Executors.newFixedThreadPool(1);
				executorService.submit(deviceDataLoader = new DisruptiveTechnologiesCloudDataLoader());
			}
			nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
			updateValidRetrieveStatisticsTimestamp();
			if (cachedData.isEmpty()) {
				return Collections.emptyList();
			}
			return cloneAndPopulateAggregatedDeviceList();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public List<Statistics> getMultipleStatistics() throws Exception {
			reentrantLock.lock();
			try {
				if (loginInfo == null) {
					loginInfo = new LoginInfo();
				}
				checkValidApiToken();
				Map<String, String> stats = new HashMap<>();
				Map<String, String> dynamicStatistics = new HashMap<>();
				ExtendedStatistics extendedStatistics = new ExtendedStatistics();

				retrieveProjectInfo(stats);
				retrieveSensorsInfo(stats);

				dynamicStatistics.put(DisruptiveTechnologiesConstant.MONITORED_DEVICES_TOTAL, String.valueOf(aggregatedResponse.get(DisruptiveTechnologiesConstant.DEVICES).size()));
				if (lastMonitoringCycleDuration != null) {
					stats.put(DisruptiveTechnologiesConstant.MONITORING_CYCLE_DURATION, String.valueOf(lastMonitoringCycleDuration));
				}
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_VERSION, getDefaultValueForNullData(adapterProperties.getProperty("aggregator.version")));
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_BUILD_DATE, getDefaultValueForNullData(adapterProperties.getProperty("aggregator.build.date")));

//				dynamicStatistics.put(DisruptiveTechnologiesConstant.ADAPTER_RUNNER_SIZE, String.valueOf(ClassLayout.parseInstance(this).toPrintable().length()));
				long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000 * 60)));
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_UPTIME, normalizeUptime(adapterUptime / 1000));
				extendedStatistics.setStatistics(stats);
				extendedStatistics.setDynamicStatistics(dynamicStatistics);
				localExtendedStatistics = extendedStatistics;
			} finally {
				reentrantLock.unlock();
			}
			return Collections.singletonList(localExtendedStatistics);
		}

		/**
		 * {@inheritDoc}
		 * set API Key into Header of Request
		 */
		@Override
		protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) {
			headers.set("Accept", "application/json");
			headers.set("Content-Type", "application/x-www-form-urlencoded");
			if (loginInfo.getToken() != null && !uri.contains(DisruptiveTechnologiesCommand.GET_TOKEN)) {
				headers.setBearerAuth(loginInfo.getToken());
			}
			return headers;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void authenticate() throws Exception {
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void internalInit() throws Exception {
			if (logger.isDebugEnabled()) {
				logger.debug("Internal init is called.");
			}
			adapterInitializationTimestamp = System.currentTimeMillis();
			executorService = Executors.newFixedThreadPool(1);
			executorService.submit(deviceDataLoader = new DisruptiveTechnologiesCloudDataLoader());
			super.internalInit();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void internalDestroy() {
			if (logger.isDebugEnabled()) {
				logger.debug("Internal destroy is called.");
			}
			if (deviceDataLoader != null) {
				deviceDataLoader.stop();
				deviceDataLoader = null;
			}
			if (executorService != null) {
				executorService.shutdownNow();
				executorService = null;
			}
			if (localExtendedStatistics != null && localExtendedStatistics.getStatistics() != null && localExtendedStatistics.getControllableProperties() != null) {
				localExtendedStatistics.getStatistics().clear();
				localExtendedStatistics.getControllableProperties().clear();
			}
			cacheValue.clear();
			loginInfo = null;
			nextDevicesCollectionIterationTimestamp = 0;
			aggregatedDeviceList.clear();
			cachedData.clear();
			super.internalDestroy();
		}

		/**
		 * Retrieves project information and updates the provided statistics map.
		 *
		 * @param stats the map where statistics will be stored
		 * @throws Exception if there is an error during the retrieval process
		 */
		private void retrieveProjectInfo(Map<String, String> stats) throws Exception {
			try {
				JsonNode response = this.doGet(String.format(DisruptiveTechnologiesCommand.GET_SINGLE_PROJECT, this.getProjectId()), JsonNode.class);
				if (response != null) {
					String GroupName = DisruptiveTechnologiesConstant.GENERIC + DisruptiveTechnologiesConstant.HASH;

					for (AggregatorInformation property : AggregatorInformation.values()) {
						String group = property.getGroup();
						switch (property) {
							case ORGANIZATION:
								String[] OrganizationId = response.get(property.getName()).asText().split("/");
								stats.put(GroupName + "OrganizationId", getDefaultValueForNullData(OrganizationId[1]));
								break;
							case ORGANIZATION_DISPLAY_NAME:
								stats.put(GroupName + "OrganizationName", getDefaultValueForNullData(response.get(property.getName()).asText()));
								break;
							case INVENTORY:
								stats.put(GroupName + uppercaseFirstCharacter(property.getName()), uppercaseFirstCharacter(response.get(property.getName()).asText()));
								break;
							case LOCATION_TIME:
								stats.put(GroupName + uppercaseFirstCharacter(property.getGroup()), convertDateTimeFormat(getDefaultValueForNullData(response.get("location").get(property.getName()).asText())));
								break;
							case LOCATION_LATITUDE:
							case LOCATION_LONGITUDE:
								stats.put(GroupName + uppercaseFirstCharacter(property.getGroup()), getDefaultValueForNullData(uppercaseFirstCharacter(response.get("location").get(property.getName()).asText())));
								break;
							case DISPLAY_NAME:
								stats.put(GroupName + "ProjectName", getDefaultValueForNullData(response.get(property.getName()).asText()));
								break;
							case SENSOR_COUNT:
								stats.put(GroupName + "MonitoredSensorCount", getDefaultValueForNullData(response.get(property.getName()).asText()));
								break;
							case CLOUD_CONNECTOR_COUNT:
								stats.put(GroupName + "MonitoredCloudConnectorCount", getDefaultValueForNullData(response.get(property.getName()).asText()));
								break;
							case PROJECT_ID:
								String[] id = response.get(property.getName()).asText().split("/");
								projectID = id[1];
								stats.put(GroupName + "ProjectId", getDefaultValueForNullData(id[1]));
								break;
							default:
								if (DisruptiveTechnologiesConstant.EMPTY.equals(group)) {
									stats.put(GroupName + uppercaseFirstCharacter(property.getName()), getDefaultValueForNullData(response.get(property.getName()).asText()));
								}
								break;
						}
					}
				}
			} catch (Exception e) {
				throw new ResourceNotReachableException("Unable to retrieve project information.", e);
			}
		}

		/**
		 * Retrieves sensor information for a specific project and updates the provided statistics map.
		 *
		 * @param stats the map where statistics will be stored
		 * @throws Exception if there is an error during the retrieval process
		 */
		private void retrieveSensorsInfo(Map<String, String> stats) throws Exception {
			reentrantLock.lock();
			try {
				if (StringUtils.isNullOrEmpty(projectID)) {
					return;
				}

				String urlTemplate = String.format(DisruptiveTechnologiesCommand.GET_SENSORS_BY_PROJECT, projectID);
				aggregatedResponse = this.doGet(urlTemplate, JsonNode.class);

				if (aggregatedResponse.size() <= 1) {
					return;
				}

				allCloudSet.clear();
				allSensorNameSet.clear();

				for (JsonNode item : aggregatedResponse.get(DisruptiveTechnologiesConstant.DEVICES)) {
					if (!isConnectorType(item)) {
						continue;
					}

					JsonNode labelsNode = item.get(DisruptiveTechnologiesConstant.LABELS);
					String group = labelsNode.get(DisruptiveTechnologiesConstant.NAME).asText();
					allCloudSet.add(group);

					for (CloudConnector cloudConnector : CloudConnector.values()) {
						processCloudConnector(item, stats, group, cloudConnector);
					}
				}
			} catch (CommandFailureException ex) {
				cachedData.clear();
				logger.error(ex.getResponse(), ex);
			} catch (Exception e) {
				throw new ResourceNotReachableException("Unable to retrieve sensors information.", e);
			} finally {
				reentrantLock.unlock();
			}
		}

		/**
		 * Checks if the specified item is of the type required for processing as a connector.
		 *
		 * @param item the JSON node representing the item to check
		 * @return true if the item type is "CCON", false otherwise
		 */
		private boolean isConnectorType(JsonNode item) {
			return item.get("type").asText().equals(DisruptiveTechnologiesConstant.CCON);
		}

		/**
		 * Processes cloud connector item and updates the statistics map with the appropriate values.
		 *
		 * @param item the JSON node representing the device item
		 * @param stats the map where statistics will be stored
		 * @param group the group name for the device
		 */
		private void processCloudConnector(JsonNode item, Map<String, String> stats, String group, CloudConnector cloudConnector) {
			String key = DisruptiveTechnologiesConstant.CLOUD_CONNECTOR + group + DisruptiveTechnologiesConstant.HASH + cloudConnector.getName();
			String value;

			switch (cloudConnector) {
				case ID:
					value = getIdValue(item, cloudConnector);
					break;
				case LABELS_NAME:
					value = item.path("labels").path(cloudConnector.getField()).asText();
					break;
				case CONNECTION_STATUS:
					value = getStatusValue(item, cloudConnector);
					break;
				case CONNECTION_AVAILABLE:
					value = getConnectionAvailableValue(item, cloudConnector);
					break;
				case CONNECTION_UPDATE_TIME:
					value = getConnectionUpdateTime(item, cloudConnector);
					break;
				default:
					value = item.path(cloudConnector.getField()).asText();
					break;
			}
			stats.put(key, getDefaultValueForNullData(value));
		}

		/**
		 * Retrieves the ID value for a cloud connector item.
		 *
		 * @param item the JSON node representing the device item
		 * @param cloudConnector the CloudConnector type
		 */
		private String getIdValue(JsonNode item, CloudConnector cloudConnector) {
			String[] name = item.get(cloudConnector.getField()).asText().split("/");
			return name.length > 3 ? name[3] : DisruptiveTechnologiesConstant.NONE;
		}

		/**
		 * Retrieves the connection status value for a cloud connector item.
		 *
		 * @param item the JSON node representing the device item
		 * @param cloudConnector the CloudConnector type
		 */
		private String getStatusValue(JsonNode item, CloudConnector cloudConnector) {
			JsonNode connectionStatusNode = item.path(DisruptiveTechnologiesConstant.REPORTED)
					.path(DisruptiveTechnologiesConstant.CONNECTION_STATUS)
					.path(cloudConnector.getField());
			return connectionStatusNode.isMissingNode() ? DisruptiveTechnologiesConstant.NONE : connectionStatusNode.asText();
		}

		/**
		 * Retrieves the connection availability value for a cloud connector item.
		 *
		 * @param item the JSON node representing the device item
		 * @param cloudConnector the CloudConnector type
		 */
		private String getConnectionAvailableValue(JsonNode item, CloudConnector cloudConnector) {
			JsonNode availableNode = item.path(DisruptiveTechnologiesConstant.REPORTED)
					.path(DisruptiveTechnologiesConstant.CONNECTION_STATUS)
					.path(cloudConnector.getField());
			String connectionAvailable = availableNode.toString();

			return availableNode.isMissingNode() || connectionAvailable.equals("[]")
					? DisruptiveTechnologiesConstant.NONE
					: connectionAvailable;
		}

		/**
		 * Retrieves and formats the update time for a cloud connector item.
		 *
		 * @param item the JSON node representing the device item
		 * @param cloudConnector the CloudConnector type
		 */
		private String getConnectionUpdateTime(JsonNode item, CloudConnector cloudConnector) {
			JsonNode updateTimeNode = item.path(DisruptiveTechnologiesConstant.REPORTED)
					.path(DisruptiveTechnologiesConstant.CONNECTION_STATUS)
					.path(cloudConnector.getField());
			return updateTimeNode.isMissingNode() ? DisruptiveTechnologiesConstant.NONE : convertDateTimeFormat(updateTimeNode.asText());
		}

		/**
		 * Populates detailed information for each device in the aggregated response.
		 * This method iterates over all devices in the response, filters out any device
		 * of type "CCON", and processes the remaining devices
		 */
		private void populateDeviceDetails() {
			try {
				if (aggregatedResponse != null && aggregatedResponse.has(DisruptiveTechnologiesConstant.DEVICES)) {
					for (JsonNode item : aggregatedResponse.get(DisruptiveTechnologiesConstant.DEVICES)) {
						if (item.get("type") != null && !item.get("type").asText().equals(DisruptiveTechnologiesConstant.CCON)) {
							JsonNode labelsNode = item.get(DisruptiveTechnologiesConstant.LABELS);

							if (labelsNode != null && labelsNode.has(DisruptiveTechnologiesConstant.NAME)) {
								String group = labelsNode.get(DisruptiveTechnologiesConstant.NAME).asText();
								allSensorNameSet.add(group);
								JsonNode node = objectMapper.createArrayNode().add(item);
								String[] id = item.get(DisruptiveTechnologiesConstant.NAME).asText().split("/");
								cachedData.removeIf(record -> record.getDeviceId().equals(id[3]));
								cachedData.addAll(aggregatedDeviceProcessor.extractDevices(node));
							}
						}
					}
				}

			} catch (Exception e) {
				logger.error("Error while populate aggregated device", e);
			}
		}

		/**
		 * Check API token validation
		 * If the token expires, we send a request to get a new token
		 */
		private void checkValidApiToken() throws Exception {
			if (StringUtils.isNullOrEmpty(this.getLogin()) || StringUtils.isNullOrEmpty(this.getPassword())) {
				throw new FailedLoginException("Username or Password field is empty. Please check device credentials");
			}

			if (this.loginInfo.isTimeout() || this.loginInfo.getToken() == null) {
				String email;
				String keyId;
				String secretId = this.getPassword();
				String[] loginField = this.getLogin().split(DisruptiveTechnologiesConstant.SPACE);
				if (loginField.length == 2) {
					email = loginField[0];
					keyId = loginField[1];
				} else {
					throw new FailedLoginException("The format of Username field is incorrect. Please check again");
				}
				retrieveToken(keyId, email, secretId);
			}
		}

		/**
		 * Generate JWT Token by KeyId, Email and SecretId
		 *
		 * @param keyId the key id for authentication
		 * @param email the email for authentication
		 * @param secret the secret id for the application
		 */
		private String generateJWT(String keyId, String email, String secret) {
			long now = System.currentTimeMillis();
			Map<String, Object> jwtHeaders = new HashMap<>();
			Date issuedAt = new Date(now);
			Date expiresAt = new Date(now + 3600 * 1000);
			jwtHeaders.put("alg", "HS256");
			jwtHeaders.put("kid", keyId);

			Algorithm algorithm = Algorithm.HMAC256(secret);

			return JWT.create()
					.withHeader(jwtHeaders)
					.withIssuedAt(issuedAt)
					.withExpiresAt(expiresAt)
					.withAudience(DisruptiveTechnologiesConstant.URI + DisruptiveTechnologiesCommand.GET_TOKEN)
					.withIssuer(email)
					.sign(algorithm);
		}


		/**
		 * Retrieves an authorization token using the provided credentials.
		 *
		 * @param keyId the key id for authentication
		 * @param email the email for authentication
		 * @param secret the secret id for the application
		 * @throws FailedLoginException if login fails due to incorrect credentials
		 * @throws ResourceNotReachableException if the endpoint is unreachable
		 * @throws Exception for other unforeseen errors
		 */
		private void retrieveToken(String keyId, String email, String secret) throws Exception {
			String jwtToken = generateJWT(keyId, email, secret);
			String body = String.format(DisruptiveTechnologiesConstant.REQUEST_BODY, URLEncoder.encode(jwtToken, "UTF-8"));
			try {
				JsonNode response = doPost(DisruptiveTechnologiesConstant.URI + DisruptiveTechnologiesConstant.COLON + getPort() + DisruptiveTechnologiesCommand.GET_TOKEN, body, JsonNode.class);
				if (response.size() == 1) {
					throw new IllegalArgumentException("ClientId and ClientSecret are not correct");
				}
				if (response.has(DisruptiveTechnologiesConstant.ACCESS_TOKEN)) {
					this.loginInfo.setToken(response.get(DisruptiveTechnologiesConstant.ACCESS_TOKEN).asText());
					this.loginInfo.setLoginDateTime(System.currentTimeMillis());
				} else {
					loginInfo = null;
					throw new ResourceNotReachableException("Unable to retrieve the authorization token, endpoint not reachable");
				}

			} catch (CommandFailureException e) {
				if (e.getStatusCode() == 400) {
					JsonNode response = objectMapper.readTree(e.getResponse());
					if (response.has("error") && "invalid_grant".equalsIgnoreCase(response.get("error").asText())) {
						throw new FailedLoginException("Unable to login. Please check device credentials");
					}
				}
				throw new ResourceNotReachableException("Unable to retrieve the authorization token, endpoint not reachable", e);
			} catch (Exception ex) {
				throw new ResourceNotReachableException("Unable to retrieve the authorization token, endpoint not reachable", ex);
			}
		}

		/**
		 * Clones and populates a new list of aggregated devices with mapped monitoring properties.
		 *
		 * @return A new list of {@link AggregatedDevice} objects with mapped monitoring properties.
		 */
		private List<AggregatedDevice> cloneAndPopulateAggregatedDeviceList() {
			aggregatedDeviceList.clear();
			synchronized (cachedData) {
				long currentTimestamp = System.currentTimeMillis();
				for (AggregatedDevice item : cachedData) {
					String updateTime = item.getProperties().get("NetworkStatus#Update");

					long updateTimeMillis = parseUpdateTime(updateTime);
					AggregatedDevice aggregatedDevice = new AggregatedDevice();
					aggregatedDevice.setDeviceId(item.getDeviceId());
					aggregatedDevice.setDeviceModel(item.getDeviceModel());
					aggregatedDevice.setDeviceName(item.getDeviceName());

					aggregatedDevice.setDeviceOnline(currentTimestamp - updateTimeMillis <= 60 * 60 * 1000);

					formatProperties(item.getProperties());
					aggregatedDevice.setProperties(item.getProperties());
					aggregatedDeviceList.add(aggregatedDevice);
				}
			}
			return aggregatedDeviceList;
		}


		/**
		 * Parses the provided update time string into a timestamp in milliseconds.
		 * If parsing fails, it logs an error and returns 0.
		 *
		 * @param updateTime the update time string in the expected date format
		 */
		private long parseUpdateTime(String updateTime) {
			try {
				OffsetDateTime parsedDateTime = OffsetDateTime.parse(updateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
				return parsedDateTime.toInstant().toEpochMilli();
			} catch (Exception e) {
				logger.error("Error parsing update time: ", e);
				return 0;
			}
		}

		/**
		 * Formats specific properties within the provided map by adjusting date formats
		 * and handling empty or null values. The method performs the following adjustments:
		 * - If the property name ends with "HumidityUpdate", "Update", "C02Update", or "PressureUpdate",
		 * it converts the date format of the value using {@link #convertDateTimeFormat(String)}.
		 * - If the property name ends with "DeskOccupancyRemarks", it checks the value for an empty array
		 * format ("[]") and, if found, replaces it with "None". Otherwise, it concatenates values into a string.
		 */
		private void formatProperties(Map<String, String> properties) {
			properties.forEach((name, value) -> {
				if (name.endsWith("HumidityUpdate") || name.endsWith("Update") || name.endsWith("C02Update") || name.endsWith("PressureUpdate")) {
					properties.put(name, convertDateTimeFormat(value));
				} else if (name.endsWith("DeskOccupancyRemarks")) {
					properties.put(name, !Objects.equals(value, "[]") ? getDefaultValueForNullData(String.join(", ", value)) : "None");
				}
			});
		}

		/**
		 * capitalize the first character of the string
		 *
		 * @param input input string
		 * @return string after fix
		 */
		private String uppercaseFirstCharacter(String input) {
			char firstChar = input.charAt(0);
			return Character.toUpperCase(firstChar) + input.substring(1);
		}

		/**
		 * Converts a date-time string from the default format to the target format with GMT timezone.
		 *
		 * @param inputDateTime The input date-time string in the default format.
		 * @return The date-time string after conversion to the target format with GMT timezone.
		 * Returns {@link DisruptiveTechnologiesConstant#NONE} if there is an error during conversion.
		 */
		private String convertDateTimeFormat(String inputDateTime) {
			if (DisruptiveTechnologiesConstant.NONE.equals(inputDateTime)) {
				return inputDateTime;
			}
			try {
				DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(DisruptiveTechnologiesConstant.DEFAULT_FORMAT_DATETIME_WITH_MILLIS)
						.withZone(ZoneOffset.UTC);
				DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern(DisruptiveTechnologiesConstant.TARGET_FORMAT_DATETIME)
						.withZone(ZoneOffset.UTC);

				OffsetDateTime date = OffsetDateTime.parse(inputDateTime, inputFormatter);
				return date.format(outputFormatter);
			} catch (Exception e) {
				logger.warn("Can't convert the date time value");
				return DisruptiveTechnologiesConstant.NONE;
			}
		}

		/**
		 * check value is null or empty
		 *
		 * @param value input value
		 * @return value after checking
		 */
		private String getDefaultValueForNullData(String value) {
			return StringUtils.isNotNullOrEmpty(value) && !"null".equalsIgnoreCase(value) ? uppercaseFirstCharacter(value) : DisruptiveTechnologiesConstant.NONE;
		}

		/**
		 * Uptime is received in seconds, need to normalize it and make it human-readable, like
		 * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
		 * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
		 * We don't need to add a segment of time if it's 0.
		 *
		 * @param uptimeSeconds value in seconds
		 * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
		 */
		private String normalizeUptime(long uptimeSeconds) {
			StringBuilder normalizedUptime = new StringBuilder();

			long seconds = uptimeSeconds % 60;
			long minutes = uptimeSeconds % 3600 / 60;
			long hours = uptimeSeconds % 86400 / 3600;
			long days = uptimeSeconds / 86400;

			if (days > 0) {
				normalizedUptime.append(days).append(" day(s) ");
			}
			if (hours > 0) {
				normalizedUptime.append(hours).append(" hour(s) ");
			}
			if (minutes > 0) {
				normalizedUptime.append(minutes).append(" minute(s) ");
			}
			if (seconds > 0) {
				normalizedUptime.append(seconds).append(" second(s)");
			}
			return normalizedUptime.toString().trim();
		}
	}
