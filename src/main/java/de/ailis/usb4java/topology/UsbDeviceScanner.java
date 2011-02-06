/*
 * Copyright (C) 2011 Klaus Reimer <k@ailis.de>
 * See LICENSE.txt for licensing information.
 */

package de.ailis.usb4java.topology;

import static de.ailis.usb4java.jni.USB.USB_CLASS_HUB;
import static de.ailis.usb4java.jni.USB.usb_find_busses;
import static de.ailis.usb4java.jni.USB.usb_find_devices;
import static de.ailis.usb4java.jni.USB.usb_get_busses;

import java.util.ArrayList;
import java.util.List;

import de.ailis.usb4java.Services;
import de.ailis.usb4java.jni.USB_Bus;
import de.ailis.usb4java.jni.USB_Device;
import de.ailis.usb4java.support.UsbLock;


/**
 * USB Device scanner.
 *
 * @author Klaus Reimer (k@ailis.de)
 */

public final class UsbDeviceScanner
{
    /** The scan interval in milliseconds. */
    private static final int DEFAULT_SCAN_INTERVAL = 500;

    /** The virtual USB root hub. */
    private final VirtualRootHub rootHub;

    /** If scanner already scanned for devices. */
    private boolean scanned = false;


    /**
     * Constructor.
     *
     * @param services
     *            The USB services.
     * @param rootHub
     *            The virtual USB root hub.
     */

    public UsbDeviceScanner(final Services services,
        final VirtualRootHub rootHub)
    {
        this.rootHub = rootHub;
    }


    /**
     * Scans for USB device connection changes.
     */

    public void scan()
    {
        UsbLock.acquire();
        try
        {
            final int bussesChanged = usb_find_busses();
            final int devicesChanged = usb_find_devices();
            if (bussesChanged + devicesChanged == 0) return;

            USB_Bus bus = usb_get_busses();
            final List<USB_Device> devices = new ArrayList<USB_Device>();
            while (bus != null)
            {
                final USB_Device device = bus.root_dev();
                if (device != null) devices.add(device);
                bus = bus.next();
            }
            updateHub(this.rootHub, devices.toArray(new USB_Device[devices
                .size()]));

            this.scanned = true;
        }
        finally
        {
            UsbLock.release();
        }
    }


    /**
     * Creates a new JSR-80 USB Device from the specified low-level device.
     *
     * @param device
     *            The low-level USB device
     * @return A UsbDevice or UsbHub object depending on the device type.
     */

    private LibUsbDevice createUsbDevice(final USB_Device device)
    {
        if (device.descriptor().bDeviceClass() == USB_CLASS_HUB)
        {
            return new LibUsbHub(device);
        }
        else
        {
            return new LibUsbNonHub(device);
        }
    }


    /**
     * Updates the specified hub ports with the specified devices.
     *
     * @param ports
     *            The hub ports to update.
     * @param devices
     *            The detected devices.
     */

    private void updateHub(final UsbPorts<LibUsbPort, LibUsbDevice> ports,
        final USB_Device[] devices)
    {
        final List<LibUsbDevice> oldDevices = ports.getAttachedUsbDevices();
        final List<LibUsbDevice> newDevices = new ArrayList<LibUsbDevice>(
            devices.length);
        for (final USB_Device dev : devices)
        {
            if (dev == null) continue;
            final LibUsbDevice device = createUsbDevice(dev);
            newDevices.add(device);

            // Update existing devices
            if (oldDevices.contains(device))
            {
                if (device.isUsbHub())
                {
                    final LibUsbHub hub = (LibUsbHub) oldDevices.get(oldDevices
                        .indexOf(device));
                    updateHub(hub, dev.children());
                }
            }

            // Add new devices
            else
            {
                ports.connectUsbDevice(device);
                if (device.isUsbHub())
                    updateHub((LibUsbHub) device, dev.children());
            }
        }

        // Disconnect old devices
        for (final LibUsbDevice device : oldDevices)
        {
            if (!newDevices.contains(device))
                ports.disconnectUsbDevice(device);
        }
    }


    /**
     * Starts scanning in the background.
     */

    public void start()
    {
        final Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    try
                    {
                        Thread.sleep(DEFAULT_SCAN_INTERVAL);
                    }
                    catch (final InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    scan();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }


    /**
     * Scans for devices but only if this was not already done.
     */

    public void firstScan()
    {
        if (!this.scanned) scan();
    }
}