# [Rita](https://github.com/ijl20/tfc_server) &gt; DataServer

DataServer is part of the RITA Realtime Intelligent Traffic Analysis platform,
supported by the Smart Cambridge programme.

## Overview

DataServer is an http server for serving templated content containing data from
the Rita platform. DataServer does not use WebSockets or EventBus bridging to the
user's web page, and populates the page with the required data before it is
delivered to the end user, much like any other traditional server-side scripted
web pages. This differs from the pages delivered by the Rita verticle which are
designed to be real-time to the end-user and are more demanding in terms of the
technology requried to support this.

DataServer is configured via Vertx application config(), see examples in the
main/resources directory.


