## [RITA](https://github.com/ijl20/tfc_server) &gt; TODO

*Modules in alphabetical order below*

### Console [src/main/java/uk/ac/cam/tfc_server/console](src/main/java/uk/ac/cam/tfc_server/console)

- [ ] add security
- [ ] all command input
- [ ] provide general eventbus monitoring
- [ ] display realtime feed vehicle count
- [ ] allow the creating of 'batch' jobs, e.g. generating FeedCSV data from a FeedPlayer, or creating
and archiving Zone messages. This has a requirement that the system can operate synchronously to
rapidle step through the data.

### FeedComposer

- [ ] design / build FeedComposer agent to create custom feeds

### FeedHandler

- [ ] implement dynamic spawning and closing of FeedHandlers

### FeedManager

- [ ] provide manager agent that can spawn FeedHandlers, FeedPlayers and FeedComposers

### FeedPlayer

- [ ] allow dynamic spawning of FeedPlayers according to user requirements

### Rita [src/main/java/uk/ac/cam/tfc_server/rita](src/main/java/uk/ac/cam/tfc_server/rita)

- [ ] User web Realtime display of zone status
- [ ] Have Zone remember transit times for day so far
- [ ] User web realtime display of zone transit times as x-y plot
- [ ] Support user selection of prior day replay
- [ ] Support user subscription to alerts

### Route

- [ ] design / build Route agent to accumulate status and generate messages for a given bus route

### Stop

- [ ] design / build Stop agent to accumulate status and generate messages for each bus stop

### TFCStore

- [ ] write vehicle / zone / route / stop data to a database

### Vehicle

### Zone [src/main/java/uk/ac/cam/tfc_server/zone](src/main/java/uk/ac/cam/tfc_server/zone)

- [ ] Support management messages to add zone.feed, zone.address pairs so zones support multiple requests
- [ ] Have Zone communicate velocity information even though vehicles may not have completed transit
- [ ] Allow Zone analysis functions to be used *synchronously* for batch mode

### ZoneCSV

- [ ] archive / write Zone messages to the filesystem

### ZoneManager

- [ ] implement dynamic start / close of ZoneManagers

