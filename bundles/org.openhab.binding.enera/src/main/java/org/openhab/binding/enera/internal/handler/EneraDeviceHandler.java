/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.enera.internal.handler;

import static org.openhab.binding.enera.internal.EneraBindingConstants.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.openhab.binding.enera.internal.model.AggregationType;
import org.openhab.binding.enera.internal.model.ChannelDataSource;
import org.openhab.binding.enera.internal.model.DeviceValue;
import org.openhab.binding.enera.internal.model.RealtimeDataMessage;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link EneraDeviceHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Oliver Rahner - Initial contribution
 */
@NonNullByDefault
public class EneraDeviceHandler extends BaseThingHandler implements MqttCallbackExtended {

    private final Logger logger = LoggerFactory.getLogger(EneraDeviceHandler.class);
    // number of seconds for alive detection
    // if no data has been received for this period of time, we will re-register
    private final int LIVEDATA_TIMEOUT = 30;

    private final String mqttClientId = "Client" + UUID.randomUUID();
    @Nullable
    private MqttClient mqttClient;
    private LocalTime lastMessageReceived = LocalTime.now();
    private int retryCounter = 0;

    private String deviceSha256 = "";

    // save received values by Key for aggregation
    private final Map<String, List<Float>> aggregatedValues = new HashMap<String, List<Float>>();

    private boolean firstMessageAfterInit = true;

    private static final Map<String, ChannelDataSource> channelToSourceMapping = new HashMap<String, ChannelDataSource>();
    static {
        channelToSourceMapping.put(CHANNEL_CURRENT_CONSUMPTION,
                new ChannelDataSource(OBIS_LIVE_CONSUMPTION_TOTAL, AggregationType.AVERAGE));
        channelToSourceMapping.put(CHANNEL_METER_READING,
                new ChannelDataSource(OBIS_METER_READING, AggregationType.LAST));
        channelToSourceMapping.put(CHANNEL_METER_READING_OUTBOUND,
                new ChannelDataSource(OBIS_METER_READING_OUTBOUND, AggregationType.LAST));
    }

    @Nullable
    private ScheduledFuture<?> aliveChecker;

    public EneraDeviceHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // we don't have any commands to handle
    }

    @Override
    public void initialize() {
        scheduler.execute(this::reregisterForRealtimeApi);
    }

    public void connectMqtt() {
        this.deviceSha256 = DigestUtils.sha256Hex(getThing().getProperties().get(PROPERTY_ID));

        int counter = 0;
        Bridge bridge = this.getBridge();
        while (bridge == null) {
            if (counter == 12) {
                this.dispose();
                return;
            }
            logger.trace("No bridge could be determined. Retrying.");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                // doesn't matter
            }
            bridge = this.getBridge();
            counter++;
        }

        EneraAccountHandler handler = (EneraAccountHandler) bridge.getHandler();
        while (handler == null) {
            logger.trace("No handler could be determined. Retrying.");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                // doesn't matter
            }
            handler = (EneraAccountHandler) bridge.getHandler();
        }

        String serverUri = handler.getAccountData().getLiveURI();

        while (serverUri == null || serverUri.equals("")) {
            logger.trace("No liveUri could be determined. Retrying.");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                // doesn't matter
            }
            serverUri = handler.getAccountData().getLiveURI();
        }

        try {
            // use pseudo host here, as Live URI contains many GET parameters which lead to invalid persistence file
            // names
            // real hostname is set in connection options
            logger.trace("Creating MqttClient with ID {}", this.mqttClientId);
            this.mqttClient = new MqttClient("wss://eneramqtt", this.mqttClientId);
            MqttConnectOptions options = new MqttConnectOptions();
            logger.trace("MqttConnectOptions:");
            logger.trace("* Server URI: {}", serverUri);
            options.setServerURIs(new String[] { serverUri });
            /*
             * logger.trace("* Username: {}", LIVE_CONSUMPTION_USERNAME);
             * options.setUserName(LIVE_CONSUMPTION_USERNAME);
             * // it's okay to dump the password because it's hardcoded and not user specific
             * logger.trace("* Password: {}", LIVE_CONSUMPTION_PASSWORD);
             * options.setPassword(LIVE_CONSUMPTION_PASSWORD.toCharArray());
             */
            options.setAutomaticReconnect(false);

            mqttClient.setCallback(this);
            logger.trace("Connecting...");
            mqttClient.connect(options);
            logger.trace("... done");

        } catch (MqttException ex) {
            logger.trace("Caught MqttException:");
            logger.trace(ex.toString());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, ex.toString());

            reregisterForRealtimeApi();
        }
    }

    public void reregisterForRealtimeApi() {
        if (retryCounter > 0) {
            try {
                Thread.sleep(retryCounter * 60000L);
            } catch (InterruptedException ex) {
                // doesn't matter
            }
        }

        retryCounter++;
        logger.warn("Registering For Realtime-API. Retry count: " + retryCounter);

        if (this.mqttClient != null) {
            try {
                if (this.mqttClient.isConnected()) {
                    mqttClient.disconnectForcibly();
                }

                if (this.mqttClient != null) {
                    this.mqttClient.close(true);

                    this.mqttClient = null;
                }

            } catch (MqttException ex) {
                logger.warn(ex.getMessage(), ex);
            }
        }

        connectMqtt();
    }

    @Override
    public void dispose() {
        logger.warn("Disposing myself");
        scheduler.execute(() -> {
            if (aliveChecker != null && !aliveChecker.isCancelled()) {
                aliveChecker.cancel(true);
            }
            if (mqttClient != null && mqttClient.isConnected()) {
                try {
                    mqttClient.disconnectForcibly();
                    mqttClient.close(true);
                    logger.warn("mqttClient has been disconnected.");
                } catch (MqttException ex) {
                    logger.error("Exception while disconnecting the MQTT client!");
                    logger.error(ex.getMessage());
                }
            }

        });
        super.dispose();
    }

    @Override
    public void connectComplete(boolean reconnect, @Nullable String serverURI) {
        logger.warn("Connection to MQTT server established. Is Reconnect: {}", reconnect);

        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.subscribe(deviceSha256);
            } catch (MqttException ex) {
                logger.error("Exception while subscribing to MQTT channels!");
                logger.error(ex.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, ex.getMessage());
            }

            aliveChecker = scheduler.scheduleWithFixedDelay(() -> {
                synchronized (this) {
                    // no data has been received for LIVEDATA_TIMEOUT seconds
                    if (lastMessageReceived.plusSeconds(LIVEDATA_TIMEOUT).isBefore(LocalTime.now())) {
                        logger.warn("Timeout exceeded for Live Data, trying to re-register");
                        reregisterForRealtimeApi();
                    }
                }
            }, LIVEDATA_TIMEOUT / 3, LIVEDATA_TIMEOUT / 3, TimeUnit.SECONDS);

            updateStatus(ThingStatus.ONLINE);
            retryCounter = 0;
        }
    }

    @Override
    public void connectionLost(@Nullable Throwable cause) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                cause != null ? cause.getMessage() : "No cause given");

        logger.warn("Lost connection to MQTT server. Cause: {}", cause != null ? cause.getMessage() : "No cause given");
        if (aliveChecker != null && !aliveChecker.isCancelled()) {
            aliveChecker.cancel(true);
        }

        reregisterForRealtimeApi();
    }

    @Override
    public void deliveryComplete(@Nullable IMqttDeliveryToken token) {
        // we don't publish any messages, so no need for this callback
    }

    @Override
    public void messageArrived(@Nullable String topic, @Nullable MqttMessage message) {
        try {
            int rate = Integer.parseInt(getThing().getConfiguration().get(CONFIG_RATE).toString());

            if (rate < 0) {
                rate = 60;
            }

            if (deviceSha256.equals(topic)) {
                lastMessageReceived = LocalTime.now();

                // live consumption message
                RealtimeDataMessage dataMessage = new Gson()
                        .fromJson(new String(message.getPayload(), StandardCharsets.UTF_8), RealtimeDataMessage.class);
                // this only gets the first message of DataMessages, because we are only subscribed to one single device
                // in the future, if a test account with multiple devices is available, this could be refactored for
                // efficiency
                List<DeviceValue> deviceValues = dataMessage.getItems().get(0).getValues();

                for (Map.Entry<String, ChannelDataSource> entry : channelToSourceMapping.entrySet()) {
                    Optional<DeviceValue> deviceValue = deviceValues.stream()
                            .filter(value -> value.getObis().equals(entry.getValue().getObisKey())).findFirst();
                    deviceValue.ifPresent(value -> addValueToList(entry.getKey(), value.getValue()));
                }

                // iterate through our value lists and check if we need to send out values to channels yet
                for (Map.Entry<String, List<Float>> entry : aggregatedValues.entrySet()) {
                    String channelName = entry.getKey();
                    List<Float> values = entry.getValue();

                    if (values.size() >= rate || firstMessageAfterInit) {
                        AggregationType aggType = channelToSourceMapping.get(channelName).getAggregationType();

                        double value;
                        // logger.warn("Aggregating values for channel {} as {}", channelName, aggType);
                        // if (logger.isTraceEnabled()) {
                        // logger.trace(String.format("Raw values are:"));
                        // logger.trace(Arrays.toString(values.toArray()));
                        // }
                        switch (aggType) {
                            case AVERAGE:
                                value = values.stream().mapToDouble(d -> d).average().getAsDouble();
                                break;
                            case MAX:
                                value = values.stream().mapToDouble(d -> d).max().getAsDouble();
                                break;
                            case LAST:
                                value = values.get(values.size() - 1).doubleValue();
                                break;
                            default:
                                value = 0.;
                                logger.warn("Unsupported Aggregation Type requested: {}", aggType.toString());
                        }
                        // logger.trace(String.format("Resulting value is %.2f", value));
                        updateState(channelName, new DecimalType(value));
                        values.clear();
                    }
                }
                firstMessageAfterInit = false;
            }
        } catch (Exception ex) {
            // safety net, because Exceptions in callbacks for MqttClient force disconnection
            logger.error("Exception in messageArrived handler: {}", ex.getMessage());
        }
    }

    public void addValueToList(String key, float value) {
        if (!this.aggregatedValues.containsKey(key)) {
            this.aggregatedValues.put(key, new ArrayList<Float>());
        }
        this.aggregatedValues.get(key).add(value);
    }
}
