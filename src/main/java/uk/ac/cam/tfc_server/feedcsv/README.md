# [RITA](https://github.com/ijl20/tfc_server) &gt; FeedCSV

FeedCSV is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

FeedCSV subscribes to a feed from a [FeedHandler](../feedhandler) and writes the position updates
as a new CSV file in the filesystem.

Vertx [config()](http://vertx.io/blog/vert-x-application-configuration/) parameters tell the FeedCSV
which eventbus address to subscribe to, and where to store the CSV files.

The CSV files have a header which lists the field names.

