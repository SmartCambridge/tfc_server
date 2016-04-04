# Rita
Realtime Intelligent Traffic Analysis

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

The Java / HTTP [eventbus bridge](http://vertx.io/docs/vertx-web/java/#_sockjs_event_bus_bridge) vertx-eventbus.js is used
to send messages (on a defined address such as "rita_out" specifically created to communicated with the browser, not the 
internal eventbus addresses used for the inter-module communication).
