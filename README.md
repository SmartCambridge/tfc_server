#![Smart Cambridge logo](images/smart_cambridge_logo.jpg) RITA: Realtime Intelligent Traffic Analysis

# Part of the Smart Cambridge programme.

*This is a work-in-progress, not all features below are complete, see Summary of system modules below*

## Overview

This system, written in Java / [Vertx](https://vertx.io) is designed to receive 'real-time' vehicle
position feeds, do analysis on those position updates in real-time, and provide both web-based information
pages and also send messages based on user subscriptions.

In terms of system design, the platform is notable mainly in that:
- An asynchronous message-passing paradigm is at the core of the platform, i.e.
vehicle position data is received by the FeedHandler module in real-time, formatted as a a message,
and published in the system. Other modules will receive and process those messages, themselved publishing
derived messages such as a zone becoming congested.
- The [Vertx](https://vertx.io) library is used to provide a non-blocking framework for the server-side
Java code, with the RITA modules themselves designed to operate asynchronously and be non-blocking.
- Each module in the system (e.g. FeedHandler, FeedPlayer, Zone, Route, Vehicle) is designed to be an
independent agent or [actor](http://www.brianstorti.com/the-actor-model/), and the system design
supports configurations of arbitrary numbers of feeds, zones, users etc.

## User output expected from the system

The system will:
- allow the user to select the region, routes or vehicles they are interested in
- show where vehicles are on a map in real time
- replay any prior period of vehicle movements
- provide realtime status of vehicles moving through designated *zones*
- show the impact of any congestion on arrival times within a chosen bus route
- show the status of predicted bus arrivals at any bus stop
- allow the user to subscribe to messages that will warn of poor service on any route or zone.

## Summary of system modules
*For more detail see the readme in each module directory*

### FeedHandler (see [FeedHandler README](src/main/java/uk/ac/cam/tfc_server/feedhandler/README.md))

Receives the realtime batched of vehicle position data (currently as GTFS format binary data as
an http POST every 30 seconds) and parses/publishes the data as a 'json' message on the Vertx eventbus.

FeedHandler also archives each post of binary data as a timestamped file in the server filesystem.

### FeedPlayer

FeedPlayer can read the archived posts of historical binary data, and publish that at a user-selected
frequency on the Vertx eventbus. This facilitates the use of the system for analysis of historical
data.

### Console

Designed to be used by admin users, the Console presents a user webpage reporting status.
Each module in the system reports its 'up' status regularly on the Vertx eventbus, and the console
listens for these messages and dynamically adds status reporting areas to the web page. Any module can
also transmit general log messages to the console.

It is intended to add a command-line in the console, to provide convenient web-based administration of
some aspects of the system.

### Zone

A Zone is provided with configuration parameters defining the geographic area of interest (as a
general polygon) and is assumes to have a 'start line' and a 'finish line'.  The Zone subscribes
to FeedHandler or FeedPlayer messages and Vehicles will be
tracked that enter the Zone by crossing that start line, and also leaving the Zone by crossing
the finish line.  A typical Zone will be a segment of road, or a polygon covering a segment of
a bus route along multiple roads. The Zone sends update messages when vehicles are found to
be moving more slowly that usual across the zone.

### ZoneManager

A ZoneManager is able to dynamically start multiple Zones, all listening to the same FeedManager or
FeedPlayer, and reporting their updates to a common 'zone update' address.

### FeedCSV

A FeedCSV module subscribes to a FeedHandler or FeedPlayer feed, and writes the vehicle position data
as CSV (comma-delimited ascii text) files in the server filesystem. The idea is simply that this may be
a more convenient or longer-term viable format for the archive of vehicle position data
than the originally received GTFS-format binary data (protobuf).

### Rita

Rita is the module providing the agent that acts on behalf of the general web *user*, e.g. it can
subscribe to the FeedHandler and show the position of vehicles on a map, or can display the
status of selected zones by subscribing to those messages.

Rita can equally provide most end-user functions using re-played data from the archive (i.e. using
a FeedPlayer) such that users can study exactly what did happen when a significant traffic
issue occurred.

It is intended that Rita will also provide the user subscription mechanism for human-readable
*alerts*, e.g. a user may opt to receive an email at 4pm every day *if* Hills Road is congested.

### FeedComposer *(planned)*

It is intended that a FeedComposer module will accept a request (via the eventbus) for a custom
feed, and will subscribe to the appropriate FeedHandler or FeedPlayer module, filter the data to
produce the requested data.  A typical use is expected to be a subscriber (such as a Zone) may only
be interested in vehicle position updates that fall within a particular geographic region. This will
provide an efficiency improvement e.g. the Zones in Cambridge will only subscribe to vehicle updates
occuring in a box around the city, while the raw feed includes vehicles across much of the UK.

### FeedManager *(planned)*

It is intended that a FeedManager, similar to the ZoneManager, will be useful to dynamically spawn
FeedHandlers, FeedPlayers and FeedComposers.

### Route *(planned)*

It is intended that Route modules will act as 'agents' on behalf of each bus route, e.g. the Citi-4
agent will subscribe to FeedHandler messages for vehicles on the Citi-4 route, and Zone messages
relevant to the Citi-4 route, to provide status and messages pertaining to the Citi-4 route (such
as likely delays and predicted journey times.

### Vehicle *(planned)*

It is possible we should have an agent-per-vehicle, i.e. a module that subscribes to the FeedPlayer or
FeedComposer that only has an interest in a given vehicle, and maintains status and generates messages
relevant to that vehicle. Not sure...

### Stop *(planned)*

It is possible we should have an agent-per-bus-stop, listening to appropriate messages and updating the
status of that stop and its timetable.

Author: Ian Lewis - ijl20@cam.ac.uk

This repository is for the the "tfc_server" system, containing:

* Realtime vehicle position feed handlers

* Zone analyzers, i.e. modules that detect vehicles entering/leaving geographic zones

* Consoles, for reporting the status of the various modules

* HTTP servers that present the user interface and consoles.

The system uses Java / Vertx to be event driven throughout

Update 2016-04-01

The entire architecture is *message passing* beginning with the realtime feeds coming in through to the analysis of buses and zones – it can also replay any day.  At the moment I’m building out the set of Agents that will be needed, so far I have working:

[FeedHandler]: receives the realtime feed and publishes it as (json) position update messages into my platform.

[Zone]: subscribes to position update messages and calculates whether buses have entered/left a zone, or has a successful transit of the zone, and publishes those ‘zone’ messages. I’ve defined parameters for about 10 zones around Cambridge, but there’s no limit.

[ZoneManager]: dynamically spawns new zones as needed

[FeedPlayer]: reads historical data and publishes it similar to a FeedHandler

[FeedCSV]: reads the FeedHandler messages and archives the records as CSV

[Rita]: provides a web user interface, e.g. to display Zone congestion info, or just draw positions on a map. I want to tart this up a bit in the short term so it is producing pages a transport user would recognise.

[Console]: listens to everything and provides a system status / log message browser window for administration.

Additional agents I’m pretty sure I will need, but haven’t built yet, include:

[Route]: will subscribe to zone messages and maybe watch individual buses to produce some Route status.

[Stop]: similar to Route, but only care about messages affecting an individual stop.

[Bus] (maybe): I could dynamically spawn agents that track each bus, if that makes the system more powerful – it would be the logical place to accumulate real-time information for each bus in case Stops or Routes want to query that, or receive alert messages saying a bus has an issue, but not sure. As I write this it seems to make more sense (the Bus agent would detect a bus getting stuck between stops, and could send a Bus-related alert message which other agents *might* subscribe to, but still not sure).

[Messenger]: subscribes to a variety of alert messages, and publishes human-readable versions outside the system in the form of emails, tweets and rss.

[FeedComposer]: My existing agents just subscribe to the FeedHandler receiving the Vix positions updates – actually I’m assuming a Zone ‘requests’ a feed (e.g. Vix buses within Cambridge) and the FeedComposer would subscribe to the (e.g.) Vix feed and produce a custom feed that only contains the data of interest – this will make the platform more scalable (not that that’s an issue at the moment).

