/**
 *  Garadget MQTT device
 *
 *  Author
 *   - ivan.kunz@gmail.com
 *
 *  Copyright 2018
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

definition(
        name: "Garadget MQTT",
        namespace: "thecrazymonkey",
        author: "Ivan Kunz",
        description: "Garadget Integration using MQTT",
        category: "My Apps",
        iconUrl:   "https://dl.dropboxusercontent.com/s/lkrub180btbltm8/garadget_128.png",
        iconX2Url: "https://dl.dropboxusercontent.com/s/w8tvaedewwq56kr/garadget_256.png",
        iconX3Url: "https://dl.dropboxusercontent.com/s/5hiec37e0y5py06/garadget_512.png")


        preferences {
            page(name: "deviceDiscovery", title: "Garadget MQTT Device Setup", content: "deviceDiscovery")
        }

def getSearchTarget(){
    return "urn:thecrazymonkey-com:device:GaradgetMQTT:1";
}

def deviceDiscovery() {
    log.debug "╚═══════════════════════════════════════════════════════════════════════════════════════════════════"

    def options = [:]
    def devices = getVerifiedDevices()
    devices.each {
        def key = it.value.mac+it.value.deviceAddress
        def value = "GaradgetMQTT ${it.value.ssdpUSN.split(':')[1][-3..-1]}" //it.value.name ?: "Default"
        options["${key}"] = value
        log.debug "║ ★ ${it.value.ssdpUSN} @ ${it.value.networkAddress}:${it.value.deviceAddress} (${it.value.mac}${it.value.deviceAddress})"
    }
    if(devices.size() == 0)
        log.debug "║ [no devices are verified]"
    log.debug "║ Verified devices: "
    log.debug "║ "

    ssdpSubscribe()
    //subscribeNetworkEvents()
    ssdpDiscover()
    verifyDevices()

    log.debug "╔════PAGE: DEVICE DISCOVERY════════════════════════════════════════════════════════════════════════════"

    return dynamicPage(name: "deviceDiscovery", title: "Starting Discover", nextPage: "", refreshInterval: 5, install: true, uninstall: true) {
        section("Please wait while we discover your GaradgetMQTT.  Please make sure the GaradgetMQTT Service is running. \r\n\r\nDiscovery can take five minutes or more, so sit back and relax! Select your device below once discovered.") {
            input "selectedDevices", "enum", required: true, title: "Select Devices (${options.size() ?: 0} found)", multiple: true, options: options
        }
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"

    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    unsubscribe()
    initialize()
}

def initialize() {
    unsubscribe()
    unschedule()

    ssdpSubscribe()
    //subscribeNetworkEvents()

    if (selectedDevices) {
        log.debug "Adding selected devices from SSDP discovery..."
        addDevices()
    }


    runEvery5Minutes("ssdpDiscover")
    log.debug "Done with initialize."
}

void ssdpDiscover() {
    log.debug "║ 2. Searching for ${searchTarget}"
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery ${searchTarget}", physicalgraph.device.Protocol.LAN))
}

void ssdpSubscribe() {
    log.debug "║ 1. Subscribing to events: ssdpTerm.${searchTarget}"
    subscribe(location, "ssdpTerm.${searchTarget}", ssdpHandler)
}

Map verifiedDevices() {
    def devices = getVerifiedDevices()
    def map = [:]
    devices.each {
        def key = it.value.mac+it.value.deviceAddress
        def value = it.value.name ?: "Wink Relay ${it.value.ssdpUSN.split(':')[1][-3..-1]}"
        map["${key}"] = value
    }
    map
}

void verifyDevices() {
    log.debug "║ 3. Verifying all devices which are not yet verified..."
    def devices = getDevices().findAll { it?.value?.verified != true }
    devices.each {
        int port = convertHexToInt(it.value.deviceAddress)
        String ip = convertHexToIP(it.value.networkAddress)
        String host = "${ip}:${port}"
        log.debug "--☆ Verifying device ${it.value.mac}${it.value.deviceAddress} at ${host}${it.value.ssdpPath}"
        sendHubCommand(new physicalgraph.device.HubAction("""GET ${it.value.ssdpPath} HTTP/1.1\r\nHOST: $host\r\n\r\n""", physicalgraph.device.Protocol.LAN, host, [callback: deviceDescriptionHandler]))
    }
}

def getVerifiedDevices() {
    getDevices().findAll{ it.value.verified == true }
}

def getDevices() {
    if (!state.devices) {
        state.devices = [:]
    }
    state.devices
}

def addDevices() {
    def devices = getDevices()

    selectedDevices.each { dni ->
        def selectedDevice = devices.find { it.value.mac+it.value.deviceAddress == dni }
        def d
        if (selectedDevice) {
            d = getChildDevices()?.find {
                it.deviceNetworkId == selectedDevice.value.mac+selectedDevice.value.deviceAddress
            }
        }

        if (!d) {
            log.debug "Creating Garadget MQTT Device with dni: ${selectedDevice.value.mac}${selectedDevice.value.deviceAddress}"
            addChildDevice("thecrazymonkey", "Garadget MQTT", selectedDevice.value.mac+selectedDevice.value.deviceAddress, selectedDevice?.value.hub, [
                    "label": selectedDevice?.value?.name ?: "Garadget MQTT",
                    "data": [
                            "mac": selectedDevice.value.mac,
                            "ip": selectedDevice.value.networkAddress,
                            "port": selectedDevice.value.deviceAddress
                    ]
            ])
        }
    }
}

def ssdpHandler(evt) {
    def description = evt.description
    def hub = evt?.hubId

    def parsedEvent = parseLanMessage(description)
    parsedEvent << ["hub":hub]

    log.debug "---╚═══════════════════════════════════════════════════════════════════════════════════════════════════"
    //log.debug "---║ RAW PARSED EVENT: $parsedEvent"

    def devices = getDevices()
    devices.each {
        def star = it.value.verified ? "★" : "☆";
        log.debug "---║ > ${star} ${it.value.ssdpUSN} @ ${it.value.networkAddress}:${it.value.deviceAddress} (${it.value.mac}${it.value.deviceAddress})"
    }
    log.debug "---║ Devices at start of ssdpHandler: "
    String ssdpUSN = parsedEvent.ssdpUSN.toString()
    if (devices."${ssdpUSN}") {
        def d = devices."${ssdpUSN}"
        if (d.networkAddress != parsedEvent.networkAddress || d.deviceAddress != parsedEvent.deviceAddress) {
            d.networkAddress = parsedEvent.networkAddress
            d.deviceAddress = parsedEvent.deviceAddress
            def child = getChildDevice(parsedEvent.mac+parsedEvent.deviceAddress)
            if (child) {
                child.sync(parsedEvent.networkAddress, parsedEvent.deviceAddress)
            }
        }
    } else {
        log.debug "---║ ☆ Adding ${ssdpUSN} to devices short list"
        devices << ["${ssdpUSN}": parsedEvent]
    }
    log.debug "---╔════SSDP HANDLER════════════════════════════════════════════════════════════════════════════════════"
}

void deviceDescriptionHandler(physicalgraph.device.HubResponse hubResponse) {
    log.debug "---╚═══════════════════════════════════════════════════════════════════════════════════════════════════"
    def body = hubResponse.xml
    def devices = getDevices()
    log.debug "---║ Got HTTP response for ${body.device.UDN}"
    def device = devices.find { it?.key?.contains(body?.device?.UDN?.text()) }
    if (device) {
        log.debug "---║ Found device in our short list: ${body.device.UDN} - marking VERIFIED★"
        device.value << [name: body?.device?.friendlyName?.text(), model:body?.device?.modelName?.text(), serialNumber:body?.device?.serialNumber?.text(), verified: true]
    }
    log.debug "---╔════DEVICE DESCRIPTION HANDLER═════════════════════════════════════════════════════════════════════════"
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}