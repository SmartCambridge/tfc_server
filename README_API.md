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
