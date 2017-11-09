## [RITA](https://github.com/ijl20/tfc_server) &gt; Rita
Realtime Intelligent Traffic Analysis

![zone plot screenshot](../../../../../../../../images/zone_plot_screenshot.png)

## Overview

The Rita module acts as the 'user agent' in the system, providing a user-friendly web interface meaningful
to a typical end user, for example someone using the local transport system.

Currently the information displayed is primarily aimed at testing the system (e.g. by showing the position of all of
the vehicles on a map) but the objective is to build a set of pages that display useful information such as:

- the traffic flow status of the major roads, inbound and outbound
- status page and predicted timetable for each bus route
- real-time predicted arrival times for buses at each bus stop
- alert messages for routes where traffic flow is abnormal
- user 'subscription' page to request email or (perhaps) SMS alerts on a regular basis, e.g. send an email alert at 4pm every weekday if traffic is bad on a particular route.

## Implementation *(this is a work-in-progress)*

Rita is a Java / Vertx module that subscribes to eventbus messages from the various feeds, zones and routes, and
provides an http server with the pages in HTML and Javascript.

The Java / HTTP [eventbus bridge](http://vertx.io/docs/vertx-web/java/#_sockjs_event_bus_bridge) `vertx-eventbus.js` is used
to send messages (on a defined address such as "rita_out" specifically created to communicated with the browser, not the 
internal eventbus addresses used for the inter-module communication).

### Startup configuration

Rita can be started with the command:

    `vertx run service:uk.ac.cam.tfc_server.rita.A -cluster -cluster-port 10099`

where the "service:" tag automatically requests the [config](http://vertx.io/blog/vert-x-application-configuration/) and
-cluster-port is any port *not already in use*.

The config is stored as a json file, in this example the following file will be used:
[`src/main/resources/uk.ac.cam.tfc_server.rita.A.json`](https://github.com/ijl20/tfc_server/src/main/resources/uk.ac.cam.tfc_server.rita.A.json):

```
{
    "main": "uk.ac.cam.tfc_server.rita.Rita",
    "options": {
        "config": {
            "module.name":      "rita",
            "module.id":        "A",

            "eb.system_status": "system_status",
            "eb.console_out":   "console_out",
            "eb.manager":       "tfc.manager",

            "rita.address":     "tfc.rita.A",

            "rita.zonemanagers": [ "A" ],
            "rita.zone.address": "tfc.zone.A",
            "rita.zone.feed":    "feed_vehicle",

            "rita.name":      "RITA-A",
            "rita.http.port":  8082,
            "rita.webroot":   "src/main/java/uk/ac/cam/tfc_server/rita/webroot"
        }
    }
}
```

Given the above config(), Rita will:
- start a web server on port given in `rita.http.port` in this case 8082, serving web pages from the directory
listed in `rita.webroot`
- start each ZoneManager listed in `rita.zonemanagers`, in this case the single `service:uk.ac.cam.tfc_server.zonemanager.A` which itself will use the [`src/main/resources/uk.ac.cam.tfc_server.zonemanager.A.json`](https://github.com/ijl20/tfc_server/src/main/resources/uk.ac.cam.tfc_server.zonemanager.A.json) config file (and that config will tell
ZoneManager to start multiple Zones). Each of those Zones will be configured to subscribe to the eventbus address in `rita.zone.feed` by passing that address in a their config parameter `zone.feed`.
- listen to the address given in parameter `eb.manager` for general system commands
- send periodic "status UP" messages to the eventbus address given in `eb.system_status`
 
In addition, similar to [ZoneManagers](../zonemanager), Rita can start [FeedPlayers](../feedplayer), using the parameters:

```
            "rita.feedplayers": [ "A", "B" ],
            "rita.feedplayer.address": "tfc.feedplayer.77"
```
The FeedPlayers thus started would publish their vehicle position records to the eventbus address given in `rita.feedplayer.address`. It would be typical in this case that the Zones started (if any) would be given the
same address (in their `zone.feed` parameter) to subscribe to.
