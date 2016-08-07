## [RITA](https://github.com/ijl20/tfc_server) &gt; Batcher

Batcher is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

Batcher is designed to step through an archived source data feed, passing the data points *synchronously*
to be processed by other Rita modules. The processing is similar to configuring a JVM to
contain a FeedPlayer, Zones and MsgFilers but this synchronous operation allows the processing to
proceed as fast as possible.

The (normal, asynchronous) Batcher verticle spawns one or more (synchronous) BatcherWorker
verticles that are each designed to run on a worker thread.

Batcher, and its main class BatcherWorker, provides the *synchronous* data processing capability of the
Rita platform.

Note that the verticles of the Rita platform (such as FeedHandler, Zone, MsgFiler) are designed to operate
asynchronously, receiving and processing data and user requests and storing or presenting derived analytics.
The Rita platform is designed to operate comfortably with the real-time data rates expected, and in fact the
FeedPlayer verticle can replay data at much higher rates (such as 50x normal speed) with the platform
continuing to function in the normal way. Actually the system has been run in this way at over 500x
'real-time' speed but at these rates it should be recognised a better synchronous processing approach would
be appropriate (hence Batcher / BatcherWorker).

But at some level of acceleration, a limit will be reached where the *downstream* processing verticles
can't keep up with the rapidity of the feed data, and the lightweight approach taken by both Rita and the
Vertx platform is to assume data messages can simply be missed without the system falling apart.

The Batcher module is designed to be used where the simple acceleration provided by a FeedPlayer running at,
say, 50x normal speed is not enough. Batcher spawns worker threads (running a class called BatcherWorker)
which *synchronously* read through historic feed data and call the Rita processing routines (such as Zone
calculations) synchronously for each data point, synchronously storing analytics data as required.

A BatcherWorker configured to process a day's worth of vehicle position data will (as a simple benchmark,
processing the same set of Cambridge region Zones)
acheive a processing speed up of approximately 7500x over the original real-time data rate.

Vertx [config()](http://vertx.io/blog/vert-x-application-configuration/) parameters tell the Batcher
which files to read and which synchronous routines (e.g. from Zones) to process the data with.


#### BatcherWorker

A BatcherWorker will synchronously iterate through a specified set of feed data records, and for
each will, for example, call the core processing routines from the Zone module (actually using
the ZoneConfig and ZoneCompute classed), generating zone transit data records which are stored
using routines shared with MsgFiler (from the FilerUtils class).

#### Sample Batcher config file
```
                                                                                
{
    "main":    "uk.ac.cam.tfc_server.batcher.Batcher",
    "options":
        { "config":
          {

            "module.name":           "batcher",
            "module.id":             "A",

            "eb.system_status":      "tfc.system_status",

            "batcher.log_level":     2,

            "batcher.address" :      "tfc.batcher.A",

            "batcher.batcherworkers": [ "A" ],

            "batcherworker.A.data_bin":      "/mnt/usb_wd_2/tfc/data_bin",
            "batcherworker.A.start_ts" :  1465603200,
            "batcherworker.A.finish_ts" : 1465686000,
            "batcherworker.A.zones" : [
                  "east_road_in",
                  "east_road_out",
                  "hills_road_in",
                  "hills_road_out",
                  "histon_road_in",
                  "histon_road_out",
                  "huntingdon_road_in",
                  "huntingdon_road_out",
                  "madingley_road_in",
                  "madingley_road_out",
                  "milton_road_in",
                  "milton_road_out",
                  "newmarket_road_in",
                  "newmarket_road_out",
                  "the_backs_north",
                  "the_backs_south",
                  "trumpington_road_in",
                  "trumpington_road_out"
            ],
            "batcherworker.A.filers":
            [
                { 
                  "source_filter": { "field": "msg_type",
                                     "compare": "=",
                                     "value": "zone_completion"
                                   },
                  "store_path": "/home/ijl20/tfc_server_data/data_zone_test/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}",
                  "store_name": "{{module_id}}_{{ts|yyyy}}-{{ts|MM}}-{{ts|dd}}.txt",
                  "store_mode": "append"
                }
            ]
          }
        }
}
```
