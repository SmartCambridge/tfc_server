## [RITA](https://github.com/ijl20/tfc_server) &gt; FeedMaker

FeedMaker is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

FeedMaker supports two HTTP methods to get the data:
1 Periodic polling with GET
2 Passive event-driven receipt of the data via POST

It's function is very similar to
[FeedHandler](https://github.com/ijl20/tfc_server/src/main/java/uk/ac/cam/tfc_server/feedhandler).

FeedMaker receives its configuration parameters (e.g. the eventbus address to
use for the feed messages) in its [Vertx](vertx.io) config().

FeedMaker also publishes regular 'status=UP' messages to
the 'system_status' eventbus address to be interpreted by the Console.

FeedMaker supports multiple simultaneous feeds (in the ```feedmaker.feeds``` config
property, each of which will have a unique ```feed_id``` property.

### GET

FeedMaker periodically polls provided web addesses, archives the raw data
received, parses that data and sends it as a json message on Rita's real-time
EventBus.

The app config feed property ```"http.get": true``` tells FeedMaker to poll with GET
requests to the defined web address.

### POST

FeedMaker sets up a handler for POST events to the local URI
MODULE_NAME/MODULE_ID/FEED_ID

E.g. with the example app config() listed below, FeedMaker will listen for POSTs to
feedmaker/cam/cam_park_local

The app config feed property ```"http.post": true``` tells FeedMaker to register
a handler for POSTs with the expected data. For any feed to have the "http.post"
option set, the FeedMaker must have the "feedmaker.http.port" propery set to give
the port on which the webserver will listen.

## Data storage

To preserve the data, FeedMaker immediately writes this binary data to the
file system in two places (as set in the Vertx verticle config):
- a 'data_bin' binary archive directory as YYYY/MM/DD/&lt;filename&gt;.bin
- as a file in a "data_monitor" directory so it is available to trigger
other linux processes via inotifywait, as &lt;filename&gt;.bin, *deleting any
prior .bin files in that directory*

The filename is &lt;UTC TIMESTAMP&gt;\_YYYY-DD-MM-hh-mm-ss.bin where hh-mm-ss
is LOCAL time. The UTC timestamp provides a guaranteed ordering of the feeds
while the local time is often more useful for relative analysis (e.g.
congestion tends to correlate with local time, not UTC).

FeedMaker then parses the raw received data (depending on a local parsing
module typically unique to the source) and 'publishes' the data to the eventbus as Json.

## FeedMaker eventbus message format


```
{
   "module_name": "feedmaker",                  // given to the FeedMaker in config, typically "feedmaker"
   "module_id":   "cam_parking_local",          // from config, but platform unique value within module_name
   "msg_type":    "car_parking",                // unique id for this message format
   "feed_id":     "cam_parking_local",          // identifies http source, matches config
   "filename":    "1459762951_2016-04-04-10-42-31",
   "filepath":    "2016/04/04",
   "request_data":[                             // parsed data from source, in this case car park occupancy
                    { "area_id":         "cam",
                      "parking_id":      "grafton_east",
                      "parking_name":    "Grafton East",
                      "spaces_total":    874,
                      "spaces_free":     384,
                      "spaces_occupied": 490
                    } ...
                   ]
}
```
In the example above, the parking occupancy record batch was written to a file called
"2016/04/04/1459762951_2016-04-04-10-42-31.bin" by FeedMaker. The Unix timestamp is
in UTC, while the 2016/04/04 and 10-42-31 is local time. That path is beneath a 'data_bin' root
specified in the FeedMaker config.

## Feedmaker app config format
```
{
    "main":    "uk.ac.cam.tfc_server.feedmaker.FeedMaker",
    "options":
        { "config":
          {

            "module.name":           "feedmaker",
            "module.id":             "cam",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",
              
            "feedmaker.log_level":   2,

            "feedmaker.http.port":   8086,

            "feedmaker.feeds":     [
                                       { 
                                         "feed_id" :   "cam_park_local",
                                         "area_id" :   "cam",

                                         "http.get":   "true";
                                         "period" :    300,
                                         "http.host":  "www.cambridge.gov.uk",              
                                         "http.uri" :  "/jdi_parking_ajax/complete",
                                         "http.ssl":   true,
                                         "http.port":  443,

                                         "http.post":  true,
                                         "http.token": "cam-auth-test",

                                         "file_suffix": ".html",
                                         "data_bin" :  "/media/tfc/cam/cam_park_local/data_bin",
                                         "data_monitor" : "/media/tfc/cam/cam_park_local/data_monitor",

                                         "msg_type" :  "feed_car_parks",
                                         "address" :   "tfc.feedmaker.cam"
                                        }
                                     ]
          }
        }
}

```

