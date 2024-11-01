/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio.common;

/**
 * DisruptiveTechnologiesCommand
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 22/10/2024
 * @since 1.0.0
 */
public class DisruptiveTechnologiesCommand {
	public static final String GET_TOKEN = "/oauth2/token";
	public static final String GET_SENSORS_BY_PROJECT = "/v2/projects/%s/devices";
	public static final String GET_ALL_PROJECT = "/v2/projects";
	public static final String GET_SINGLE_PROJECT = "/v2/projects/%s";
}
