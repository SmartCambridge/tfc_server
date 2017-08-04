## [RITA](https://github.com/ijl20/tfc_server) &gt; HttpMsg

HttpMsg is part of the Cambridge City platform,
supported by the Smart Cambridge programme.

## Overview

HttpMsg listens for POSTs on a defined port and URLs, and transmits each POST as
a message on the Vertx EventBus.  It's purpose is to provide an event-driven bridge between an
external environment (in our case the Django web front-end) and the Vertx real-time platform.

HttpMsg will listen for http/https POSTS on
```
[server-name]/<module_name>/<module_id>/<eventbus_address>/<destination_module_name>/<destination_module_id>
```
and the POST data (assumed to be Json) will be published on that eventbus_address given.

HttpMsg will add four properties to the published eventbus message:

* "module_name" - this is the module name of *this* module from its config(), i.e. typically "httpmsg".

* "module_id" - from the config() for this module

* "to_module_name" and "to_module_id" - these allow all modules listening to the given eventbus
address to filter the message if necessary, e.g. on the eventbus address "tfc.manager" modules
can ignore these messages except those containing their own module_name and module_id.

HttpMsg's function is similar to
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

<hostname>:8098/httpmsg/test/tfc.httpmsg.test/<to_module_name>/<to_module_id>

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

### Example usage

HttpMsg is used in the City Platform to allow the Django web environment to send commands to the
MsgRouter module to add sensor / destination mappings.

I.e. when a user fills in a 'Add Device' web form, the Django web platform (tfc_web) sends a POST
to e.g.

```
http://smartcambridge.org/httpmsg/A/tfc.manager/msgrouter/csn
```

The POST can contain the content:
```
{
    "method": "add_sensor",
    "params": { "sensor_id": "fb3a2124fafb12432",
                "info": { "sensor_id": "fb3a2124fafb12432",
                          "destination_id": "fa1cfa12341f3223"
                        }
              }
}
```
And the HttpMsg module will publish onto the eventbus addresss "tfc.manager":
```
{
    "module_name": "httpmsg",
    "module_id": "A",
    "to_module_name": "msgrouter",
    "to_module_id": "csn",
    "method": "add_sensor",
    "params": { "sensor_id": "fb3a2124fafb12432",
                "info": { "sensor_id": "fb3a2124fafb12432",
                          "destination_id": "fa1cfa12341f3223"
                        }
              }
}
```

