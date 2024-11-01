/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common;

/**
 * AggregatorInformation class represents information about the aggregator.
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 22/10/2024
 * @since 1.0.0
 */
public enum AggregatorInformation {
	PROJECT_ID("name", ""),
	DISPLAY_NAME("displayName", ""),
	ORGANIZATION("organization", ""),
	ORGANIZATION_DISPLAY_NAME("organizationDisplayName", ""),
	SENSOR_COUNT("sensorCount", ""),
	CLOUD_CONNECTOR_COUNT("cloudConnectorCount", ""),
	INVENTORY("inventory", ""),
	LOCATION_TIME("timeLocation", "LocationTime"),
	LOCATION_LATITUDE("latitude", "LocationLatitude"),
	LOCATION_LONGITUDE("longitude", "LocationLongitude"),
	;
	private final String name;
	private final String group;

	/**
	 * Constructor for AggregatorInformation.
	 *
	 * @param name The name representing the system information category.
	 * @param group The corresponding value associated with the category.
	 */
	AggregatorInformation(String name, String group) {
		this.name = name;
		this.group = group;
	}

	/**
	 * Retrieves {@link #name}
	 *
	 * @return value of {@link #name}
	 */
	public String getName() {
		return name;
	}

	/**
	 * Retrieves {@link #group}
	 *
	 * @return value of {@link #group}
	 */
	public String getGroup() {
		return group;
	}
}
