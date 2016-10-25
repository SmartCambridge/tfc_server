## [RITA](https://github.com/ijl20/tfc_server) &gt; FeedScraper

FeedScraper is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

FeedScraper periodically polls provided web addesses, archives the raw data
received, parses that data and sends it as a json message on Rita's real-time
EventBus. It's function is very similar to
[FeedHandler](https://github.com/ijl20/tfc_server/src/main/java/uk/ac/cam/tfc_server/feedhandler).

To preserve the data, FeedScraper immediately writes this binary data to the
file system in two places (as set in the Vertx verticle config):
- a binary archive directory as YYYY/MM/DD/&lt;filename&gt;.bin
- as a file in a "monitor" directory so it is available to trigger
other linux processes via inotifywait, as &lt;filename&gt;.bin, *deleting any
prior .bin files in that directory*

The filename is &lt;UTC TIMESTAMP&gt;\_YYYY-DD-MM-hh-mm-ss.bin where hh-mm-ss
is LOCAL time. The UTC timestamp provides a guaranteed ordering of the feeds
while the local time is often more useful for relative analysis (e.g.
congestion tends to correlate with local time, not UTC).

FeedScraper then parses the raw received data (depending on a local parsing
module typically unique to the source) and 'publishes' the data to the eventbus as Json.

FeedScraper receives its configuration parameters (e.g. the eventbus address to
use for the feed messages) in its [Vertx](vertx.io) config().

FeedScraper also publishes regular 'status=UP' messages to
the 'system_status' eventbus address to be interpreted by the Console.

## FeedScraper eventbus message format


```
{
   "module_name": "feedscraper",                // as given to the FeedScraper in config, typically "feedscraper"
   "module_id"  : "abc",                        // from config, but platform unique value within module_name
   "feed_id"    : "cam_local_parking",          // identifies http source, matches config
   "feed_format" : "car_parking",
   "filename":"1459762951_2016-04-04-10-42-31",
   "filepath":"2016/04/04",
   "request_data":[                             // actual parsed data from source, in this case car park occupancy
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
In the (real) example above, the parking occupancy record batch was written to a file called
"2016/04/04/1459762951_2016-04-04-10-42-31.bin" by FeedScraper. The Unix timestamp is
in UTC, while the 2016/04/04 and 10-42-31 is local time. That path is beneath a 'data_bin' root
specified in the FeedScraper config.


