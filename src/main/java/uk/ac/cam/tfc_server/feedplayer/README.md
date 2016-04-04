## [RITA](https://github.com/ijl20/tfc_server) &gt; FeedPlayer

FeedPlayer is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

FeedPlayer reads the archive of binary GTFS-format batch position feeds produced
by [FeedHandler](../feedhandler) and publishes each position record batch as a
message on the eventbus.

In this way, receiving modules in the RITA system can be made to process historical
data in exactly the same way as the real-time feeds.

Vertx [config()](http://vertx.io/blog/vert-x-application-configuration/) parameters tell the FeedPlayer
which files to read and which eventbus address to publish the messages to.

