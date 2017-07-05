## [RITA](https://github.com/ijl20/tfc_server) &gt; EverynetFeed

EverynetFeed is part of the Cambridge Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

EverynetFeed is intended as a LoraWAN 'application' to receive sensor data from
the Cambridge Sensor Network which is currently based on Everynet gateways.

It's function is very similar to
[FeedHandler](https://github.com/ijl20/tfc_server/src/main/java/uk/ac/cam/tfc_server/feedhandler).

EverynetFeed receives its configuration parameters (e.g. the eventbus address to
use for the feed messages) in its [Vertx](vertx.io) config().

EverynetFeed also publishes regular 'status=UP' messages to
the 'system_status' eventbus address to be interpreted by the Console.

EverynetFeed supports multiple simultaneous feeds (in the ```feedmaker.feeds``` config
property, each of which will have a unique ```feed_id``` property.

### POST

EverynetFeed sets up a handler for POST events to the local URI
MODULE_NAME/MODULE_ID/FEED_ID

E.g. with the example app config() listed below, FeedMaker will listen for POSTs to
feedmaker/cam/cam_park_local

The app config feed property ```"http.post": true``` tells FeedMaker to register
a handler for POSTs with the expected data. For any feed to have the "http.post"
option set, the FeedMaker must have the "feedmaker.http.port" propery set to give
the port on which the webserver will listen.

## Data storage

To preserve the data, EverynetFeed immediately writes this binary data to the
file system in two places (as set in the Vertx verticle config):
- a 'data_bin' binary archive directory as YYYY/MM/DD/&lt;filename&gt;.bin
- as a file in a "data_monitor" directory so it is available to trigger
other linux processes via inotifywait, as &lt;filename&gt;.bin, *deleting any
prior .bin files in that directory*

The filename is &lt;UTC TIMESTAMP&gt;\_YYYY-DD-MM-hh-mm-ss.bin where hh-mm-ss
is LOCAL time. The UTC timestamp provides a guaranteed ordering of the feeds
while the local time is often more useful for relative analysis (e.g.
congestion tends to correlate with local time, not UTC).

EverynetFeed then parses the raw received data (depending on a local parsing
module typically unique to the source) and 'publishes' the data to the eventbus as Json.

## EverynetFeed eventbus example message format


```
{
   "module_name": "everynet_feed",              // given to the FeedMaker in config, typically "feedmaker"
   "module_id":   "test",                       // from config, but platform unique value within module_name
   "msg_type":    "everynet_ascii_hex",         // unique id for this message format
   "feed_id":     "ascii_hex",                  // identifies http source, matches config
   "filename":    "1459762951_2016-04-04-10-42-31",
   "filepath":    "2016/04/04",
   "request_data":[                             // parsed data from source, in this case car park occupancy
        {"params": {"payload": "nhZSFzkQAABiYBQFD7U=", "port": 1, "dev_addr": "175b5b50",
                    "radio": {  "stat": 1, "gw_band": "EU863-870", "server_time": 1495228962.576152, "modu": "LORA",
                                "gw_gps": {"lat": 52.20502, "alt": 76, "lon": 0.10843}, "gw_addr": "70b3d54b13c80000", "chan": 4,
                                "gateway_time": 1495228962.523077, "datr": "SF12BW125", "tmst": 2295430404, "codr": "4/5",
                                "rfch": 1, "lsnr": -16.0, "rssi": -119, "freq": 868.1, "size": 27},
                    "counter_up": 127, "dev_eui": "0018b2000000113e", "rx_time": 1495228962.523077,
                    "encrypted_payload": "dKf1XcEJUJNJFnerXEY="},
         "jsonrpc": "2.0", "id": "00421fc2adf8", "method": "uplink",
         "calc_lat": 52.28985,
         "calc_lng": 0.10433333333333333
        }
                   ]
}
```
In the example above, the parking occupancy record batch was written to a file called
"2016/04/04/1459762951_2016-04-04-10-42-31.bin" by FeedMaker. The Unix timestamp is
in UTC, while the 2016/04/04 and 10-42-31 is local time. That path is beneath a 'data_bin' root
specified in the FeedMaker config.

## Example EverynetFeed app config
```
{
    "main":    "uk.ac.cam.tfc_server.everynet_feed.EverynetFeed",
    "options":
        { "config":
          {

            "module.name":           "everynet_feed",
            "module.id":             "A",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",
              
            "everynet_feed.log_level":   1,

            "everynet_feed.http.port":   8087,

            "everynet_feed.feeds":     [
                                       { 
                                         "feed_id" :   "ascii_decimal",
                                         "area_id" :   "cam",

                                         "file_suffix":   ".json",
                                         "data_bin" :     "/home/ijl20/everynet_data/data_bin",
                                         "data_monitor" : "/home/ijl20/everynet_data/data_monitor",

                                         "msg_type" :  "everynet_ascii_decimal",
                                         "address" :   "tfc.everynet_feed.A"
                                       },
                                       { 
                                         "feed_id" :   "ascii_hex",
                                         "area_id" :   "cam",

                                         "file_suffix":   ".json",
                                         "data_bin" :     "/home/ijl20/everynet_data/data_bin",
                                         "data_monitor" : "/home/ijl20/everynet_data/data_monitor",

                                         "msg_type" :  "everynet_ascii_hex",
                                         "address" :   "tfc.everynet_feed.A"
                                       }
                                     ]
          }
        }
}
```

