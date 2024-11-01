/*
 *  Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */

package com.avispl.symphony.dal.infrastructure.management.disruptivetechnologies.studio;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;

/**
 * DisruptiveTechnologiesCommunicatorTest
 *
 * @author Harry / Symphony Dev Team<br>
 * Created on 25/10/2024
 * @since 1.0.0
 */
public class DisruptiveTechnologiesCommunicatorTest {
	private ExtendedStatistics extendedStatistic;
	private DisruptiveTechnologiesCommunicator disruptiveTechnologiesCommunicator;

	@BeforeEach
	void setUp() throws Exception {
		disruptiveTechnologiesCommunicator = new DisruptiveTechnologiesCommunicator();
		disruptiveTechnologiesCommunicator.setHost("api.disruptive-technologies.com");
		disruptiveTechnologiesCommunicator.setLogin("crbc8026n54000drmb00@crbc2sna9j4j6igdjkvg.serviceaccount.d21s.com crbc89vs2lg0009sta1g");
		disruptiveTechnologiesCommunicator.setPassword("9524fc9a7fd04c128a02b830def287d7");
		disruptiveTechnologiesCommunicator.setPort(443);
		disruptiveTechnologiesCommunicator.init();
		disruptiveTechnologiesCommunicator.connect();
		String defaultProjectId = "crbc2sna9j4j6igdjkvg";
		disruptiveTechnologiesCommunicator.setProjectId(defaultProjectId);
	}

	@AfterEach
	void destroy() throws Exception {
		disruptiveTechnologiesCommunicator.disconnect();
		disruptiveTechnologiesCommunicator.destroy();
	}

	@Test
	void testLoginSuccess() throws Exception {
		disruptiveTechnologiesCommunicator.getMultipleStatistics();
	}

	@Test
	void testGetAggregatorData() throws Exception {
		extendedStatistic = (ExtendedStatistics) disruptiveTechnologiesCommunicator.getMultipleStatistics().get(0);
		Map<String, String> stats = extendedStatistic.getStatistics();
//		Map<String, String> dsMap = extendedStatistic.getDynamicStatistics();
//		System.out.println("stats: " + stats);
		Assertions.assertEquals("AVI-SPL-LAB Inventory", stats.get("Generic#ProjectName"));
		Assertions.assertEquals("AVI-SPL-LAB", stats.get("Generic#OrganizationName"));
		Assertions.assertEquals("11", stats.get("Generic#MonitoredSensorCount"));
	}

	@Test
	void testGetAggregatedData() throws Exception {
		disruptiveTechnologiesCommunicator.getMultipleStatistics();
		disruptiveTechnologiesCommunicator.retrieveMultipleStatistics();
		Thread.sleep(20000);
		List<AggregatedDevice> aggregatedDeviceList = disruptiveTechnologiesCommunicator.retrieveMultipleStatistics();
//		System.out.println("aggregatedDeviceList " + aggregatedDeviceList);
		String sensorId = "emucrbc5qpqbmpf547g7dh0";
		Optional<AggregatedDevice> aggregatedDevice = aggregatedDeviceList.stream().filter(item -> item.getDeviceId().equals(sensorId)).findFirst();
		if (aggregatedDevice.isPresent()) {
			Map<String, String> stats = aggregatedDevice.get().getProperties();
			Assertions.assertEquals("co2", stats.get("Type"));
			Assertions.assertEquals("CO2 Sensor 1", stats.get("LabelName"));
		}
	}

	@Test
	void testGetNumberOfDevices() throws Exception {
		disruptiveTechnologiesCommunicator.getMultipleStatistics();
		disruptiveTechnologiesCommunicator.retrieveMultipleStatistics();
		Thread.sleep(20000);
		List<AggregatedDevice> aggregatedDeviceList = disruptiveTechnologiesCommunicator.retrieveMultipleStatistics();
		Assert.assertEquals(11, aggregatedDeviceList.size());
	}

	@Test
	void testTimeLogin() throws Exception {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		long iat = System.currentTimeMillis() / 1000;
		System.out.println(exp);
		System.out.println(iat);
	}

}
