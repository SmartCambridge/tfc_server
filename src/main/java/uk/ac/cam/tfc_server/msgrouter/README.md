# [Rita](https://github.com/ijl20/tfc_server) &gt; MsgRouter

MsgRouter is part of the Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

MsgRouter subscribes to an eventbus address, filters the messages received, and forwards messages to
defined destination addresses.

## Sample MsgRouter service config file

```
                                                                                
{
    "main":    "uk.ac.cam.tfc_server.msgrouter.MsgRouter",
    "options":
        { "config":
          {

            "module.name":           "msgrouter",
            "module.id":             "everynet_feed_test",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",

            "msgrouter.log_level":     1,

            "msgrouter.address": "tfc.msgrouter.everynet_feed_test",

            "msgrouter.routers":
            [
                { 
                    "source_address": "tfc.everynet_feed.test",
                    "source_filter": { 
                                         "field": "dev_eui",
                                         "compare": "=",
                                         "value": "0018b2000000113e"
                                     },
                     "http.host":  "localhost",              
                     "http.port":  8098,
                     "http.uri" :  "/everynet_feed/test/adeunis_test2",
                     "http.ssl":   false,
                     "http.post":  true,
                     "http.token": "test-msgrouter-post"
                }
            ]
              
          }
        }
}
```

## Sample add_application JSON message

```
{ "msg_type":"module_method",
  "to_module_id":"test",
  "to_module_name":"msgrouter",
  "params":{
      "http.token":"",
      "http.host":"localhost",
      "http.uri":"/efgh",
      "http.port":80,
      "app_eui":2,
      "http.ssl":false,
      "http.post":true},
  "method":"add_application",
  "module_name":"httpmsg",
  "module_id":"test"
}
```

## Sample add_device JSON message

```
{ "msg_type":"module_method",
  "to_module_id":"test",
  "to_module_name":"msgrouter",
  "params":{
      "app_eui":2,
      "dev_eui":"0018b2000000113f"},
  "method":"add_device",
  "module_name":"httpmsg",
  "module_id":"test"
}
```

