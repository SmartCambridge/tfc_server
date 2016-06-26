## [RITA](https://github.com/ijl20/tfc_server) &gt; MESSAGES

These are the various message formats flowing between the modules

### FeedHandler

FeedHandler flattens the subset of the received GTFS binary data into the following
Json format:

```
{  "filename":"1459762951_2016-04-04-10-42-31",
   "filepath":"2016/04/04",
   "timestamp":1459762950,
   "entities":[
        {   "received_timestamp":1459762951,
            "vehicle_id":"17",
            "label":"WP-WD411 Y173",
            "latitude":52.24946,
            "longitude":-0.11835587,
            "bearing":330.0,
            "timestamp":1459762922,
            "trip_id":"1103251-20160329-22000909",
            "route_id":"WP-1A",
            "current_stop_sequence":17,
            "stop_id":"10190"
        },
        {  "received_timestamp":1459762951,
           "vehicle_id":"13",
           "label":"SCFensOVD-15657",
           "latitude":52.307323,
           "longitude":-0.01364167,
           "bearing":92.0,
           "timestamp":1459761023
        },
        ...
   ]
}
```
In the (real) example above, the positions record batch was written to a file called
"2016/04/04/1459762951_2016-04-04-10-42-31.bin" by FeedHandler. The Unix timestamp is
in UTC, while the 2016/04/04 and 10-42-31 is local time.

A second 'entity' (i.e. position record) is included, showing that some fields may
be omitted.

Note that the batch feed has a "timestamp", from the original source, while the "filename"
refers to the time the batch feed was received by FeedHandler.

Each 'entity' is a position record for an individual vehicle which FeedHandler flattens
somewhat. For the 'entity', "timestamp" is that sent in the original GTFS data, i.e. the
time the data was transmitted by the vehicle. FeedHandler adds the "received_timestamp"
matching that of the batch feed, for convenience of processing individual position
records (because often to minimise errors you need to know whether a position record
is too old to be trusted).

### Zone

Zones broadcast their 'zone_completion', "zone_start" etc messages on the 'zone.address' provided in
their configuration on startup.

Note that each message includes a 'ts' value (i.e. TIMESTAMP of the event recorded) and also
a 'ts_delta' value which is the DURATION between adjacent realtime datapoints during which the
event occurred. E.g. when a vehicle crosses a start line this will be identified by a datapoint
before the startline, followed by another datapoint, between which the calculated vector is seen
to cross the start line. The 'ts_delta' value is how many seconds occurred between these data points
and can be used as a 'confidence factor' in the calculated event time.

#### ZONE_COMPLETION:
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_completion",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of calculated zone exit time
  "ts_delta": =(start_ts_delta + finish_ts_delta), // 'confidence' factor (sum of entry + exit vector durations)
  "duration": 120 // number of seconds it took this vehicle to transit this zone
}
```

#### ZONE_START (vehicle entered zone via start line)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_start",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of calculated zone entry time
  "ts_delta": =(ts - prev_ts) // duration of entry vector (in seconds)
}
```

#### ZONE_ENTRY (vehicle entered zone but not via start line)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_entry",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of 1st position record inside zone
  "ts_delta": =(ts - prev_ts) // duration of entry vector (in seconds)
}
```

#### ZONE_EXIT (vehicle left the zone but without crossing start or finish lines)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_exit",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of 1st position record outside zone
  "ts_delta": =(ts - prev_ts) // duration of exit vector (in seconds)
}
```

#### ZONE_UPDATE
When a ZONE_UPDATE_REQUEST message is received (from Rita), Zone sends the history of prior messages
```
{ "module_name": MODULE_NAME, // zone
  "module_id", MODULE_ID,    // e.g. madingley_road_in
  "msg_type", Constants.ZONE_UPDATE,
  "msgs", [ <zone message>, <zone message> ... ]
}
```

#### ZONE_INFO
When a ZONE_INFO_REQUEST message is received (from Rita), Zone sends the zone config parameters such
as the lat/longs of the bounding polygon.
```
{ "module_name": MODULE_NAME, // zone
  "module_id":   MODULE_ID,    // e.g. madingley_road_in
  "msg_type":    Constants.ZONE_INFO,
  "center":      from config(),
  "finish_index": from config(),
  "zoom":        from config(),
  "path":        from config()
}
```

### Rita

ZONE_INFO_REQUEST sent on EventBus address given in config "eb.manager".

This message is sent on the manager eventbus address by Rita 'on behalf' of a web client
which is about to display the zone (i.e. zone_map.hbs) and requests the zone parameters
via its web socket to Rita.

A 'Zone' module will reply to this message with a ZONE_INFO messsage, giving the Zone
parameters, which will be forwarded on the socket to the web client which can then, for
example, draw the zone on a map.

```
{ "module_name": "rita"     // i.e. module name SENDING this request
  "module_id": "cambridge", // id of the current instance of Rita SENDING this request
  "to_module_name": "zone", // module name this manager message is being SENT to
  "to_moduler_id: "madingely_road_in", // the id of any particular zone
  "zone.address": "tfc.zone.cambridge.madingley_road_in", // e.g. whatever eventbus address Rita
                                                          // wants the response sent to.
  "msg_type": Constants.ZONE_INFO_REQUEST
}
```

ZONE_UPDATE_REQUEST sent on EventBus address given in config "eb.manager".

This message is sent on the manager eventbus address by Rita 'on behalf' of a web client
to retrieve all the Zone messages in the Zone buffer, e.g. to catch up on the messages
sent so far today.

A 'Zone' module will reply to this message with the Zone messages, which will be
forwarded on the socket to the web client.

The web client will then be able to update e.g. a plot (i.e. zone_plot.hbs) with all the
journey time datapoints so far today, before continuing to update the plot in real time.

```
{ "module_name": "rita"     // i.e. module name SENDING this request
  "module_id": "cambridge", // id of the current instance of Rita SENDING this request
  "to_module_name": "zone", // module name this manager message is being SENT to
  "to_moduler_id: "madingely_road_in", // the id of any particular zone
  "zone.address": "tfc.zone.cambridge.madingley_road_in", // e.g. whatever eventbus address Rita
                                                          // wants the response sent to.
  "msg_type": Constants.ZONE_UPDATE_REQUEST
}
```
