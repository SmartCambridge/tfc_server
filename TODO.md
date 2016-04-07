## [RITA](https://github.com/ijl20/tfc_server) &gt; TODO

### Rita [src/main/java/uk/ac/cam/tfc_server/rita](src/main/java/uk/ac/cam/tfc_server/rita)

- [ ] User web Realtime display of zone status
- [ ] Have Zone remember transit times for day so far
- [ ] User web realtime display of zone transit times as x-y plot
- [ ] Support user selection of prior day replay
- [ ] Support user subscription to alerts

### Zone [src/main/java/uk/ac/cam/tfc_server/zone](src/main/java/uk/ac/cam/tfc_server/zone)

- [ ] Support management messages to add zone.feed, zone.address pairs so zones support multiple requests
- [ ] Have Zone communicate velocity information even though vehicles may not have completed transit

### FeedComposer

- [ ] design / build FeedComposer agent to create custom feeds

### Console [src/main/java/uk/ac/cam/tfc_server/console](src/main/java/uk/ac/cam/tfc_server/console)

- [ ] add security
- [ ] all command input
- [ ] provide general eventbus monitoring
- [ ] display realtime feed vehicle count

### Route

- [ ] design / build Route agent to accumulate status and generate messages for a given bus route

### Stop

- [ ] design / build Stop agent to accumulate status and generate messages for each bus stop

### ZoneCSV

- [ ] archive / write Zone messages to the filesystem

### TFCStore

- [ ] write vehicle / zone / route / stop data to a database


