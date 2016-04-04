# FeedHandler

FeedHandler is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

FeedHandler provides a http server that listens for http POSTs of
binary GTFS-realtime format data, which contain batches of vehicle
position records.

To preserve the data, FeedHandler immediately writes this binary data to the
file system in three places:
- a local cache directory as YYYY/MM/DD/&lt;filename&gt;.bin
- a binary archive directory as YYYY/MM/DD/&lt;filename&gt;.bin
- as a file in a "monitor" directory so it is available to trigger
other linux processes via inotifywait, as &lt;filename&gt;.bin, *deleting any
prior .bin files in that directory*

The filename is &lt;UTC TIMESTAMP&gt;\_YYYY-DD-MM-hh-mm-ss.bin where hh-mm-ss
is LOCAL time. The UTC timestamp provides a guaranteed ordering of the feeds
while the local time is often more useful for relative analysis (e.g.
congestion tends to correlate with local time, not UTC.

FeedHandler then parses the binary data (using the Google GTFS/protobuf library)
and 'publishes' the data to the eventbus as Json.

FeedHandler receives its configuration parameters (e.g. the eventbus address to
use for the feed messages) in its [Vertx](vertx.io) config().

FeedHandler also publishes regular (every 10 seconds) 'status=UP' messages to
the 'system_status' eventbus address to be interpreted by the Console.


## GTFS Data format from Google

https://developers.google.com/transit/gtfs-realtime/reference

```
header (FeedHeader)
    gtfs_realtime_version (String)
    incrementality (incrementality)
    timestamp (uint64)

entity (FeedEntity) (repeated)
    id (String)
    is_deleted (bool)
    trip_update (TripUpdate)
        trip (TripDescriptor)
            trip_id (String)
            route_id (String)
            direction_id (uint32) "EXPERIMENTAL"
            start_time (String) "e.g. 11:15:35"
            start_date (String) "YYYYMMDD"
            schedule_relationship (SheduleRelationship)

        vehicle (VehicleDescriptor)
            id (String)
            label (String)
            license_plate (String)

        stop_time_update (StopTimeUpdate)

        timestamp (uint64) = "POSIX UTC TIMESTAMP" in seconds since 1970-1-1
        delay (int32) = "SECONDS" positive or negative estimate

    vehicle (VehiclePosition)
        trip (TripDescriptor)
            trip_id (String)
            route_id (String)
            direction_id (uint32) "EXPERIMENTAL"
            start_time (String) "e.g. 11:15:35"
            start_date (String) "YYYYMMDD"
            schedule_relationship (SheduleRelationship)

        vehicle (VehicleDescriptor)
            id (String)
            label (String)
            licence_plate (String)

        position (Position)
            latitude (Float)
            longitude (Float)
            bearing (Float)
            odometer (double)
            speed (Float)

        current_stop_sequence (unit32)
        stop_id (String)
        current_status (VehicleStopStatus)

        timestamp (uint64) "POSIX UTC TIMESTAMP"
        congestion_level (CongestionLevel)
        
        occupancy_status (OccupancyStatus)

    alert (Alert)
```

The actual real-time position data received in the system in Cambridge contains a
small subset of these fields, and frankly the Google GTFS protobuf format is a
pain in the ass for most subsequent processing of the data.  For this reason
FeedHandler does archive the binary GTFS data exactly as received (so nothing is lost)
but the data is internally transmitted on the eventbus in a simplified format (see below).

## FeedHandler eventbus message format

Given the simplicity of the actual data received in the current system (in Cambridge),
FeedHandler flattens the subset of the received GTFS binary data into the following
Json format:

```
{  "filename":"1459761031_2016-04-04-10-10-31",
   "filepath":"2016/04/04",
   "timestamp":1459761030,
   "entities":[
        {  "received_timestamp":1459761031,
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
"2016/04/04/1459761031_2016-04-04-10-10-31.bin" by FeedHandler. The Unix timestamp is
in UTC, while the 2016/04/04 and 10-10-31 is local time.

Note that the batch feed has a "timestamp", from the original source, while the "filename"
refers to the time the batch feed was received by FeedHandler.

Each 'entity' is a position record for an individual vehicle which FeedHandler flattens
somewhat. For the 'entity', "timestamp" is that sent in the original GTFS data, i.e. the
time the data was transmitted by the vehicle. FeedHandler adds the "received_timestamp"
matching that of the batch feed, for convenience of processing individual position
records (because often to minimise errors you need to know whether a position record
is too old to be trusted).

Modules such as FeedCSV take the entire batch feed and store it in another format. In this
case it is helpful for the FeedCSV module to store the parsed data using the same filename
as FeedHandler used in its binary archive, so the files match up easily for future
analysis.

