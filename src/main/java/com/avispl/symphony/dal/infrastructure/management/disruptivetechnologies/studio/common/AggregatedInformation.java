/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common;

/**
 * Enum AggregatedInformation represents various pieces of aggregated information about a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 22/10/2024
 * @since 1.0.0
 */
public enum AggregatedInformation {
	TYPE("Type", ""),
	PRODUCT_NUMBER("ProductNumber", ""),
	LABELS_NAME("LabelName", ""),
	LABELS_CUSTOM("LabelCustom", ""),
	CONNECTION_STATUS("ConnectionStatus", ""),

	NETWORK_STATUS_SIGNAL_STRENGTH("SignalStrength(%)", "NetworkStatus#"),
	NETWORK_STATUS_RSSI("RSSI", "NetworkStatus#"),
	NETWORK_STATUS_UPDATE_TIME("Update", "NetworkStatus#"),
	NETWORK_STATUS_TRANSMISSION_MODE("TransmissionMode", "NetworkStatus#"),

	BATTERY_STATUS_PERCENTAGE("Level(%)", "BatteryStatus#"),
	BATTERY_STATUS_UPDATE_TIME("Update", "BatteryStatus#"),

	/** CO2 **/
	CO2_PPM("CO2(ppm)", "SensorData#"),
	CO2_UPDATE_TIME("C02Update", "SensorData#"),

	PRESSURE_PASCAL("Pressure(hPa)", "SensorData#"),
	PRESSURE_UPDATE_TIME("PressureUpdate", "SensorData#"),

	/** DeskOccupancy **/
	DESK_OCCUPANCY_STATE("DeskOccupancyState", "SensorData#"),
	DESK_OCCUPANCY_UPDATE_TIME("DeskOccupancyUpdate", "SensorData#"),
	DESK_OCCUPANCY_REMARKS("DeskOccupancyRemarks", "SensorData#"),

	/** Door&Window **/
	CONTACT_STATE("ContactState", "SensorData#"),
	CONTACT_UPDATE_TIME("ContactUpdate", "SensorData#"),

	/** Humidity **/
	HUMIDITY_TEMP("TemperatureUpdate(C)", "SensorData#"),
	HUMIDITY_RELATIVE("Humidity(%)", "SensorData#"),
	HUMIDITY_UPDATE_TIME("HumidityUpdate", "SensorData#"),

	/** Motion **/
	MOTION_STATE("MotionState", "SensorData#"),
	MOTION_UPDATE_TIME("MotionUpdate", "SensorData#"),

	/** Proximity Counter **/
	OBJECT_PRESENCE_COUNT_TOTAL("ObjectPresenceCount", "SensorData#"),
	OBJECT_PRESENCE_COUNT_UPDATE_TIME("ObjectPresenceCountUpdate", "SensorData#"),

	/** Proximity **/
	OBJECT_PRESENCE_STATE("ObjectPresenceState", "SensorData#"),
	OBJECT_PRESENCE_UPDATE_TIME("ObjectPresenceUpdate", "SensorData#"),

	/** Temperature **/
	TEMPERATURE_VALUE("Temperature(C)", "SensorData#"),
	TEMPERATURE_UPDATE_TIME("TemperatureUpdate", "SensorData#"),

	/** Touch Counter **/
	TOUCH_COUNT_TOTAL("TouchCount", "SensorData#"),
	TOUCH_COUNT_UPDATE_TIME("TouchCountUpdate", "SensorData#"),

	/** Touch **/
	SENSOR_DATA("TouchUpdate", "SensorData#"),

	/** WaterDetector **/
	WATER_PRESENT_STATE("WaterDetectionState", "SensorData#"),
	WATER_PRESENT_UPDATE_TIME("WaterDetectionUpdate", "SensorData#"),




	;
	private final String name;
	private final String group;

	/**
	 * Constructor for AggregatedInformation.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	AggregatedInformation(String name, String group) {
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
