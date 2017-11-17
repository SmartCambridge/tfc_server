# [Platform](https://github.com/ijl20/tfc_server) &gt; RTMonitor

RTMonitor, as in Real Time Monitor, is part of the Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

RTMonitor allows client web pages to issue subscriptions to eventbus data and
thus receive updates via websockets.

RTMonitor maintains the *state* of a given eventbus message feed, such that a
subscription to (say) real-time bus data can push enriched data each time a 
relevant data message appears on the eventbus. In this example, most simply, it can push both the
previous data message for the same bus as well as the newly arrived data message.

## Concepts

RTMonitor can simultaneously 'monitor' multiple eventbus addresses and communicate all or a subset
of that data in real-time to multiple webpage subscribing 'clients'. There are a few concepts
and terminology involved that will help clarify the code.

#### Monitor

Each 'Monitor' is launched as a result of an entry in the RTMonitor json config file.  E.g.
```
{    
    "main":    "uk.ac.cam.tfc_server.rtmonitor.RTMonitor",
    "options":
        { "config":
          {

            "module.name":           "rtmonitor",
            "module.id":             "test",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",
              
            "rtmonitor.log_level":   1,

            "rtmonitor.http.port":   8099,

            "rtmonitor.monitors": [ {
                                      "http.uri":   "/rtmonitor/sirivm",
                                      "address":    "tfc.feedmaker.cloudamber.sirivm",
                                      "records_array": "request_data",
                                      "record_index": "VehicleRef"
                                    }
                                  ]
          }
        }
}
```
In the example above, one Monitor is launched which will listen to the eventbus address
tfc.feedmaker.cloudamber.sirivm.  It will also listen for WebSocket connect requests coming in
on port 8099, uri /rtmonitor/sirivm.  In our case we use nginx to allow those requests to 
actually come in on port 80.

This monitor configuration above also includes 'records\_array' and 'record\_index' definitions
pertaining to the format of the expected eventbus messages.  More on that below.

### eventbus message

Periodically (typically asynchronously on an event-driven basis) a sensor 'station' (like a 
bus or an air quality sensor) will transmit its data to the Adaptive City Platform.  That data
will be received by a feed handler (e.g. the ACP module *FeedMaker*) and broadcast on the
eventbus as a Json-format message.

These are the messages RTMonitor will receive and process.

Note that it is common for a single eventbus message to contain *multiple* data records.  For
example a car park management system may transmit occupancy figures for multiple car parks in
a single message.  A SiriVM message from a bus company may contain the timestamped positions
of multiple buses.

### data records

This is the 'atomic' record of the sensor data, e.g. the timestamped position of a single
bus, or the timestamped occupancy of a single car park.

### records\_array and record\_index eventbus message attributes

This is best explained with an example using the Adaptive City Platform Json-format SiriVM
realtime vehicle position messages.  This data is actually fairly typical for realtime 
'sensor' messages in that each eventbus message can contain multiple 'sensor' messages (in
this case the realtime position of buses) and each sensor 'record' relates to a sensor with
a given 'identifier' for a data reading that occured at a particular place and time.

```
{  "module_name":"feedmaker",
    "module_id":"test",
    "msg_type":"siri_vm_flat",
    "feed_id":"cloudamber_siri_vm",
    "filename":"1506931281.619_2017-10-02-09-01-21",
    "filepath":"2017/10/02",
    "ts":"1506931281.619",
    "request_data":[ { "RecordedAtTime":"2017-09-29T09:41:22+01:00",
                       "ValidUntilTime":"2017-09-29T09:41:22+01:00",
                       "VehicleMonitoringRef":"SCNH-18157",
                       "LineRef":"24",
                       "DirectionRef":"OUTBOUND",
                       "DataFrameRef":"1",
                       "DatedVehicleJourneyRef":"2001",
                       "PublishedLineName":"24",
                       "OperatorRef":"SCNH",
                       "OriginRef":"0590PQG15",
                       "OriginName":"Queensgate Bay 15",
                       "DestinationRef":"300000269M",
                       "DestinationName":"Old Town Hall",
                       "OriginAimedDepartureTime":"2017-09-29T09:10:00+01:00",
                       "Monitored":"true",
                       "InPanic":"0",
                       "Longitude":"-0.3953570",
                       "Latitude":"52.5305557",
                       "Bearing":"186",
                       "Delay":"-PT1M41S",
                       "VehicleRef":"SCNH-18157"
                     },``
                       ...
                   ]
}
```
Note that RTMonitor is intended to be general-purpose, i.e. it cannot be expected to know
the meaning of the actual sensor data but instead provides generally useful capabilties
to allow WebSocket access to eventbus messages.

Note that in this example (SiriVM data) the position data for multiple buses (say 10..100)
will arrive in a single transmission from the bus comany and hence in a single eventbus
message, but the complete 'map' of the positions of all buses in the city will depend on
the arrival of multiple messages over a period of time.

#### RTMonitor monitor config() property records\_data

Hence the *records\_data* config() parameter tells RTMonitor the name of the JsonArray property
that can hold multiple data records in the eventbus message.  In the example SiriVM data above,
the records are provided in a JsonArray property *request\_data*.

#### RTMonitor monitor config() property record\_index

*Within* the data records, one property may be considered the 'primary key'.  I.e. in the 
SiriVM bus position data the *VehicleRef* is the unique identifier of the bus, so that property
name is passed to the RTMonitor in its vertx config() *record\_index* property as shown above. 

This concept is obviously important if you want to do some types of analysis across multiple
data records, e.g. display the path taken by an individual bus.

The fact that the record 'primary key' is defined in the monitor config() allows RTMonitor to
accumulate 'state' information relevant to that primary key that would be difficult to do otherwise.
For example RTMonitor can respond to a request for the latest positions of all the buses.  RTMonitor
supports this by maintaining a Hashmap of latest records with the *record\_index* as the key.

### Clients

The Adaptive City Platform may server a web page that then opens a WebSocket connection to the
URI defined in the RTMonitor (in the example config() given, this would be ```/rtmonitor/sirivm```.

Within RTMonitor, the connecting browser page is considered a *Client*. The client confirms
its desire to connect by sending a Json message on the WebSocket ```{ "msg_type": "rt_connect" }```.

###  Subscriptions

The client can request a real-time subscription to data arriving on the eventbus.  Note the 
client does *not* specify the eventbus address - this is specified in the RTMonitor config().

An example subcription could be achieved by the client sending a Json-format message on the
websocket on the defined URI:
```
{ "msg_type": "rt_subscribe",
  "request_id": "abc"
}
```

### Filters


### Requests


### Options

