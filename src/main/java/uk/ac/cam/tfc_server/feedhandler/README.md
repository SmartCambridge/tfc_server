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

    
