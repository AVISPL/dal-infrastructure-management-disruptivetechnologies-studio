/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common;

/**
 * DisruptiveTechnologiesConstant
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 22/10/2024
 * @since 1.0.0
 */
public class DisruptiveTechnologiesConstant {
	public static final String HASH = "#";
	public static final String MODEL_MAPPING_AGGREGATED_DEVICE = "dt_sensor/model-mapping.yml";
	public static final String REQUEST_BODY = "assertion=%s&grant_type=urn%%3Aietf%%3Aparams%%3Aoauth%%3Agrant-type%%3Ajwt-bearer";
	public static final String NONE = "None";
	public static final String SPACE = " ";
	public static final String EMPTY = "";
	public static final String GENERIC = "Generic";
	public static final String DEFAULT_FORMAT_DATETIME_WITH_MILLIS  = "yyyy-MM-dd'T'HH:mm:ss.S'Z'";
	public static final String DEFAULT_FORMAT_DATETIME_WITHOUT_MILLIS  = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
	public static final String TARGET_FORMAT_DATETIME = "MMM d, yyyy, h:mm a";
	public static final String ACCESS_TOKEN = "access_token";
	public static final String EXPIRES_IN = "expires_in";
	public static final String REPORTED = "reported";
	public static final String URI = "https://identity.disruptive-technologies.com";
	public static final String COLON = ":";
	public static final String DEVICES = "devices";
	public static final String CCON = "ccon";
	public static final String CLOUD_CONNECTOR = "CloudConnector_";
	public static final String CONNECTION_STATUS = "connectionStatus";
	public static final String LABELS = "labels";
	public static final String NAME = "name";

	public static final String MONITORED_DEVICES_TOTAL = "MonitoredDevicesTotal";
	public static final String MONITORING_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
	public static final String ADAPTER_VERSION = "AdapterVersion";
	public static final String ADAPTER_BUILD_DATE = "AdapterBuildDate";
	public static final String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
	public static final String ADAPTER_UPTIME = "AdapterUptime";
	public static final String ADAPTER_RUNNER_SIZE = "RunnerSize(b)";
	/**
	 * Token timeout is 29 minutes, as this case reserve 1 minutes to make sure we never failed because of the timeout
	 */
	public static final long TIMEOUT = 28;
}
