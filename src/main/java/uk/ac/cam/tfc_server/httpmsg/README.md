## [RITA](https://github.com/ijl20/tfc_server) &gt; HttpMsg

HttpMsg is part of the Cambridge Adaptive Cities platform,
supported by the Smart Cambridge programme.

## Overview

HttpMsg listens for POSTs on a defined port and URLs, and transmits each POST as
a message on the Vertx EventBus

It's function is very similar to
[FeedHandler](https://github.com/ijl20/tfc_server/src/main/java/uk/ac/cam/tfc_server/feedhandler)
except HttpMsg does NOT save the POST data to the filesystem.

HttpMsg receives its configuration parameters (e.g. the eventbus address to
use for the feed messages) in its [Vertx](vertx.io) config().

HttpMsg also publishes regular 'status=UP' messages to
the 'system_status' eventbus address to be interpreted by the Console.

HttpMsg supports multiple simultaneous feeds (in the ```httpmsg.feeds``` config
property, each of which will have a unique ```address``` property which is the
Vertx EventBus address to which the message should be sent.

### Receiving POST data

E.g. with the example app config() listed below, HttpMsg will listen for POSTs to

<hostname>:8098/httpmsg/test/tfc.httpmsg.test

and send those messages on the EventBus to

tfc.httpmsg.test

## HttpMsg config format
```
                                                                                
{
    "main":    "uk.ac.cam.tfc_server.httpmsg.HttpMsg",
    "options":
        { "config":
          {

            "module.name":           "httpmsg",
            "module.id":             "test",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",
              
            "httpmsg.log_level":   1,

            "httpmsg.http.port":   8098,

            "httpmsg.feeds":     [
                                       { 
                                         "http.token": "httpmsg-test",
                                         "address" :   "tfc.httpmsg.test"
                                        }
                                 ]
          }
        }
}
```

