package org.openhab.binding.enera.internal.discovery;

import static org.openhab.binding.enera.internal.EneraBindingConstants.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openhab.binding.enera.internal.handler.EneraAccountHandler;
import org.openhab.binding.enera.internal.model.EneraDevice;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;

/**
 * EneraDeviceDiscoveryService
 */
public class EneraDeviceDiscoveryService extends AbstractDiscoveryService {
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_DEVICE));

    private EneraAccountHandler eneraAccountHandler;

    public EneraDeviceDiscoveryService(EneraAccountHandler eneraAccountHandler) {
        super(SUPPORTED_THING_TYPES, 0);
        this.eneraAccountHandler = eneraAccountHandler;
    }

    @Override
    public void startScan() {
        List<EneraDevice> deviceList = this.eneraAccountHandler.getAccountData().getDevices();

        ThingUID bridgeUID = eneraAccountHandler.getThing().getUID();

        for (EneraDevice d : deviceList) {
            // take the last number group from the ID (which is a GUID))
            ThingUID thingUID = new ThingUID(THING_TYPE_DEVICE, bridgeUID,
                    d.getId().substring(d.getId().length() - 12, d.getId().length()));

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROPERTY_ID, d.getId());
            properties.put(PROPERTY_NAME, d.getName());
            properties.put(PROPERTY_BRAND, d.getBrand());
            properties.put(PROPERTY_METER_ID, d.getMeterId());
            properties.put(PROPERTY_SERIAL, d.getSerial());
            properties.put(PROPERTY_REGISTERED_AT, d.getRegisteredAt());

            DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withThingType(THING_TYPE_DEVICE)
                    .withBridge(bridgeUID).withLabel("Enera - " + d.getName()).withProperties(properties).build();
            thingDiscovered(discoveryResult);
        }

        stopScan();
    }

    @Override
    public void stopScan() {
        super.stopScan();
    }
}
