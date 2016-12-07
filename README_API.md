##![Smart Cambridge logo](images/smart_cambridge_logo.jpg) RITA: Realtime Intelligent Traffic Analysis

# Part of the Smart Cambridge programme.

## Overview

The Rita platform provides an HTTP restful API for data, generally returning the data as
Json but also providing binary data (i.e. as originally received by Rita) if requested.

### /api/console/status

This returns the 'latest reported status' of each module running on the Rita platform. Note that if a module
silently dies then the last status will still be reported and it is the reponsibility of the *receiver* of this
data from the API to calculate how much time has passed since the status was reported (by subtracting the 'ts'
value from the current time) and acting upon that data appropriately.

For example, if the API returns a 'ts' value of 1476969294 for a given module status, and the current unix timestamp
is 1476969394, then it is 100 seconds since that status was originally reported.

Each module provides a 'status_red_seconds' (equiv. down)  and 'status_amber_seconds' (equiv warning) which indicate
reasonable thresholds after which an alarm is appropriate (as this may vary by module).

```
{
  "module_name":"console", // name of Rita module providing this API
  "module_id":"A",         // id of Rita module providing this API
  "status":[               // array of latest module status messages from currently running modules
             {
               "module_name":"rita", // name of Rita module with this status
               "module_id":"vix",    // id of Rita module with this status
               "status":"UP",        // current status, currently always "UP"
               "status_msg": "UP",   // (optional) status message which can be any text
               "status_amber_seconds":15, // how many seconds to allow the 'ts' to be aged before flagging as AMBER
               "status_red_seconds":25,   // how many seconds to allow the 'ts' to be aged before flagging as RED
               "ts":1476969294       // unix timestamp (seconds) when this status was received from this module
             },
             //... more individual module status records
           ]
}
```

### /api/dataserver/zone/list

Lists the zone_id's of the configured Zones.

Returns (but the properties per zone return will be expanded later):
```
{
  "module_name":"dataserver",
  "module_id":"vix",
  "request_data": {
     "zone_list": [
            {"zone_id":"madingley_road_in"},
            {"zone_id":"madingley_road_out"},
            {"zone_id":"histon_road_out"},
            {"zone_id":"newmarket_road_in"},
            {"zone_id":"huntingdon_road_in"},
            ...
            {"zone_id":"milton_road_out"}
  ]}}
```

### /api/dataserver/zone/transits/<zone_id>/YYYY/MM/DD

These are the duration (in seconds) of each vehicle 'completion' of its journey through the zone.

Each transit JsonObject was originally a message from a Zone, triggered by vehicle position information
coming in real time from the vehicles.  The Zone will send a "zone_completion" message when a vehicle
is seen to cross the 'finish line' of the Zone after previously crossing the 'start line' and remaining
within the Zone inbetween.

Note that the vehicles are sending their position data intermittently (typically every 30 seconds, but there
can be any arbitrary period between successive updates, even days...). So the 'ts_delta' concept is important
(see field below). This is the sum of the time differences of the two pairs of data points giving the
entry and the exit from the zone. With a reliable 30 seconds vehicle reporting interval, ideally the zone 'entry'
of the vehicle will be a pair of points 30 seconds apart, and the same will apply to the vehicle 'exit'. This
would be reported in the "zone_completion" message as ```"ts_delta": 60```, i.e. the sum of the entry and exit
pair differences. ts_delta is an important 'confidence factor' in the accuracy of the "duration" value.

Format of each 'transit':
```
{ "duration":114,                  // journey time across the zone in seconds
  "module_name":"zone",            // module_name of module sending original message
  "module_id":"madingley_road_in", // zone identifier
  "route_id":"SCCM-X5",            // route_id given by the vehicle
  "msg_type":"zone_completion",    // always "zone_completion" for a transit
  "ts_delta":80,                   // sum of the durations of the entry and exit vectors (see above)
  "vehicle_id":"14370",            // unique vehicle identifier
  "ts":1475299611                  // utc timestamp (seconds) when this transit completed
}
```

E.g.
```
{
  "module_name":"dataserver",
  "module_id":"vix",
  "transits":[
    {"duration":112,"module_id":"madingley_road_in","route_id":"","msg_type":"zone_completion","ts_delta":60,"module_name":"zone","vehicle_id":"402","ts":1475299617},
    {"duration":114,"module_id":"madingley_road_in","route_id":"SCCM-X5","msg_type":"zone_completion","ts_delta":80,"module_name":"zone","vehicle_id":"14370","ts":1475299611},
    {"duration":101,"module_id":"madingley_road_in","route_id":"SCCM-X5","msg_type":"zone_completion","ts_delta":60,"module_name":"zone","vehicle_id":"14372","ts":1475301290},
    {"duration":94,"module_id":"madingley_road_in","route_id":"SCCM-X5","msg_type":"zone_completion","ts_delta":80,"module_name":"zone","vehicle_id":"14369","ts":1475303028},
    {"duration":96,"module_id":"madingley_road_in","route_id":"","msg_type":"zone_completion","ts_delta":60,"module_name":"zone","vehicle_id":"414","ts":1475303032},
    {"duration":132,"module_id":"madingley_road_in","route_id":"SCCM-4","msg_type":"zone_completion","ts_delta":60,"module_name":"zone","vehicle_id":"14115","ts":1475304553},
    {"duration":157,"module_id":"madingley_road_in","route_id":"","msg_type":"zone_completion","ts_delta":120,"module_name":"zone","vehicle_id":"346","ts":1475362364}
]}
```

### /api/dataserver/zone/config/<zone_id>

This currently returns the complete vertx service configuration file the Zone was started with, which includes
the lat/log path defining the zone boundary.

Note that the 'start line' of the Zone is always between the first two points on the zone.path (index 0 and 1),
and the 'finish line' of the Zone is the line between path points 'zone.finish_index' and 'zone.finish_index+1'.
In the example below there are 4 zone.path points defining the boundary of the Zone (i.e. this is a quadrilateral)
which can be considered to have indices 0,1,2,3.  The start line is (as always) between 0..1, and the finish line
is between 2..3 (as defined by zone.finish_index).


```
{
  "module_name":"dataserver",
  "module_id":"vix",
  "request_data":
    {
      "main":"uk.ac.cam.tfc_server.zone.Zone",
      "options":
        {
          "config":
            {
              "module.name":"zone",
              "module.id":"madingley_road_in",
              "eb.system_status":"system_status",
              "eb.zone":"tfc.zone",
              "zone.center":{"lat":52.21132533651944,"lng":0.0969279289245284},
              "zone.zoom":15,
              "zone.path":[{"lat":52.212783911038585,"lng":0.08480072021484375},
                           {"lat":52.214335321429296,"lng":0.08514404296875},
                           {"lat":52.212257996914616,"lng":0.10570049285888672},
                           {"lat":52.21065392038191,"lng":0.10548591613769531}],
              "zone.finish_index":2,
              "zone.name":"Madingley Road IN",
              "zone.id":"madingley_road_in"
            }
        }
    }
}
```

### /api/dataserver/parking/config/<parking_id>

Returns the configuration parameters of the car park with that parking_id, e.g. for grand-arcade-car-park:
```
{
  "module_name":"dataserver",
  "module_id":"vix",
  "request_data":
  {
     "feed_id":"cam_park_local",
     "parking_id":"grand-arcade-car-park",
     "parking_name":"Grand Arcade",
     "latitude":52.2038,
     "longitude":0.1207,
     "capacity":890
  }
}
```
Note that the parking_id is the *default* value for a feed that provides occupancy data for this car park but
another can be specified at the time the occupancy data is requested.

### /api/dataserver/parking/list

Returns the full list of car parks in the system, with each entry being as the config above:
```
{
   "module_name":"dataserver",
   "module_id":"vix",
   "request_data":
     {
        "parking_list":
          [
             {
               "feed_id":"cam_park_local",
               "parking_id":"grafton-east-car-park",
               "parking_name":"Grafton East",
               "latitude":52.2072,
               "longitude":0.1348,
               "capacity":780
             },
             ...
          ]
     }
}
```

### /api/dataserver/parking/occupancy/<parking_id>?date=YYYY-MM-DD[&feed_id=<feed_id>]

Returns the occupancy data for the requested parking_id on given date. An optional feed_id
can be specified, otherwise the feed_id in the car park config file will be used.

Returns:
```
{
   "module_name":"dataserver",
   "module_id":"vix",
   "request_data":
     [
       {
         "filename":"1478822640_2016-11-11-00-04-00",
         "ts":1478822640,
         "spaces_occupied":63
         "area_id":"cam",
         "feed_id":"cam_park_local",
         "capacity":953,
         "module_id":"cam",
         "filepath":"2016/11/11",
         "msg_type":"feed_car_parks",
         "parking_id":"grand-arcade-car-park",
         "module_name":"feedmaker",
         "parking_name":"Grand Arcade",
       },
       ...
     ]
}
```

### /api/dataserver/feed/list

Provides the current list of feeds with configuration information for each.

Returns:
```
{
    "module_name":"dataserver",
    "module_id":"test",
    "request_data":
      {
        "feed_list":
          [
            {
              "feed_id":"cam_park_local",
              "feed_name":"Cambridge Local car parks",
              "feed_description":"Feed received giving car park occupancy data for Cambridge City local car parks"
            },
            ...
          ]
      }
}
```

### /api/dataserver/feed/config/<feed_id>

Provides configuration data for a given feed.

Returns:
```
{
    "module_name":"dataserver",
    "module_id":"test",
    "request_data":
      {
        "feed_id":"cam_park_rss",
        "feed_name":"Cambridge car parks incl P&R",
        "feed_description":"Car park occupancy data for Cambridge City local car parks plus P&R car parks"
      }
}
```

### /api/dataserver/feed/now/<feed_id>

Provides the LATEST JSON data for the requested feed.

Returns:
```
{
  "module_name":"dataserver",
  "module_id":"test",
  "request_data":<whatever JSON data was most recent for that feed>
}
```

E.g. for the parking occupancy feed 'cam_park_local', the API returns:
```
{
  "module_name":"dataserver",
  "module_id":"test",
  "request_data":{
    "module_name":"feedmaker",
    "module_id":"park_local_rss",
    "msg_type":"feed_car_parks",
    "feed_id":"cam_park_local",
    "filename":"1481111458_2016-12-07-11-50-58",
    "filepath":"2016/12/07",
    "ts":1481111458,
    "request_data":
      [
        {"parking_id":"grafton-east-car-park","spaces_capacity":780,"spaces_free":409,"spaces_occupied":371},
        {"parking_id":"grafton-west-car-park","spaces_capacity":280,"spaces_free":41,"spaces_occupied":239},
        {"parking_id":"grand-arcade-car-park","spaces_capacity":890,"spaces_free":108,"spaces_occupied":782},
        {"parking_id":"park-street-car-park","spaces_capacity":375,"spaces_free":108,"spaces_occupied":267},
        {"parking_id":"queen-anne-terrace-car-park","spaces_capacity":540,"spaces_free":84,"spaces_occupied":456}
      ]
  }
}
```
