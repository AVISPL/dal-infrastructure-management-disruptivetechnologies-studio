/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common.metric;

/**
 * StatusEnum
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 22/10/2024
 * @since 1.0.0
 */
public enum CloudConnector {
	ID("Id", "name"),
	TYPE("Type", "type"),
	PRODUCT_NUMBER("ProductNumber", "productNumber"),
	LABELS_NAME("LabelsName", "name"),
	LABELS_CUSTOM("LabelCustom", "custom"),

	CONNECTION_STATUS("ConnectionType", "connection"),
	CONNECTION_AVAILABLE("ConnectionTypeAvailable", "available"),
	CONNECTION_UPDATE_TIME("ConnectionTypeUpdateTime", "updateTime"),

	;
	private final String name;
	private final String field;

	/**
	 * Constructor for SystemNetworkInformation.
	 *
	 * @param name The name representing the system information category.
	 * @param field The field associated with the category.
	 */
	CloudConnector(String name, String field) {
		this.name = name;
		this.field = field;
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
	 * Retrieves {@link #field}
	 *
	 * @return value of {@link #field}
	 */
	public String getField() {
		return field;
	}
}
