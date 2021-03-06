/**
 *  Tyco Door/Window Sensor
 *
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.json.JsonOutput
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus

metadata {
	definition (name: "Tuya Open/Closed Sensor", namespace: "tknboy", author: "Tae Kim") {
		capability "Battery"
		capability "Configuration"
		capability "Contact Sensor"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"
        fingerprint inClusters: "0000 0001 0003 0500", outClusters: "0003", manufacturer: "TUYATEC-ydcwf5m2", model: "RH3001", deviceJoinName: "Tuya Open/Closed Sensor"
	}

	simulator {

	}

	tiles(scale: 2) {
		multiAttributeTile(name:"contact", type: "generic", width: 6, height: 4){
			tileAttribute ("device.contact", key: "PRIMARY_CONTROL") {
				attributeState("open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#e86d13")
				attributeState("closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#00A0DC")
			}
		}
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}

       		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
		}

		main (["contact"])
		details(["contact","battery","refresh","configure"])
	}
}

def parse(String description) {
	log.debug "description: $description"

	Map map = [:]
	if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
    else if (description?.startsWith('zone status')) {
    	map = parseIasMessage(description)
    }

	log.debug "Parse returned $map"
	def result = map ? createEvent(map) : null

    if (description?.startsWith('enroll request')) {
        List cmds = zigbee.enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }
    return result
}

private Map parseCatchAllMessage(String description) {
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    if (shouldProcessMessage(cluster)) {
        switch(cluster.clusterId) {
			case 0x0500:
				Map descMap = zigbee.parseDescriptionAsMap(description)
				// someone who understands Zigbee better than me should refactor this whole DTH to bring it up to date
				if (descMap?.attrInt == 0x0002) {
                    def zs = new ZoneStatus(zigbee.convertToInt(descMap.value, 16))
                    resultMap = getContactResult(zs.isAlarm1Set() ? "open" : "closed")
                }
				break
            case 0x0001:
            	resultMap = getBatteryResult(cluster.data.last())
                break

        }
    }

    return resultMap
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    boolean ignoredMessage = cluster.profileId != 0x0104 ||
        cluster.command == 0x0B ||
        cluster.command == 0x07 ||
        (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
    return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	log.debug "Desc Map: $descMap"
    Map resultMap = [:]
    if (descMap.cluster == "0001" && descMap.attrId == "0020") {
		resultMap = getBatteryResult(zigbee.convertHexToInt(descMap.value))
        log.debug "getBatteryResult result : ${resultMap}"
	}

	return resultMap
}


private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)

	return zs.isAlarm1Set() ? getContactResult('open') : getContactResult('closed')
}


private Map getBatteryResult(rawValue) {
	log.debug "Battery ${rawValue}"
	def linkText = getLinkText(device)

    def result = [:]

	if (!(rawValue == 0 || rawValue == 255)) {
		def volts = rawValue / 10
		def minVolts = 2.1
		def maxVolts = 3.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
		def roundedPct = Math.round(pct * 100)
		result.value = Math.min(100, roundedPct)
		result.descriptionText = "${linkText} battery was ${result.value}%"
		result.name = 'battery'
        return result
        //log.debug "${result}"
	}

	return result
}


private Map getContactResult(value) {
	log.debug 'Contact Status'
	def linkText = getLinkText(device)
	def descriptionText = "${linkText} was ${value == 'open' ? 'opened' : 'closed'}"
	return [
		name: 'contact',
		value: value,
		descriptionText: descriptionText
	]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS)
}

def refresh()
{
	log.debug "Refreshing Battery"
	def refreshCmds = [

        //"st rattr 0x${device.deviceNetworkId} 1 0x402 0", "delay 200",
		"st rattr 0x${device.deviceNetworkId} 1 1 0x20"

	]

	return refreshCmds + zigbee.enrollResponse()
}
def installed() {
    sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
}
def configure() {
	// Device-Watch allows 2 check-in misses from device
	sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])

	String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
		log.debug "Configuring Reporting, IAS CIE, and Bindings."
	def enrollCmds = [
    	"delay 1000",

		"zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
		"send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        //"raw 0x500 {01 23 00 00 00}", "delay 200",
        //"send 0x${device.deviceNetworkId} 1 1", "delay 1500",
	]
    return enrollCmds + zigbee.batteryConfig() + refresh() // send refresh cmds as part of config
}

private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
    reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
    int i = 0;
    int j = array.length - 1;
    byte tmp;
    while (j > i) {
        tmp = array[j];
        array[j] = array[i];
        array[i] = tmp;
        j--;
        i++;
    }
    return array
}