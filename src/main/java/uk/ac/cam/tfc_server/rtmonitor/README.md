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

Note that the RTMonitor can be launched with a `RTMONITOR_KEY` (either from an environment
variable of that name, or a `rtmonitor.key` property in the Config) which will be used to
decrypt a token provided by the client in the "rt_connect" message.

## Concepts

RTMonitor can simultaneously 'monitor' multiple eventbus addresses and communicate all or a subset
of that data in real-time to multiple webpage subscribing 'clients'. There are a few concepts
and terminology involved that will help clarify the code.

### Monitor

This is the essential 'server' unit which is configured (via Config) to listen to eventbus events on
a given `address` and accept websocket connections from clients which can subsequently send `rt_subscribe`
and `rt_request` messages to establish a continuous subscription for data records or a one-off request for
current values respectively.

Each 'Monitor' is launched as a result of a `rtmonitor.monitors` entry in the RTMonitor json config file.  E.g.
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

            "rtmonitor.key":         "coffee",

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

Note the Monitor configuration fields:
* ```http.uri:``` This is the web address the Monitor will listen on to respond to clients
connecting via a websocket.
* ```address:``` This is the eventbus address the Monitor will subscribe to on behalf of its future
clients, providing the data records that the clients can subscribe to.
* ```records_array:``` It is common for the actual data records from the original source (e.g. a remote
sensor) to be embedded within a JsonArray property of the eventbus message.  If so, ```records_array```
is the name of that property. If the eventbus messages are coming from a FeedMaker, then the property will
usually be ```request_data``` as in the example above. This requirement arises because sensors (or controlling
servers) often send the data records in 'batches' (e.g. the sensor readings for the past 5 minutes) and in this case
the eventbus message will contain a 'batch' (i.e. JsonArray) of data records rather than a single one.
* ```record_index:``` If the data records have a defining 'key', e.g. if the records are sensor data then
typically each record will contain the 'id' of the sensor that transmitted the original data, then the property
in the data record that contains this key can be given in ```record_index```. For SiriVM bus position data, then
the original 'sensor' was a vehicle, and the unique vehicle identifier is given within the data record as the property
```VehicleRef``` as in the example above.

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

These are the messages RTMonitor will receive and process (an example of bus-position SiriVM data is given
below).

Note that it is common for a single eventbus message to contain *multiple* data records.  For
example a car park management system may transmit occupancy figures for multiple car parks in
a single message.  A SiriVM message from a bus company may contain the timestamped positions
of multiple buses.

Each eventbus message will typically have the format similar to this example:
```
{   "module_name":"feedmaker",
    "module_id":"A",
    "msg_type":"siri_vm_flat",
    "feed_id":"cloudamber_siri_vm",
    "filename":"1506931281.619_2017-10-02-09-01-21",
    "filepath":"2017/10/02",
    "ts":"1506931281.619",
    "request_data":[ { JsonObject content of a data record },
                     { JsonObject content of a data record },
                     ...
                   ]
}
```

### data records

This is the 'atomic' record of the sensor data, e.g. the timestamped position of a single
bus, or the timestamped occupancy of a single car park. The ```record_index``` property of the Monitor
config identifies an optional property in each data record (such as ```VehicleRef```) that contains the
data source reference identifier.  This allows RTMonitor to accumulate state for each sensor such as its most
recent data record.

### The data records sent to the subscribing clients

Much more detail on the way RTMonitor works is provided below, but here is the message format returned to
a client with a 'subscription' to data, as  periodic messages sent to the client via its
websocket with the format below:
```
{
    "msg_type": "rt_data",
    "request_id": "bus_stop_widget1_A",
    "request_data":[ { JsonObject content of a data record },
                     { JsonObject content of a data record },
                     ...
                   ]
}
```
Each client websocket message will be sent directly as a result of an incoming eventbus message containing data records that
successfully match the criteria given in the client subscription. The 'data record' elements of the property
```request_data``` are a subset of the data records contained in the incoming eventbus message.

It is possible for a client subscription to contain no filtering criteria (i.e. effectively a 'request all'), in which case
RTMonitor acts as an
eventbus address to websocket bridge for that client and every eventbus message on the monitored eventbus address will be
sent to the client unchanged, in its entirety.  Note that in this special case the websocket message, as an exact copy of the
eventbus message, contains the original FeedMaker (for example) metadata such as ```ts``` but does *not* contain the usual
RTMonitor metadata ```request_id``` and ```"msg_type": "rt_data"```.

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
the meaning of the actual sensor data but instead provides generally useful capabilities
to allow WebSocket access to eventbus messages.

Note that in this example (SiriVM data) the position data for multiple buses (say 10..100)
will arrive in a single transmission from the bus comany and hence in a single eventbus
message, but the complete 'map' of the positions of all buses in the city will depend on
the arrival of multiple messages over a period of time.

#### RTMonitor monitor config() properties `records_array` and `record_index`

Hence the *records\_array* config() parameter tells RTMonitor the name of the JsonArray property
that can hold multiple data records in the eventbus message.  In the example SiriVM data above,
the records are provided in a JsonArray property *request\_data*.

The ```record_index``` property defines the name of the 'primary key' *within* the data
records. E.g. in SiriVM bus position data the *VehicleRef* is the unique identifier of the bus,
so that property name is passed to the RTMonitor in its vertx config() *record\_index* property,
as shown in the example full vertx config() show at the top of this README.

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
its desire to connect by sending a Json message on the WebSocket:
```
{ "msg_type": "rt_connect",
  "client_data": { "rt_client_id": <String rt_client_id>,
                   "rt_client_name": <String rt_client_name>,
                   "rt_client_url": <String url of web page client>,
                   "rt_token": <String auth token>
                 }
}
```

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
This subscription will result in *all* data records from the monitor eventbus address being
sent to the client browser. If a subset of the data records is required (e.g. data records from
a single sensor) then filters can be applied (see section below).

The subscription can be deleted by sending an ```rt_unsubscribe``` message with the same
```request_id```:
```
{ "msg_type": "rt_unsubscribe",
  "request_id": "abc"
}
```

### Filters

On a *subscription* or *request* (see next section), the returned data can be *filtered*
via the ```filters``` property. This property is optional, and if missing then no filters
are applied to the data.

The behaviour of filters depends upon the ```records_array``` property of the monitor:
* if ```records_array``` is set, then the filters are applied to the data records expected
to be found within the JsonArray named in that property.
* if ```records_array``` is not specified for the monitor, then the *whole eventbus message*
is treated as the data record.

The ```filters``` property is always a JsonArray, with each element being a filter
condition. For a data record to pass through the filters, *all* the filters in the
```filters``` JsonArray must succeed for that record, i.e. the multiple filter
conditions are in a boolean 'AND' relationship.

Note that a boolean 'OR' relationship between filters can be acheived through the
use of multiple subscriptions.

```
{ "msg_type": "rt_subscribe",
  "request_id": "abc"
  "filters": [ { "test": "=", "key": "VehicleRef", "value": "CAMB-1018" } ]
}
```
In the example above, records will only be returned if the record contains a
property ```VehicleRef``` and the value of that property is ```CAMB-1018```.

As another example of use, with SiriVM bus position data the records include
```OriginRef``` and ```DestinationRef``` properties with the identifiers of the
starting and finishing bus stops for that particular vehicle journey. A
web page could filter on both of those properties to display all buses on
a particular route.

```
{ "msg_type": "rt_subscribe",
  "request_id": "A",
  "filters" : [
      { "test": "=", "key": "DestinationRef", "value": "0500CCITY544" },
      { "test": "=", "key": "OriginRef", "value": "0500CCITY517" }
  ]
}
```

The ```"test": "inside"``` filter will return records inside the clockwise polygon
provided a list of points.

```
{ "msg_type": "rt_request",
  "request_id": "A",
  "options": [ "latest_records" ],
  "filters": [
      { "test": "inside",
        "lat_key": "Latitude",
        "lng_key": "Longitude",
        "points": [
            {  "lat": 52.182240996245156, "lng": 0.11908739805221558 },
            {  "lat": 52.18318827670533, "lng": 0.14784067869186404 },
            {  "lat": 52.170293005240666, "lng": 0.14775484800338748 },
            {  "lat": 52.16671324666648, "lng": 0.12389391660690309 }
        ]
      }
  ]
}
```

### Requests

Requests can be similar to subscriptions, but receive the data immediately *once*
rather than as a continous stream of real-time updates.

A slightly subtle point is that RTMonitor necessarily provides the data fulfilling a *request*
immediately from some accumulated *state* of the monitored message feed. Currently this
includes:
* the latest message from the monitored eventbus address
* the previous message from teh monitored eventbus address
* the accumulates set of latest data records indexed by the property given in ```record\_index```
* similarly the accumulated set of 'previous' data records

For example:
```
{ "msg_type": "rt_request",
  "request_id": "abc"
}
```
This request will, without filtering, receive the latest message received from the eventbus. This
is actually a default value ```"latest_msg"``` for the options listed below.

The ```rt_request``` message can specify filters, exactly as with the subscriptions.  In this case the
filters will be applied to the data records found before the resultant subset is returned to the client
via the WebSocket.

### Options

The ```rt_request``` message can specify a JsonArray of ```"options"``` which determine the data actually
returned.  Choices include:
* ```"latest_msg"```: the latest monitored message received from the eventbus.
* ```"previous_msg"```: the penultimate monitored message received from the eventbus. Sometimes this is useful
where the client want to immediately produce a calculation based on the most recent *pair* of data records, e.g.
to display the spot velocity of a vehicle from a pair of position records. A common usage will be to
*request*  ```"options": [ "previous_msg", "latest_msg" ]``` and *subscribe* to the realtime feed
such that (for example) a velocity vector can be created immediately on the web page and then updated
with each new realtime data record.
* ```"latest_records"```: where the monitored feed has a ```"records_data"``` JsonArray and a
```"record_index"``` key for those data records, the RTMonitor can return the latest *data record*
for each value of that primary key.  These records may have been accumualated over multiple
evenbus messages.
* ```"previous_records"```: as ```"latest_records"``` above, except it will return the penultimate
data record for each primary key value.

## Overview of the Java classes within RTMonitor.java

### RTMonitor

The Java classes defined in RTMonitor.java pretty much mirror the concepts described more generally above.

### MonitorTable

The list of Monitors running.

RTMonitor first starts with an empty *MonitorTable* data structure, which will be initialized according to
the contents of the Vertx json config() file containing the parameters of each *Monitor* to be launched.
I.e. as with most tfc_server Verticles, a single RTMonitor instance is designed to run _multiple_
Monitors.  So after startup is completed, the MonitorTable data structure will be populated with a number
of Monitor objects.

### Monitor

Manages a complete set of eventbus subscriptions and connected clients.

Each *Monitor* object is designed to subscribe to an eventbus address and listen for data requests on
a websocket on a defined URL.  The definitions for these are given in the vertx config() file for the
RTMonitor. In addition to subscribing to the defined eventbus address, the Monitor will accumulate
some 'state' from the incoming messages, for example the most recent and previous messages, such that
these can be requested by clients directly without delay. If the Monitor is told (via vertx config()) the
defining 'key' of the incoming records, such as MonitoredVehicleRef for the SiriVM data, then the Monitor
can accumulate the most recent message from, in this example, each monitored vehicle.

### ClientTable

A list of connected clients.

Each Monitor starts with an empty *ClientTable* object, intended to hold data about each Client that
subsequently connects to request data (i.e. the websocket handle to be used to exchange messages, and
the parameters of the data subscriptions).

### Client

When a web page connects to the Monitor (via the websocket on the defined URL), a *Client* object is
created and added to the Clients list contained within that Monitor.  The Client object includes the handle
to the websocket, a unique ID allocated to the client, and initially contains an empty *Subscriptions*
object that will accumulate the active subscription data requests as they come in from the Client. Note
that all interactions between the RTMonitor and the connected web page are in the form of messages exchanged
via the websocket in an agreed Json format.

After connecting, a Client can be expected to send one or more formatted Json messages to
RTMonitor requesting data.
This data is implicitly to be data records from the eventbus address to which the Monitor is subscribing.
If the data request is a single request for existing data accumulated in the Monitor, then the results
can be immediately sent and that particular 'transaction' is completed.
If the data request is for an ongoing subscription to the eventbus messages, then a *Subscription* object
is created containing the subscription parameters which is added to the Client's Subscriptions list.

As a reminder from earlier, here is an example of a typical subscription (in this case for SiriVM bus
position data, but note that RTMonitor is essentially independent of the format of the actual data
records being requested).

```
{ "msg_type": "rt_subscribe",
  "request_id": "A",
  "filters" : [
      { "test": "=", "key": "DestinationRef", "value": "0500CCITY544" },
      { "test": "=", "key": "OriginRef", "value": "0500CCITY517" }
  ]
}
```

### Subscription

The *Subscription* object (in the Subscriptions list of the Client) contains a copy of the request
message (as a JsonObject) and a *Filters* object containing the list of filters defined in the request
(in this example the two DestinationRef=0500CCITY544 and OriginRef=0500CCITY517). Each filter is
(surprise) stored in a *Filter* object.

### Filters

A list of Filter objects.

### Filter

The *Filter* object includes (crucially) a 'test(data record)' method which returns true or false
depending upon the filter succeeding or failing.  The *Filters* object also contains a 'test(data record)'
methos that simply calls the same method for each of its Filter objects and returns true if they all succeed.
I.e. the filters in a given request are 'AND'ed together.

### RTToken

Holds a 'cached' version of the token used by a client making a connection, which will provide the
token in the `rt_token` property of the `rt_connect` message. The unencrypted token is a JSON object
that contains `origin`: the referring hostnames from which the client page should
have originally been served. The token may also limit the number of times it can be used in
connecting to the same RTMonitor (via a `uses` property)

The property `RTToken.client_token` holds the original (decrypted) JsonObject from the client and is
displayed on the client status page linked to from the basic `home` page provided by RTMonitor.

The (AES) decryption support for the tokens is provided in `util/RTCrypto.java`, while the encrypted
token is expected to be embedded in the 'client' web page by the `tfc_web` scripted web-page platform.

The `tfc_web` code to do this is in the `smartcambridge/rt_crypto.py` package which is called e.g. by
`tfc_web/transport/views.py` `map_real_time()`, producing:

```
var RTMONITOR_URI = 'https://smartcambridge.org/rtmonitor/real-time-data/';

// RTMonitor rt_connect client_data
var CLIENT_DATA = { rt_client_name: 'My client V'+VERSION,
                    rt_client_id: 'my_client',
                    rt_token: '2V5r1Rmp1OR5nLdzZwVgI2sZOZLgID0CJcOtl5Y+VbknqnPNwSvF/YTVW4tmIiE+G9G5t3KBhTFkg79gXwSnw3yGiPRt+9++E+7Vdsasm0UyQw6bV7c8L/k4PvT7la8MJ/C5q+9pFpGXeuaUyoyacqNh2BQ6ayOlQY/hu7vBG5jINngNhrfqrwbV3YMUYiqMYe/bxPsVuUVvpHAKWa2ypezzbwLwXG/bMeNH6HmiwQT1z0snIKS4nubW6oqvtPJI0u1UTAnfN17wmVBIT5JqeCw/iukKsMoC65ODN5AQ9wXFvrqwVKb6OPP/6S+TIBrARpvtK6I05pYRU2ho8G9EEAvdW6sJt2PeHoxoYrmV+MeXYeMyX++29LO0xnE7/d4s'
                  };
```

An unencrypted token may look like:
```
{
  "issuer" : "/transport/map/",
  "issued" : "2019-12-10T17:40:06.548461+00:00",
  "expires" : "2019-12-10T18:40:06.548461+00:00",
  "origin" : [ "https://tfc-app[1-5].cl.cam.ac.uk", "http://localhost" ],
  "uses" : "5"
}
```

For development purposes, a java `util/MakeToken <key>` utility is provided which uses `RTCrypto.java`
to encrypt a token input via standard input, e.g.
```
cat token_file.json | java -cp target/tfc_server-3.6.3-fat.jar MakeToken <key>
```

That `<key>` would need also be provided to RTMonitor via its `Config` `rtmonitor.key` parameter or an
environment variable `RTMONITOR_KEY`.

The encrypted token will need to be embedded in the client web page as the `rt_token` property of the
`CLIENT_DATA` provided to the `RTMonitor_API(CLIENT_DATA, RTMONITOR_URI)` object instantiation.
