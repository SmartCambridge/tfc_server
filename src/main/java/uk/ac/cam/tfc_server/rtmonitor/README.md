# [Platform](https://github.com/ijl20/tfc_server) &gt; RTMonitor

RTMonitor, as in Real Time Monitor, is part of the Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

RTMonitor allows client web pages to issue subscriptions to eventbus data and
receive updates via websockets.

RTMonitor maintains the *state* of a given eventbus message feed, such that a
subscription to (say) real-time bus data can push enriched data each time a 
relevant data message appears on the eventbus. In this example, most simply, it can push both the
previous data message for the same bus as well as the newly arrived data message.


