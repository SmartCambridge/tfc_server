##  [RITA](https://github.com/ijl20/tfc_server) &gt; ZoneManager

ZoneManager is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

ZoneManager receives a [config()](http://vertx.io/blog/vert-x-application-configuration/) which lists
parameters for a group of [Zones](../zone) to be spawned, includind the eventbus address they should
subscribe to and also the address on which they should send their Zone update messages.
