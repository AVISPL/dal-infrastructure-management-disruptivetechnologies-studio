	/*
	 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
	 */

	package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio;

	import java.io.IOException;
	import java.net.URLEncoder;
	import java.time.OffsetDateTime;
	import java.time.ZoneOffset;
	import java.time.format.DateTimeFormatter;
	import java.util.ArrayList;
	import java.util.Arrays;
	import java.util.Base64;
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

	import com.fasterxml.jackson.databind.JsonNode;
	import com.fasterxml.jackson.databind.ObjectMapper;
	import javax.crypto.Mac;
	import javax.crypto.spec.SecretKeySpec;
	import javax.security.auth.login.FailedLoginException;
	import org.openjdk.jol.info.ClassLayout;

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
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.AggregatedInformation;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.AggregatorInformation;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.DisruptiveTechnologiesCommand;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.DisruptiveTechnologiesConstant;
	import com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.LoginInfo;
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
			private volatile boolean dataFetchCompleted = false;

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
						if (!dataFetchCompleted && nextDevicesCollectionIterationTimestamp <= currentTimestamp) {
							populateDeviceDetails();
							dataFetchCompleted = true;
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
						if (dataFetchCompleted) {
							nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;
							lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle) / 1000;
							logger.debug("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);
							dataFetchCompleted = false;
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
		 * Configurable property for historical properties, comma separated values kept as set locally
		 */
		private Set<String> historicalProperties = new HashSet<>();

		/**
		 * Retrieves {@link #historicalProperties}
		 *
		 * @return value of {@link #historicalProperties}
		 */
		public String getHistoricalProperties() {
			return String.join(",", this.historicalProperties);
		}

		/**
		 * Sets {@link #historicalProperties} value
		 *
		 * @param historicalProperties new value of {@link #historicalProperties}
		 */
		public void setHistoricalProperties(String historicalProperties) {
			this.historicalProperties.clear();
			Arrays.asList(historicalProperties.split(",")).forEach(propertyName -> {
				this.historicalProperties.add(propertyName.trim());
			});
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
		 */
		@Override
		public void controlProperty(ControllableProperty controllableProperty) throws Exception {}

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
				retrieveMetadata(stats, dynamicStatistics);

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
		 * Retrieves metadata information and updates the provided statistics and dynamic map.
		 *
		 * @param stats the map where statistics will be stored
		 * @param dynamicStatistics the map where dynamic statistics will be stored
		 * @throws Exception if there is an error during the retrieval process
		 */
		private void retrieveMetadata(Map<String, String> stats, Map<String, String> dynamicStatistics) throws Exception {
			try {
				dynamicStatistics.put(DisruptiveTechnologiesConstant.MONITORED_DEVICES_TOTAL,
						projectID != null ? String.valueOf(aggregatedResponse.get(DisruptiveTechnologiesConstant.DEVICES).size()) : "0");

				if (lastMonitoringCycleDuration != null) {
					dynamicStatistics.put(DisruptiveTechnologiesConstant.MONITORING_CYCLE_DURATION, String.valueOf(lastMonitoringCycleDuration));
				}

				stats.put(DisruptiveTechnologiesConstant.ADAPTER_VERSION,
						getDefaultValueForNullData(adapterProperties.getProperty("aggregator.version")));
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_BUILD_DATE,
						getDefaultValueForNullData(adapterProperties.getProperty("aggregator.build.date")));

				dynamicStatistics.put(DisruptiveTechnologiesConstant.ADAPTER_RUNNER_SIZE,
						String.valueOf(ClassLayout.parseInstance(this).toPrintable().length()/1000));

				long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000 * 60)));
				stats.put(DisruptiveTechnologiesConstant.ADAPTER_UPTIME, normalizeUptime(adapterUptime / 1000));
			} catch (Exception e) {
				logger.error("Failed to populate metadata information with projectId " + projectID, e);
			}
		}

		/**
		 * Retrieves project information and updates the provided statistics map.
		 *
		 * @param stats the map where statistics will be stored
		 * @throws Exception if there is an error during the retrieval process
		 */
		private void retrieveProjectInfo(Map<String, String> stats) throws Exception {
			try {
				String projectId = this.getProjectId();
				if(Objects.equals(projectId, "")) {
					String group = DisruptiveTechnologiesConstant.GENERIC + DisruptiveTechnologiesConstant.HASH;
					stats.put(group + "ProjectId", "Unknown");
					stats.put(group + "ProjectName", "Unknown");
					stats.put(group + "OrganizationId", "Unknown");
					return;
				}
				JsonNode response = this.doGet(String.format(DisruptiveTechnologiesCommand.GET_SINGLE_PROJECT, this.getProjectId()), JsonNode.class);
				if (response != null) {
					String GroupName = DisruptiveTechnologiesConstant.GENERIC + DisruptiveTechnologiesConstant.HASH;

					for (AggregatorInformation property : AggregatorInformation.values()) {
						String group = property.getGroup();
						switch (property) {
							case ORGANIZATION:
								String[] OrganizationId = response.get(property.getName()).asText().split("/");
								stats.put(GroupName + "OrganizationId", OrganizationId[1]);
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
								stats.put(GroupName + "ProjectId", id[1]);
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
				if (e.getMessage().contains("403")) {
					String group = DisruptiveTechnologiesConstant.GENERIC + DisruptiveTechnologiesConstant.HASH;
					stats.put(group + "ProjectId", projectId);
					stats.put(group + "ProjectName", "None");
					stats.put(group + "OrganizationId", "None");
					logger.error("403 Forbidden: Invalid projectId or insufficient permissions for projectId " + projectID, e);
				} else {
					throw new ResourceNotReachableException("Unable to retrieve project information.", e);
				}
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
					String group = labelsNode.get(DisruptiveTechnologiesConstant.NAME).asText().isEmpty()
							? uppercaseFirstCharacter(item.get("type").asText()) + DisruptiveTechnologiesConstant.SPACE + DisruptiveTechnologiesConstant.SENSOR
							: labelsNode.get(DisruptiveTechnologiesConstant.NAME).asText();
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
			return item.at("/type").asText().equals(DisruptiveTechnologiesConstant.CCON);
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
					value = getIdValueOfCloudConnector(item, cloudConnector);
					break;
				case LABEL_NAME:
					String labelName = item.path(DisruptiveTechnologiesConstant.LABELS).path(cloudConnector.getField()).asText();
					value = labelName.isEmpty() ? getDefaultValueOfCloudConnector(item, DisruptiveTechnologiesConstant.SENSOR) : labelName;
					break;
				case LABEL_DESCRIPTION:
					String labelDescription = item.path(DisruptiveTechnologiesConstant.LABELS).path(cloudConnector.getField()).asText();
					value = labelDescription.isEmpty() ? getDefaultValueOfCloudConnector(item, DisruptiveTechnologiesConstant.DESCRIPTION) : labelDescription;
					break;
				case CONNECTION_STATUS:
					value = getDefaultValueForNullData(getStatusValue(item, cloudConnector));
					break;
				case CONNECTION_AVAILABLE:
					value = getDefaultValueForNullData(getConnectionAvailableValue(item, cloudConnector));
					break;
				case CONNECTION_UPDATE_TIME:
					value = getDefaultValueForNullData(getConnectionUpdateTime(item, cloudConnector));
					break;
				default:
					value = getDefaultValueForNullData(item.path(cloudConnector.getField()).asText());
					break;
			}
			stats.put(key, value);
		}

		/**
		 * Retrieves the type value from the given JSON node based on the specified cloud connector field.
		 * If the field is not present or is null, returns a constant representing a non-existent value.
		 *
		 * @param item the JSON node containing type information
		 * @return the formatted type value as a string; if the field is missing or null, returns {@link DisruptiveTechnologiesConstant#NONE}
		 */
		private String getDefaultValueOfCloudConnector(JsonNode item, String suffix) {
			JsonNode fieldNode = item.get(CloudConnector.TYPE.getField());
			String fieldValue = fieldNode.asText() + DisruptiveTechnologiesConstant.SPACE + suffix;
			return uppercaseFirstCharacter(fieldValue);
		}

		/**
		 * Retrieves the ID value for a cloud connector item.
		 *
		 * @param item the JSON node representing the device item
		 * @param cloudConnector the CloudConnector type
		 */
		private String getIdValueOfCloudConnector(JsonNode item, CloudConnector cloudConnector) {
			if (cloudConnector == null || cloudConnector.getField() == null) {
				return DisruptiveTechnologiesConstant.NONE;
			}
			JsonNode fieldNode = item.get(cloudConnector.getField());
			if (fieldNode == null || fieldNode.isNull()) {
				return DisruptiveTechnologiesConstant.NONE;
			}
			String[] name = fieldNode.asText().split("/");
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
						if (item.at("/type") != null && !item.at("/type").asText().equals(DisruptiveTechnologiesConstant.CCON)) {
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
		 * @param keyId  the key id for authentication
		 * @param email  the email for authentication
		 * @param secret the secret id for the application
		 */
		private String generateJWT(String keyId, String email, String secret) throws Exception {
			long now = System.currentTimeMillis();
			Map<String, Object> jwtHeaders = new HashMap<>();
			Date issuedAt = new Date(now);
			Date expiresAt = new Date(now + 3600 * 1000);
			jwtHeaders.put("alg", "HS256");
			jwtHeaders.put("kid", keyId);

			Map<String, Object> jwtPayload = new HashMap<>();
			jwtPayload.put("aud", DisruptiveTechnologiesConstant.URI + DisruptiveTechnologiesCommand.GET_TOKEN);
			jwtPayload.put("iss", email);
			jwtPayload.put("iat", issuedAt.getTime() / 1000);
			jwtPayload.put("exp", expiresAt.getTime() / 1000);

			String headerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(toJson(jwtHeaders).getBytes());
			String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(toJson(jwtPayload).getBytes());

			String signatureBase = headerBase64 + "." + payloadBase64;
			String signature = generateHmacSHA256(secret, signatureBase);

			return signatureBase + "." + signature;
		}

		/**
		 * Generates an HMAC-SHA256 signature for the given message using the provided secret key.
		 *
		 * @param secret the secret key used for generating the HMAC (must be a non-null string).
		 * @param message the message to be signed (must be a non-null string).
		 * @return the Base64 URL-safe encoded HMAC-SHA256 signature (without padding).
		 * @throws Exception if the HMAC-SHA256 algorithm is not available or if there is an issue with the key initialization.
		 */
		private String generateHmacSHA256(String secret, String message) throws Exception {
			SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(keySpec);
			byte[] hmacBytes = mac.doFinal(message.getBytes());
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
		}

		/**
		 * Converts a map of key-value pairs into a JSON string.
		 * Keys and values in the map are serialized as JSON string properties.
		 * @param map the map containing key-value pairs to be serialized (keys must be strings).
		 * @return a JSON-formatted string representation of the map.
		 */
		private String toJson(Map<String, Object> map) {
			StringBuilder json = new StringBuilder("{");
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				json.append("\"").append(entry.getKey()).append("\":");
				if (entry.getValue() instanceof String) {
					json.append("\"").append(entry.getValue()).append("\"");
				} else {
					json.append(entry.getValue());
				}
				json.append(",");
			}
			if (json.charAt(json.length() - 1) == ',') {
				json.deleteCharAt(json.length() - 1);
			}
			json.append("}");
			return json.toString();
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

					Map<String, String> dynamicStats = new HashMap<>();
					Map<String, String> stats = new HashMap<>();
					boolean isDeviceOnline = false;

					long updateTimeMillis = parseUpdateTime(updateTime);
					AggregatedDevice aggregatedDevice = new AggregatedDevice();
					aggregatedDevice.setDeviceId(item.getDeviceId());
					aggregatedDevice.setDeviceName(item.getDeviceName().isEmpty() ?
							getDefaultNameOrDescriptionOfSensor(item.getDeviceModel(), DisruptiveTechnologiesConstant.SENSOR)
							: item.getDeviceName());


					isDeviceOnline = currentTimestamp - updateTimeMillis <= 60 * 60 * 1000;

					aggregatedDevice.setDeviceOnline(isDeviceOnline);

					populateDeviceSensor(item.getProperties(), stats, dynamicStats, isDeviceOnline);

					formatProperties(stats);
					aggregatedDevice.setProperties(stats);
					aggregatedDevice.setDynamicStatistics(dynamicStats);
					aggregatedDeviceList.add(aggregatedDevice);
				}
			}
			return aggregatedDeviceList;
		}

		/**
		 * Populates device sensor information into the specified {@code stats} map based on the cached data.
		 * This method retrieve data from the cached device sensor information and extracts relevant sensor properties.
		 *
		 * @param cached The cached data containing device sensor information.
		 * @param stats The map to populate with the extracted sensor information.
		 * @param dynamicStats The map to populate with the dynamic sensor information.
		 */
		private void populateDeviceSensor(Map<String, String> cached, Map<String, String> stats, Map<String, String> dynamicStats, Boolean isDeviceOnline){
			try{
				for (AggregatedInformation item : AggregatedInformation.values()) {
						String name = item.getGroup() + item.getName();
						String value = getDefaultValueForNullData(cached.get(name));
					if (DisruptiveTechnologiesConstant.NONE.equalsIgnoreCase(value) && !isLabelField(item)) {
						continue;
					}
					boolean propertyListed = !historicalProperties.isEmpty() && isSupportedHistorical(name, item);
						switch (item) {
							case NETWORK_STATUS_SIGNAL_STRENGTH:
							case NETWORK_STATUS_RSSI:
							case BATTERY_STATUS_PERCENTAGE:
							case TEMPERATURE_VALUE:
							case HUMIDITY_RELATIVE:
							case CO2_PPM:
								if(propertyListed && isDeviceOnline){
									dynamicStats.put(name, value);
								}else {
									stats.put(name, value);
								}
								break;
							case HUMIDITY_TEMP:
								if(propertyListed && isDeviceOnline){
									dynamicStats.put(DisruptiveTechnologiesConstant.TEMPERATURE_NAME, value);
								} else {
									stats.put(DisruptiveTechnologiesConstant.TEMPERATURE_NAME, value);
								}
								break;
							case PRESSURE_PASCAL:
								try {
									int pressure = (int) Double.parseDouble(value) / 100;
									String formattedPressure = String.valueOf(pressure);
									if (propertyListed && isDeviceOnline) {
										dynamicStats.put(name, formattedPressure);
									}
									stats.put(name, formattedPressure);
								} catch (NumberFormatException e) {
									logger.error("Error parsing pressure value for " + name, e);
									stats.put(name, DisruptiveTechnologiesConstant.NONE);
								}
								break;
							case OBJECT_PRESENT_STATE:
								stats.put(name, DisruptiveTechnologiesConstant.NOT_PRESENT.equals(value) ? "NO_OBJECT_DETECTED" : "OBJECT_DETECTED ");
								break;
							case WATER_PRESENT_STATE:
								stats.put(name, DisruptiveTechnologiesConstant.NOT_PRESENT.equals(value) ? "NO_WATER_DETECTED" : "WATER_DETECTED");
								break;
							case NETWORK_STATUS_TRANSMISSION_MODE:
								stats.put(name, "LOW_POWER_STANDARD_MODE".equals(value) ? "Standard power usage" : "");
								break;
							case LABEL_NAME:
								String labelName = Objects.equals(value, DisruptiveTechnologiesConstant.NONE)
										? getDefaultNameOrDescriptionOfSensor(cached.get(DisruptiveTechnologiesConstant.TYPE), DisruptiveTechnologiesConstant.SENSOR)
										: value;
								stats.put(name, labelName);
								break;
							case LABEL_DESCRIPTION:
								String labelDescription = Objects.equals(value, DisruptiveTechnologiesConstant.NONE)
										? getDefaultNameOrDescriptionOfSensor(cached.get(DisruptiveTechnologiesConstant.TYPE), DisruptiveTechnologiesConstant.DESCRIPTION)
										: value;
								stats.put(name, labelDescription);
								break;
							case TYPE:
								stats.put(name, getTypeOfSensor(cached.get(DisruptiveTechnologiesConstant.TYPE)));
								break;
							default:
								stats.put(name, value);
								break;
						}
					}
			} catch (Exception e) {
				logger.error("Error while populate Sensor Info", e);
			}
		}

		/**
		 * Verify historical properties
		 * @param name name of historical properties
		 * @param item name of value mapping
		 * @return true or false
		 */
		private boolean isSupportedHistorical(String name, AggregatedInformation item) {
			if(AggregatedInformation.HUMIDITY_TEMP.getName().equals(item.getName())){
				return historicalProperties.stream().anyMatch(item1 -> item1.equals(DisruptiveTechnologiesConstant.TEMPERATURE_NAME));
			}
			return historicalProperties.stream().anyMatch(item1 -> item1.equals(name));
		}


		/**
		 * If the type value is "Co2", it will be returned in uppercase. Otherwise, the first character
		 * of the type value will be capitalized.
		 *
		 * @param item  the JSON node containing type information
		 * @return the formatted type value as a string
		 */
		private String getTypeOfSensor(String item) {
			return item.equals("co2") ? item.toUpperCase() : uppercaseFirstCharacter(item);
		}

		/**
		 * Retrieves the default value from the given JSON node based on the specified sensor.
		 * If the field is not present or is null, returns a constant representing a non-existent value.
		 * If the type value is "Co2", it will be returned in uppercase. Otherwise, the first character
		 * of the type value will be capitalized.
		 *
		 * @param item  the JSON node containing type information
		 * @return the formatted type value as a string
		 */
		private String getDefaultNameOrDescriptionOfSensor(String item, String suffix) {
			return (item.equals("co2") ? item.toUpperCase() : uppercaseFirstCharacter(item))
					+ DisruptiveTechnologiesConstant.SPACE + suffix;
		}


		/**
		 * Parses the provided update time string into a timestamp in milliseconds.
		 * If parsing fails, it logs an error and returns 0.
		 *
		 * @param updateTime the update time string in the expected date format
		 */
		private long parseUpdateTime(String updateTime) {
			try {
				if(updateTime == null) {
					return 0;
				}
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
		 * Checks if the specified {@link AggregatedInformation} item is either a
		 * LABEL_NAME or LABEL_DESCRIPTION field.
		 *
		 * @param item the {@link AggregatedInformation} item to check
		 **/
		private boolean isLabelField(AggregatedInformation item) {
			return item == AggregatedInformation.LABEL_NAME || item == AggregatedInformation.LABEL_DESCRIPTION;
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
