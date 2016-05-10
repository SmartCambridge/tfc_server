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

ZONE_COMPLETION:
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_completion",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of calculated zone exit time
  "duration": 120 // number of seconds it took this vehicle to transit this zone
}
```

ZONE_START (vehicle entered zone via start line)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_start",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of calculated zone entry time
}
```

ZONE_ENTRY (vehicle entered zone but not via start line)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_entry",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of 1st position record inside zone
}
```

ZONE_EXIT (vehicle left the zone but without crossing start or finish lines)
```
{ "module_name": "zone" // typically "zone",
  "module_id": "madingley_road_in", // id of zone, issued when Zone was started
  "msg_type": "zone_exit",
  "vehicle_id": "abc77", // vehicle_id received by Zone for vehicle that has just completed transit
  "route_id": "CA-4", // as given in the latest position report from vehicle, may be ""
  "ts": 1462886995, // unix UTC timestamp of 1st position record outside zone
}
```

