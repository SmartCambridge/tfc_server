
GTFS Data format from Google

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

    
