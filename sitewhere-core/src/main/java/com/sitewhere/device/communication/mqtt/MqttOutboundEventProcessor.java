/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.device.communication.mqtt;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import com.sitewhere.SiteWhere;
import com.sitewhere.common.MarshalUtils;
import com.sitewhere.device.event.processor.FilteredOutboundEventProcessor;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.device.IDevice;
import com.sitewhere.spi.device.IDeviceAssignment;
import com.sitewhere.spi.device.IDeviceManagement;
import com.sitewhere.spi.device.event.IDeviceAlert;
import com.sitewhere.spi.device.event.IDeviceCommandInvocation;
import com.sitewhere.spi.device.event.IDeviceCommandResponse;
import com.sitewhere.spi.device.event.IDeviceEvent;
import com.sitewhere.spi.device.event.IDeviceLocation;
import com.sitewhere.spi.device.event.IDeviceMeasurements;
import com.sitewhere.spi.device.event.processor.IMulticastingOutboundEventProcessor;
import com.sitewhere.spi.device.event.processor.multicast.IDeviceEventMulticaster;
import com.sitewhere.spi.device.event.processor.routing.IRouteBuilder;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;

/**
 * Outbound event processor that sends events to an MQTT topic.
 * 
 * @author Derek
 */
public class MqttOutboundEventProcessor extends FilteredOutboundEventProcessor
	implements IMulticastingOutboundEventProcessor<String>, IMqttComponent {

    /** Static logger instance */
    private static Logger LOGGER = LogManager.getLogger();

    private String protocol = MqttLifecycleComponent.DEFAULT_PROTOCOL;

    /** Host name */
    private String hostname = MqttLifecycleComponent.DEFAULT_HOSTNAME;

    /** Port */
    private String port = MqttLifecycleComponent.DEFAULT_PORT;

    /** TrustStore path */
    private String trustStorePath;

    /** TrustStore password */
    private String trustStorePassword;

    /** KeyStore path */
    private String keyStorePath;

    /** KeyStore password */
    private String keyStorePassword;

    /** Topic events are posted to */
    private String topic;

    /** Broker username */
    private String username;

    /** Broker password */
    private String password;

    /** Client id */
    private String clientId;

    /** Clean session flag */
    private boolean cleanSession = true;

    /** Quality of service */
    private int qos = 0;

    /** MQTT client */
    private MqttClient mqttClient;

    /** Multicaster for events */
    private IDeviceEventMulticaster<String> multicaster;

    /** Route builder for generating topics */
    private IRouteBuilder<String> routeBuilder;

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#start
     * (com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void start(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	if ((topic == null) && ((multicaster == null) && (routeBuilder == null))) {
	    throw new SiteWhereException("No topic specified and no multicaster or route builder configured.");
	}

	// Required for filters.
	super.start(monitor);

	// Start multicaster if configured.
	if (multicaster != null) {
	    startNestedComponent(multicaster, monitor, true);
	}

	// Start route builder if configured.
	if (routeBuilder != null) {
	    startNestedComponent(routeBuilder, monitor, true);
	}

	// Use common MQTT configuration setup.
	this.mqttClient = MqttLifecycleComponent.connect(this);
	LOGGER.info("Connected to MQTT broker.");
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#stop(
     * com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void stop(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Stop multicaster if configured.
	if (multicaster != null) {
	    multicaster.lifecycleStop(monitor);
	}

	// Stop route builder if configured.
	if (routeBuilder != null) {
	    routeBuilder.lifecycleStop(monitor);
	}

	if ((getMqttClient() != null) && (getMqttClient().isConnected())) {
	    try {
		getMqttClient().disconnect();
	    } catch (Exception e) {
		LOGGER.error("Error shutting down MQTT device event receiver.", e);
	    }
	}
	super.stop(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#
     * onMeasurementsNotFiltered(com.sitewhere.spi.device.event.
     * IDeviceMeasurements)
     */
    @Override
    public void onMeasurementsNotFiltered(IDeviceMeasurements measurements) throws SiteWhereException {
	sendEvent(measurements);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#
     * onLocationNotFiltered(com.sitewhere.spi.device.event.IDeviceLocation)
     */
    @Override
    public void onLocationNotFiltered(IDeviceLocation location) throws SiteWhereException {
	sendEvent(location);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#
     * onAlertNotFiltered (com.sitewhere.spi.device.event.IDeviceAlert)
     */
    @Override
    public void onAlertNotFiltered(IDeviceAlert alert) throws SiteWhereException {
	sendEvent(alert);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#
     * onCommandInvocationNotFiltered
     * (com.sitewhere.spi.device.event.IDeviceCommandInvocation)
     */
    @Override
    public void onCommandInvocationNotFiltered(IDeviceCommandInvocation invocation) throws SiteWhereException {
	sendEvent(invocation);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.event.processor.FilteredOutboundEventProcessor#
     * onCommandResponseNotFiltered(com.sitewhere.spi.device.event.
     * IDeviceCommandResponse)
     */
    @Override
    public void onCommandResponseNotFiltered(IDeviceCommandResponse response) throws SiteWhereException {
	sendEvent(response);
    }

    /**
     * Send an {@link IDeviceEvent} to the configured topic.
     * 
     * @param event
     * @throws SiteWhereException
     */
    protected void sendEvent(IDeviceEvent event) throws SiteWhereException {
	IDeviceManagement dm = SiteWhere.getServer().getDeviceManagement(getTenant());
	IDeviceAssignment assignment = dm.getDeviceAssignmentByToken(event.getDeviceAssignmentToken());
	IDevice device = dm.getDeviceByHardwareId(assignment.getDeviceHardwareId());
	if (getMulticaster() != null) {
	    List<String> routes = getMulticaster().calculateRoutes(event, device, assignment);
	    for (String route : routes) {
		publish(event, route);
	    }
	} else {
	    if (getRouteBuilder() != null) {
		publish(event, getRouteBuilder().build(event, device, assignment));
	    } else {
		publish(event, getTopic());
	    }
	}
    }

    /**
     * Publish an event to an MQTT topic.
     * 
     * @param event
     * @throws SiteWhereException
     */
    protected void publish(IDeviceEvent event, String topic) throws SiteWhereException {
	try {
	    LOGGER.info("Publishing event " + event.getId() + " to route: " + topic + " with QOS " + getQos());
	    getMqttClient().publish(topic, MarshalUtils.marshalJson(event), getQos(), false);
	} catch (MqttPersistenceException e) {
	    throw new SiteWhereException("Unable to publish event.", e);
	} catch (MqttException e) {
	    throw new SiteWhereException("Unable to publish event.", e);
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#getLogger()
     */
    @Override
    public Logger getLogger() {
	return LOGGER;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.event.processor.
     * IMulticastingOutboundEventProcessor# getMulticaster()
     */
    @Override
    public IDeviceEventMulticaster<String> getMulticaster() {
	return multicaster;
    }

    public void setMulticaster(IDeviceEventMulticaster<String> multicaster) {
	this.multicaster = multicaster;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.event.processor.
     * IMulticastingOutboundEventProcessor# getRouteBuilder()
     */
    @Override
    public IRouteBuilder<String> getRouteBuilder() {
	return routeBuilder;
    }

    public void setRouteBuilder(IRouteBuilder<String> routeBuilder) {
	this.routeBuilder = routeBuilder;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getProtocol()
     */
    @Override
    public String getProtocol() {
	return protocol;
    }

    public void setProtocol(String protocol) {
	this.protocol = protocol;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.common.IInternetConnected#getHostname()
     */
    @Override
    public String getHostname() {
	return hostname;
    }

    public void setHostname(String hostname) {
	this.hostname = hostname;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getPort()
     */
    @Override
    public String getPort() {
	return port;
    }

    public void setPort(String port) {
	this.port = port;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getUsername()
     */
    @Override
    public String getUsername() {
	return username;
    }

    public void setUsername(String username) {
	this.username = username;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getPassword()
     */
    @Override
    public String getPassword() {
	return password;
    }

    public void setPassword(String password) {
	this.password = password;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.communication.mqtt.IMqttComponent#getTrustStorePath(
     * )
     */
    @Override
    public String getTrustStorePath() {
	return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
	this.trustStorePath = trustStorePath;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#
     * getTrustStorePassword()
     */
    @Override
    public String getTrustStorePassword() {
	return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
	this.trustStorePassword = trustStorePassword;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.communication.mqtt.IMqttComponent#getKeyStorePath()
     */
    @Override
    public String getKeyStorePath() {
	return keyStorePath;
    }

    public void setKeyStorePath(String keyStorePath) {
	this.keyStorePath = keyStorePath;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#
     * getKeyStorePassword()
     */
    @Override
    public String getKeyStorePassword() {
	return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
	this.keyStorePassword = keyStorePassword;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getClientId()
     */
    @Override
    public String getClientId() {
	return clientId;
    }

    public void setClientId(String clientId) {
	this.clientId = clientId;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.communication.mqtt.IMqttComponent#isCleanSession()
     */
    @Override
    public boolean isCleanSession() {
	return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
	this.cleanSession = cleanSession;
    }

    /*
     * @see com.sitewhere.device.communication.mqtt.IMqttComponent#getQos()
     */
    @Override
    public int getQos() {
	return qos;
    }

    public void setQos(String label) {
	this.qos = QoS.getValueFor(label);
	LOGGER.info("Using QoS of " + this.qos);
    }

    public String getTopic() {
	return topic;
    }

    public void setTopic(String topic) {
	this.topic = topic;
    }

    public MqttClient getMqttClient() {
	return mqttClient;
    }

    public void setMqttClient(MqttClient mqttClient) {
	this.mqttClient = mqttClient;
    }
}