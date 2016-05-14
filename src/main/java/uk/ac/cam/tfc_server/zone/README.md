##  [RITA](https://github.com/ijl20/tfc_server) &gt; Zone

Zone is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

A zone is an area of arbitrary shape, typically a segment of some route such as
a rectangle surrounding a length of road, such that vehicles can be monitored within it. The
Zone can publish messages giving updated status of the traffic flow within the Zone. The idea is
that other agents in the system (such as an agent representing a bus route) can subscribe to
these zone messages and update their status (and alert travellers) if there are issues on the
route likely to impact future arrival times.

Zone receives a feed of position records
(typically from a [FeedHandler](src/main/java/uk/ac/cam/tfc_server/feedhandler), or
FeedComposer) and uses geometric functions to detect any vehicle entering or leaving the zone.

Zone has vertx [config()](http://vertx.io/blog/vert-x-application-configuration/)
parameters that give the lat/long coordinates of each point of the
polygon defining perimeter of the zone (path[0]..path[n]). The zone always has a startline
between path[0]..path[1], and another config() parameter (finish_index) gives the first of
the consecutive pair of points defining a finishline. This allows the Zone to accumulate
transit times across the Zone in a particular direction (i.e. startline to finishline) and detect
when these are abnormal.

## Zone sends the following messages to zone.address:

When a vehicle completes a transit of the Zone, startline..finishline:
```
{ "module_name":  MODULE_NAME,
    "module_id", MODULE_ID,
    "msg_type", Constants.ZONE_COMPLETION,
    "vehicle_id", vehicle_id,
    "route_id", route_id,
    "ts", finish_ts, // this is a CALCULATED timestamp of the finishline crossing
    "duration", duration // Zone transit journey time in seconds
  }
```

When a vehicle exits the Zone other than via start and finish lines
```
  { "module_name":  MODULE_NAME,
    "module_id", MODULE_ID,
    "msg_type", Constants.ZONE_EXIT,
    "vehicle_id", vehicle_id,
    "route_id", route_id,
    "ts", position_ts // this is the timestamp of the first point OUTSIDE the zone
  }
```

When a vehicle enters the Zone via the start line
```
  { "module_name":  MODULE_NAME,
    "module_id", MODULE_ID,
    "msg_type", Constants.ZONE_START,
    "vehicle_id", vehicle_id,
    "route_id", route_id,
    "ts", start_ts // this is the timestamp of the first point OUTSIDE the zone
  }
```

When a vehicle enters the Zone but NOT via the start line
```
  { "module_name":  MODULE_NAME,
    "module_id", MODULE_ID,
    "msg_type", Constants.ZONE_ENTRY,
    "vehicle_id", vehicle_id,
    "route_id", route_id,
    "ts", position_ts // this is the timestamp of the first point INSIDE the zone
  }
```

