## [Intelligent City Platform](https://github.com/SmartCambridge/tfc_server) &gt; FeedMQTT

FeedMQTT is the Intelligent City Platform vertx mqtt client. 

Using parameters provided in a service configuration file, 
FeedMQTT subscribes to channels on a remote MQTT
server, and publishes the received messages on the eventbus while also persisting them in the filesystem.

The primary uses so far have been to subscribe to data from The Things Network, and also to connect to
an MQTT broker (mosquitto) running locally on the server which is itself receiving data published by
sensors.

## Sample service config

Here is an example used to receive data from TTN.

```
{
    "main":    "uk.ac.cam.tfc_server.feedmqtt.FeedMQTT",
    "options":
        { "config":
          {

            "module.name":        "feedmqtt",
            "module.id":          "dev",

            "eb.system_status":   "tfc.system_status",
            "eb.console_out":     "tfc.console_out",
            "eb.manager":         "tfc.manager",
              
            "feedmqtt.log_level": 1,

            "feedmqtt.feeds":     [
                                       { 
                                         "feed_id" :   "csn",
                                         "feed_type":  "feed_mqtt",
                                         "host":       "eu.thethings.network",
                                         "port":       1883,
                                         "topic":      "+/devices/+/up",
                                         "username":   "<TTN application name>",
                                         "password":   "ttn-account-v2.7BgW-blah-blah-blah-SoLpB9",

                                         "file_suffix":   ".json",
                                         "data_bin" :     "/home/foo/tfc_server_data/csn_ttn/data_bin",
                                         "data_monitor" : "/home/foo/tfc_server_data/csn_ttn/data_monitor",

                                         "msg_type" :  "feed_mqtt",
                                         "address" :   "tfc.feedmqtt.dev"
                                       }
                                     ]
          }
        }
}
```

