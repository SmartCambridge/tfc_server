// ********************************************************************************************
// ****************************** rita.js *****************************************************
// ************************* Realtime Analysis Tool   *****************************************
// ********************************************************************************************

// as of Oct 2015 the fields in data.csv are:
//      timestamp,id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id

// Constant address of server-side eventbus bridge
// Provided by Rita.java
//debug - should be provided by server
var CONSOLE_EVENTBUS_HTTP = 'http://carrier.csi.cam.ac.uk:8082/eb';

// constants controlling what's updated in the browser (map load can be quite high)

var DISPLAY_UPDATE_MAP_POINTS = true;
var DISPLAY_UPDATE_CONSOLE1 = true;

var TRAIL = false; // if true then display all cumulative position data points, not just current

var POSITION_TIMEOUT = 300; // discard positions more than 5 mins old.
var POSITION_AGED = 120; // consider a position to be 'aged' if it's over 2 mins old

//*********************************************************************************************
//*********************************************************************************************
//*************** PROGRAM GLOBAL VARIABLES                   **********************************
//*********************************************************************************************
//*********************************************************************************************

var APP_KEY = 'tfc_current_bounds'; // 'unique' key used to identify localstorage values
var SAVE_KEY = 'save'; // local key to identify saved path localstorage values
var CURRENT_KEY = 'current'; // localstorage save key for current path (so page can restore
                             // dynamic content on reload)

var map; // google.maps.Map object

// initialize lat/lng bounds of the Google map, to prune the markers we need to draw
var map_bounds = { n: 90, s: -90, e: 180, w: -180 };

//var marker;
var infowindow;

var map_element; // html div element that will display Google Map
var saves_element; // div element containing the list of saved paths

var bounds = new Array(); // array to hold all saves loaded from localStorage
                            // bounds[0] is the current drawn bounds
/*
        bounds fields:
        name; // name of this bounding polygon
        path = new Array();
        finishpath; // [ position1, position2 ] coords of finish line
        marker; // google.maps.Marker for first bounds point
        start_polyline; // google.maps.Polyline for initial line between first and second bounds point
        finish_polyline; // google.maps.Polyline for finish line
        finish_index; // index of bounds[] that starts finish line
        polygon; // google.maps.Polygon for bounds area
        box; // rectangle containing bounds, to optimize included check
*/

// one-letter marker labels
var marker_labels = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz123456789';
var UNITS_SPEED = 'mph'; // knots, ms, kmh, mph
var UNITS_DISTANCE = 'miles'; // nm, miles, m, km

// if user has clicked on vehicle 'id' or service 'route_id' then store value here
// so that vehicle (or route) can be highlighted on map
var track_id = '';
var track_route_id = '';

var MAPCENTER = { lat: 52.2, lng: 0.05}; // = new google.maps.LatLng(52.3, -0.1);
var map;

//var info = new Array();
var markers = new Array();

// dictionary to hold previous position of vehicle
var previous_positions = {};

// bounds entry/exit polylines drawn on map e.g. polyline_entry['631'] = google.maps.Polyline
var polyline_entry = {};
var polyline_exit = {};

// CACHE OF PRE-DEFINED BOUNDS IN CAMBRIDGE REGION

var bounds_cache = new Array();
bounds_cache.push(JSON.parse('{\
                                "name":"TOFT",\
                                "center":{"lat":52.2,"lng":0.049999999999954525},\
                                "zoom":13,\
                                "path":[{"lat":52.18181339776096,"lng":-0.00102996826171875},\
                                        {"lat":52.190127806299266,"lng":-0.006008148193359375},\
                                        {"lat":52.1878125575784,"lng":-0.019397735595703125},\
                                        {"lat":52.178339830038674,"lng":-0.01682281494140625}],\
                                "finish_index":2,\
                                "checked":false,\
                                "vehicles":{},\
                                "box":{"north":52.190127806299266,\
                                        "south":52.178339830038674,\
                                        "east":-0.00102996826171875,\
                                        "west":-0.019397735595703125}}'));


// ***********************************************************************************
// ***********************************************************************************
// ************ START CODE ON PAGE/MAP LOAD                         ******************
// ***********************************************************************************
// ***********************************************************************************

function init()
{
    previous_positions = {};

    saves_element = document.getElementById('saves');

    //debug - simple hack to pick up flags in querystring
    if (location.search.toLowerCase().indexOf('update') >= 0)
    {
        UPDATE = true;
        document.getElementById('update').checked = true;        
    }
    
    if (location.search.toLowerCase().indexOf('trail') >= 0)
    {
        TRAIL = true;
    }


    init_bounds();
    
    load_saves();
    display_saves();

    // initialize eventbus to connect to Rita.java on server
    eb = new EventBus(CONSOLE_EVENTBUS_HTTP);
    // script to run when Vertx EventBus is ready
    eb.onopen = function() {

        // set a handler to receive a "rita_out" message
        eb.registerHandler('rita_out', function(error, message) {
          write_console1('rita_out: ' + message.body + '<br/>');
        });

        // set a handler to receive a "rita_feed" message
        eb.registerHandler('rita_feed', function(error, message) {
           // map_feed(message.body);
            // 'entities' is the array of position records from the feed
            // 'timestamp' is the UNIX UTC seconds timestamp the data was sent to us
            process_positions(message.body.entities, message.body.timestamp);
        });
    }

}

// initialize the data structure to store the drawn and loaded bounds
function init_bounds()
{
    bounds = new Array();
    
    // set up bounds[0] for 'current' drawn bounds
    bounds[0] = {};
    bounds[0].name = 'Drawn bounds';
    bounds[0].checked = false;
    bounds[0].vehicles = {}; // vehicle crossing timstamps object
    bounds[0].path = new Array(); // list of lat/long points
}

// initMap() is called when the map loaded into the page (see src URL in google javascript link above)
function initMap() {
    
    //path = document.getElementById('path');
    map_element = document.getElementById('map');

    // set a map style to NOT display points-of-interest
    var mapStyles =[{
            featureType: "poi",
            elementType: "labels",
            stylers: [
                  { visibility: "off" }
            ]
        }];

    map = new google.maps.Map(map_element, {
	  zoom: 13,
          center: new google.maps.LatLng(MAPCENTER.lat, MAPCENTER.lng),
	  mapTypeId: google.maps.MapTypeId.ROADMAP,
          draggableCursor:'crosshair',
          styles: mapStyles
	});

    // set listener to update boundary box for map each time user changes it
    google.maps.event.addListener(map, "bounds_changed", function() {
         var m = map.getBounds();
         map_bounds.w = m.getSouthWest().lng();
         map_bounds.e = m.getNorthEast().lng();
         map_bounds.s = m.getSouthWest().lat();
         map_bounds.n = m.getNorthEast().lat();
    });

    // display stops as markers
    //draw_stops(map, stop_times);
    
    // display trip as polyline
    //draw_trip(map, trip);
    
    // display marker for each trip point
    //draw_points(map, trip);
    
    google.maps.event.addListener(map, 'click', function(event) {
		user_bound(event.latLng);
	});
  
    infowindow = new google.maps.InfoWindow( { disableAutoPan: true });
  
}

// ***********************************************************************************
// ***********************************************************************************
// ************ USER INTERFACE FOR DRAWING NEW BOUNDS ON MAP        ******************
// ***********************************************************************************
// ***********************************************************************************

// User has clicked, or new bound loaded from saved bounds, so place a marker on the map
// We add the marker and the new polyline to 'markers' and 'lines' respectively.
// Also calculate 'hop_distance' (from previous marker) and 'path_distance' (total so far) in m.
// Note we add properties to the marker (e.g. hop_distance) before we add it to 'markers' array.

function user_bound(position)
{
    // store the latLng of this position in the bounds array
    bounds[0].path.push({ lat: position.lat(), lng: position.lng() });
    // for optimization in 'within_bounds', calculate the bounding rectangle that includes current bounds
    bounds[0].box = update_box(0);

    // Now do appropriate update to data depending on whether this is
    // the first marker (markers.length == 0) or a later one.
    if (bounds[0].path.length == 1)
    {
        // for the FIRST bounds point, add marker
        // create basic marker object connected to given position (latLng)
        bounds[0].marker = new google.maps.Marker({
            position: position,
            map: map
        });
        // add this first bounds point to the bounds array
        
        google.maps.event.addListener(bounds[0].marker, 'drag', function () {
            drag_marker(marker);
            });
    } else if (bounds[0].path.length == 2){
        // for the SECOND bounds point, draw a line
        
        // remove the marker for the first bounds point
        bounds[0].marker.setMap(null);
        draw_start(0);
    } else {
        // we have 3 or more bounds points so draw a polygon
        // remove the existing line between the first and second bounds points
        draw_polygon(0);
        draw_start(0);
    }
}

// draw bounds / start / finish on map
function draw_bounds(bounds_index)
{
    draw_polygon(bounds_index);
    draw_start(bounds_index);
    draw_finish(bounds_index);
}

// draw bounding box on map
// This is simply the polygon representing the bounds_path, the startline and the finishline
function draw_polygon(bounds_index)
{
    if (bounds[bounds_index].polygon)
    {
        bounds[bounds_index].polygon.setMap(null);
    }
    // draw the polygon of the bounds onto the map
    bounds[bounds_index].polygon = new google.maps.Polygon({
        paths: bounds[bounds_index].path,
        strokeColor: '#FFFF99',
        strokeOpacity: 0.8,
        strokeWeight: 2,
        fillColor: '#FFFF99',
        fillOpacity: 0.25,
        editable: true,
        zIndex: 5,
        map: map
    });
    
    if (bounds_index == 0)
    {
        // create a listener for when we click to create the finish line
        bounds[0].polygon.addListener('click', bounds_clicked );
    }
}

// for optimization in 'within_bounds', calculate the bounding rectangle that includes all current bounds
function update_box(bounds_index)
{
    var box = { north: -90, south: 90, east: -180, west: 180 };
    var bounds_path = bounds[bounds_index].path
    for (var i=0; i<bounds_path.length; i++)
    {
        if (bounds_path[i].lat > box.north) box.north = bounds_path[i].lat;
        if (bounds_path[i].lat < box.south) box.south = bounds_path[i].lat;
        if (bounds_path[i].lng > box.east) box.east = bounds_path[i].lng;
        if (bounds_path[i].lng < box.west) box.west = bounds_path[i].lng;
    }
    return box;
}

// given index into bounds, calculate [pos1, pos2] for finish line coords
function finish_path(bounds_index)
{
    var a = bounds[bounds_index].finish_index;
    var b = a + 1;
    if (b == bounds[bounds_index].path.length)
    {
        b = 0;
    }
    return [ bounds[bounds_index].path[a],bounds[bounds_index].path[b] ]
}

function draw_start(bounds_index)
{
    if (bounds[bounds_index].start_polyline)
    {
        bounds[bounds_index].start_polyline.setMap(null);
    }
    bounds[bounds_index].start_polyline = new google.maps.Polyline({
                    path: [ bounds[bounds_index].path[0], bounds[bounds_index].path[1]],
                    strokeColor: '#009900',
                    strokeOpacity: 1.0,
                    strokeWeight: 4,
                    editable: false,
                    zIndex: 10,
                    map: map
                  });
}

// draw the finish line
function draw_finish(bounds_index)
{
    //alert('finish is '+bounds_finish_index);
    if (bounds[bounds_index].finish_polyline)
    {
        bounds[bounds_index].finish_polyline.setMap(null);
    }
    bounds[bounds_index].finish_polyline = new google.maps.Polyline({
                path: finish_path(bounds_index),
                strokeColor: '#990000',
                strokeOpacity: 1.0,
                strokeWeight: 4,
                editable: false,
                zIndex: 10,
                map: map
              });
}

// the bounds polygon has been clicked, so draw FINISH line there
function bounds_clicked(polyEvent)
{
    
    if (polyEvent.edge)
    {
        bounds[0].finish_index = polyEvent.edge;
    } else if (polyEvent.vertex) {
        bounds[0].finish_index = polyEvent.vertex;
    } else {
        return;
    }
    
    draw_finish(0);
}

// Draw the plain rectangle that surrounds the bounds polygon
// This is for debug purposes to debug the 'box' optimisation for 
// determining whether a point is inside the bounds polygon (i.e.
// check the containing 'box' first.
function draw_box(bounds_index)
{
	new google.maps.Rectangle({
		strokeColor: '#0000FF',
		strokeOpacity: 0.8,
		strokeWeight: 2,
		fillColor: '#0000FF',
		fillOpacity: 0.05,
		map: map,
		bounds: bounds[bounds_index].box
	});
}

// return the 'static' elements of a bounds to be saved or displayed
function bounds_static(bounds_index)
{
    var static_bounds = {};
    static_bounds.center = bounds[bounds_index].center;
    static_bounds.zoom = bounds[bounds_index].zoom;
    static_bounds.path = bounds[bounds_index].path;
    static_bounds.finish_index = bounds[bounds_index].finish_index;
    static_bounds.name = bounds[bounds_index].name;
    return static_bounds;
}

// ***********************************************************************************
// ***********************************************************************************
// ************ GENERAL ROUTINES FOR DRAWING TRIP POLYLINE, POINTS, STOPS ************
// ***********************************************************************************
// ***********************************************************************************

// display a trip as a polyline
function draw_trip(map, trip)
{
    // trip data
    var trip_points = new Array();
    var trackpoints = new Array();

    trip_points = trip.split("\n");
      
    for (var i=2; i < trip_points.length-1; i++)
      {
        var point = trip_points[i].split(",");
        var lat = parseFloat(point[5]);
        var lng = parseFloat(point[6]);
        //alert('point '+i+" lat,lng: "+lat+","+lng);
      	trackpoints.push( { lat: lat, lng: lng } );
      }
      
    new google.maps.Polyline({
    		path: trackpoints,
            //icons: [{
            //icon: pathSymbol,
            //offset: '50%'
            //}],
    		geodesic: false,
    		strokeColor: '#FF0000',
    		strokeOpacity: 1.0,
    		strokeWeight: 2
  			}).setMap(map);
}

// display marker for each trip point
function draw_points(map, trip)
{
    // trip data
    var trip_points = new Array();
    var trackpoints = new Array();

    trip_points = trip.split("\n");
      
    for (var i=2; i < trip_points.length-1; i++)
      {
        var point = trip_points[i].split(",");
        var lat = parseFloat(point[5]);
        var lng = parseFloat(point[6]);
		var position = new google.maps.LatLng(lat, lng);
		
		//var color = within_bounds(position, bounds, bounds_box) ? "green" : "red";
		
        var marker = new google.maps.Marker({
            //position: new google.maps.LatLng({ lat: lat, lng: lng}),
            position: position,
            icon: {
                path: google.maps.SymbolPath.CIRCLE,
                scale: 2,
				//strokeColor: color
            },
            map: map
        });

        google.maps.event.addListener(marker, 'mouseover', (function(marker, i, point) {
            return function() {
            var date = new Date(parseInt(point[0])*1000);
            // hours part from the timestamp
            var hours = date.getHours();
            // minutes part from the timestamp
            var minutes = "0" + date.getMinutes();
            // seconds part from the timestamp
            var seconds = "0" + date.getSeconds();

            // will display time in 10:30:23 format
            var formattedTime = hours + ':' + minutes.substr(-2) + ':' + seconds.substr(-2);
            infowindow.setContent('<p>'+formattedTime+'</p>');
            infowindow.open(map, marker);
            }
        })(marker, i, point));
        
        google.maps.event.addListener(marker, 'mouseout', function() {
              infowindow.close();
            }
        );
    }

}

// draw the bus stops on the map
function draw_stops(map, stop_times)
{
    var stop_rows = new Array();

	stop_rows = stop_times.split("\n");

    for (var i = 2; i < stop_rows.length-1; i++) {  
		var point = stop_rows[i].split(",");
		var lat = parseFloat(point[8]);
		var lng = parseFloat(point[9]);

		//if (i == 2) alert(lat+" "+lng);
		
        var marker = new google.maps.Marker({
            //position: new google.maps.LatLng({ lat: lat, lng: lng}),
            position: new google.maps.LatLng(lat, lng),
            icon: 'images/bus_stop.png',
            map: map
        });

        google.maps.event.addListener(marker, 'mouseover', (function(marker, i, point) {
            return function() {
              infowindow.setContent('<p>'+point[7]+'</p>');
              infowindow.open(map, marker);
            }
        })(marker, i, point));
        
        google.maps.event.addListener(marker, 'mouseout', function() {
              infowindow.close();
            }
        );
    }

}

//**********************************************************************************
//**********************************************************************************
//************** GEOMETRIC FUNCTIONS               *********************************
//**********************************************************************************
//**********************************************************************************

// Return distance in m between positions p1 and p2.
// lat/longs in e.g. p1.lat etc
var get_distance = function(p1, p2) {
  var R = 6378137; // Earth's mean radius in meter
  var dLat = rad(p2.lat - p1.lat);
  var dLong = rad(p2.lng - p1.lng);
  var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(rad(p1.lat)) * Math.cos(rad(p2.lat)) *
    Math.sin(dLong / 2) * Math.sin(dLong / 2);
  var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  var d = R * c;
  return d; // returns the distance in meter
};

// Return true is position is inside bounding polygon
// http://stackoverflow.com/questions/13950062/checking-if-a-longitude-latitude-coordinate-resides-inside-a-complex-polygon-in
function within_bounds(lat, lng, bounds_path, box)
{
	// easy optimization - return false if position is outside bounding rectangle (box)
    if (lat > box.north || lat < box.south || lng < box.west || lng > box.east)
        return false;

    var lastPoint = bounds_path[bounds_path.length - 1];
    var isInside = false;
    var x = lng;
    for (var i=0; i<bounds_path.length; i++)
    {
		var point = bounds_path[i];
        var x1 = lastPoint.lng;
        var x2 = point.lng;
        var dx = x2 - x1;

        if (Math.abs(dx) > 180.0)
        {
            // most likely, just jumped the dateline (could do further validation to this effect if needed).  normalise the numbers.
            if (x > 0)
            {
                while (x1 < 0)
                    x1 += 360;
                while (x2 < 0)
                    x2 += 360;
            }
            else
            {
                while (x1 > 0)
                    x1 -= 360;
                while (x2 > 0)
                    x2 -= 360;
            }
            dx = x2 - x1;
        }

        if ((x1 <= x && x2 > x) || (x1 >= x && x2 < x))
        {
            var grad = (point.lat - lastPoint.lat) / dx;
            var intersectAtLat = lastPoint.lat + ((x - x1) * grad);

            if (intersectAtLat > lat)
                isInside = !isInside;
        }
        lastPoint = point;
    }

    return isInside;
}


// http://stackoverflow.com/questions/563198/how-do-you-detect-where-two-line-segments-intersect
// Detect whether lines A->B and C->D intersect
// return { intersect: true/false, position: LatLng (if lines do intersect), progress: 0..1 }
// where 'progress' is how far the intersection is along the A->B path
function intersect(line1, line2)
{
    var A = line1[0], B = line1[1], C = line2[0], D = line2[1];
    
    var s1 = { lat: B.lat - A.lat, lng: B.lng - A.lng };
    var s2 = { lat: D.lat - C.lat, lng: D.lng - C.lng };

    var s = (-s1.lat * (A.lng - C.lng) + s1.lng * (A.lat - C.lat)) / (-s2.lng * s1.lat + s1.lng * s2.lat);
    var t = ( s2.lng * (A.lat - C.lat) - s2.lat * (A.lng - C.lng)) / (-s2.lng * s1.lat + s1.lng * s2.lat);

    if (s >= 0 && s <= 1 && t >= 0 && t <= 1)
    {
        // lines A->B and C->D intersect
        return { success: true, position: { lat: A.lat + (t * s1.lat), lng: A.lng + (t * s1.lng) }, progress: t };
    }

    return { success: false }; // lines don't intersect
}

//*********************************************************************************************
//*********************************************************************************************
//*************** FORMAT FUNCTIONS, TO PRODUCE PRINT STRINGS **********************************
//*********************************************************************************************
//*********************************************************************************************

// -42.123 -> 42 7.54 W (convert float lat to a string with n digits for decimal of minutes)
function format_lat(lat, n)
{
    var NS = 'S';
    if (lat >= 0)
    {
        NS = 'N';
    }
    var deg = Math.floor(Math.abs(lat));
    var min = ((Math.abs(lat) - deg) * 60).toFixed(n);
    if (min < 10)
    {
        min = '0' + min;
    }
    return deg + '&nbsp;' + min + '&nbsp;' + NS;
}

function format_lng(lng, n)
{
    var EW = 'W';
    if (lng >= 0)
    {
        EW = 'E';
    }
    var deg = Math.floor(Math.abs(lng));
    var min = ((Math.abs(lng) - deg) * 60).toFixed(n);
    if (min < 10)
    {
        min = '0' + min;
    }
    return deg + '&nbsp;' + min + '&nbsp;' + EW;
}

// format distance (from meters)
function format_distance(x)
{
    switch (UNITS_DISTANCE) {
        case 'nm':
            return nm(x).toFixed(1);
            break;
        case 'miles':
            return miles(x).toFixed(1);
            break;
        case 'm':
            return Math.round(x);
            break;
        case 'km':
            return (x / 1000).toFixed(1);
            break;
        default: 
            alert('Unknown distance units ' + UNITS_DISTANCE);
            break;
    }
    return 0;
}

// format speed (x in meters/second)
function format_speed(x)
{
    if (x == 0) return '';
    switch (UNITS_SPEED) {
        case 'knots':
            return (x * 3600 * nm(1)).toFixed(1);
            break;
        case 'mph':
            return (x * 3600 * miles(1)).toFixed(1);
            break;
        case 'ms':
            return x.toFixed(1);
            break;
        case 'kmh':
            return (x * 3600 / 1000).toFixed(1);
            break;
        default: 
            alert('Unknown speed units ' + UNITS_SPEED);
            break;
    }
    return 11;
}

// format time for path display
// d is a JS date
function format_time(d)
{
    var hr = d.getHours();
    if (hr < 10) {
        hr = "0" + hr;
    }
    var min = d.getMinutes();
    if (min < 10) {
        min = "0" + min;
    }
    var secs = d.getSeconds();
    if (secs < 10) {
        secs = "0" + secs;
    }
    var time = hr + ":" + min + ":" + secs;

    return time;
}

// convert a js date to "YYYY-MM-DD HH:MM:SS"
function format_datetime(d)
{
	var year = d.getFullYear();
	
	var month = d.getMonth()+1;
	if (month < 10) {
		month = '0'+month;
	}
	
	var day = d.getDate();
	if (day < 10) {
		day = '0'+day;
	}
	
	var date = year+'-'+month+'-'+day;
	
    var hr = d.getHours();
    if (hr < 10) {
        hr = "0" + hr;
    }
    var min = d.getMinutes();
    if (min < 10) {
        min = "0" + min;
    }
    var secs = d.getSeconds();
    if (secs < 10) {
        secs = "0" + secs;
    }
    var time = hr + ":" + min + ":" + secs;

    return date+' '+ time;
}

//*********************************************************************************************
//*********************************************************************************************
//*************** CONVERSION FUNCTIONS, E.G. meters to nautical miles *************************
//*********************************************************************************************
//*********************************************************************************************

// degrees to radians
var rad = function(x) {
  return x * Math.PI / 180;
};

// meters to nautical miles
function nm(x)
{
    return x * 0.000539956803;
}

// meters to statute miles
function miles(x)
{
    return x * 0.000621371;
}

//*********************************************************************************************
//*********************************************************************************************
//*************** STORAGE FUNCTIONS TO STORE/LOAD BOUNDS              *************************
//*********************************************************************************************
//*********************************************************************************************

function load_saves()
{
    // re-initialize the 'bounds' array
    init_bounds();
	
	//load from bounds_cache
	for (var i=0;i<bounds_cache.length;i++)
	{
		bounds.push(bounds_cache[i]);
	}
    
	// read all saves from localStorage (keys prefixed with APP_KEY+'save')
    for (var i = 0; i < localStorage.length; i++)
    {
        var sKey = localStorage.key(i);
		if (sKey.indexOf(APP_KEY+SAVE_KEY) != 0)
		{
			continue; // skip this localStorage value if not for this app
		}
        var saved_bounds = JSON.parse(localStorage.getItem(sKey));
        bounds.push( saved_bounds );
        //bounds_index = bounds.length - 1;
        //bounds[bounds_index].name = sKey.slice((APP_KEY+SAVE_KEY).length);
    }
    for (var bounds_index=1; bounds_index<bounds.length; bounds_index++)
    {
        bounds[bounds_index].checked = false; //debug should remember if was previously checked
        bounds[bounds_index].vehicles = {}; // initially empty object to store per-vehicle crossing timestamps
        bounds[bounds_index].box = update_box(bounds_index);        
    }
}

// clear and re-display the tabular list of saves (below the path table, if there is one)
function display_saves()
{
    // clear saves display
    while (saves_element.firstChild) {
        saves_element.removeChild(saves_element.firstChild);
    }

	// display saves as table
    var saves_table = document.createElement('TABLE');
    saves_table.className = "saves_table";

    for (var i = 0; i < bounds.length; i++ )
    {
		// start empty row
        var save_row = document.createElement('TR');
		
		// add save name TD
		var save_name = document.createElement('TD');
        save_name.innerHTML = bounds[i].name;
		//save_name.onclick = function (x) { return function () { user_load_bounds(x)}} (i);
		save_name.title = 'Load this trip';
		save_name.className = 'save_name';
		save_row.appendChild(save_name);
		
		// add select checkbox TD
		var select_save = document.createElement('TD');
        var checkbox = document.createElement('INPUT');
        checkbox.type = "checkbox";
        checkbox.value = i;
        checkbox.id = 'bounds_'+i;
		checkbox.onclick = function (x) { return function () { user_select_save(x)}} (i);
		select_save.appendChild(checkbox);
		select_save.title = 'Enable or disable these bounds';
		select_save.className = 'save_select';
		save_row.appendChild(select_save);
		
		// add view icon TD
		var view_icon = document.createElement('TD');
		var img_element = document.createElement('IMG');
		img_element.src = 'images/view.png';
		view_icon.appendChild(img_element);
		view_icon.onclick = function (x) { return function () { user_view_save(x)}} (i);
		view_icon.title = 'View these saved bounds';
		view_icon.className = 'save_view';
		save_row.appendChild(view_icon);
		
		// add delete icon TD
		var delete_icon = document.createElement('TD');
		img_element = document.createElement('IMG');
		img_element.src = 'images/delete.png';
		delete_icon.appendChild(img_element);
		delete_icon.onclick = function (x) { return function () { user_delete_save(x)}} (i);
		delete_icon.title = 'Delete these bounds';
		delete_icon.className = 'save_delete';
		save_row.appendChild(delete_icon);
		
		// add row for current path to display table
        saves_table.appendChild(save_row);
    }
	
	// display the whole table
	saves_element.appendChild(saves_table);
}

// save the current path with a given name to localStorage
function save_bounds(save_name)
{
    if(typeof(Storage) !== "undefined") {
        // Code for localStorage/sessionStorage.
		var bounds_data = {  center: { lat: map.getCenter().lat(),
									   lng: map.getCenter().lng()
								     },
							 zoom: map.getZoom(),
							 path: new Array(),
                             finish_index: bounds[0].finish_index,
                             name: save_name
							};
		for (var i = 0; i < bounds[0].path.length; i++)
		{
			bounds_data.path.push({ lat: bounds[0].path[i].lat,
								lng: bounds[0].path[i].lng
								
			});
		}
        localStorage.setItem(APP_KEY+SAVE_KEY+save_name, JSON.stringify(bounds_data));
    } else {
        alert('Sorry, your browser does not allow saving of data (try a newer one...)');
    }
}

//*********************************************************************************************
//*********************************************************************************************
//*************** USER BUTTONS                                        *************************
//*********************************************************************************************
//*********************************************************************************************


// clear the bounds polygon off the map and reset the bounds data to empty
function user_clear_bounds(bounds_index)
{
  bounds[bounds_index].path = new Array();
  uncheck_bounds(bounds_index);
}

// user chose a new speed units drop-down
function change_speed_units()
{
    UNITS_SPEED = status_speed_units.value;
    //alert('speed units now '+UNITS_SPEED);
    display_path(path);
}

// user chose a new distance units drop-down
function change_distance_units()
{
    UNITS_DISTANCE = status_distance_units.value;
    display_path(path);
}

// user clicked the delete icon next to a save name
function user_delete_save(bounds_index)
{
    localStorage.removeItem(APP_KEY+SAVE_KEY+bounds[bounds_index].name);
    load_saves();
    display_saves();
}

function user_select_save(bounds_index)
{
    var checkbox = document.getElementById('bounds_'+bounds_index);

    if (checkbox.checked)
    {
        bounds[bounds_index].checked = true;
        draw_bounds(bounds_index);
    } else
    {
        uncheck_bounds(bounds_index);
    }
}

function uncheck_bounds(bounds_index)
{
  bounds[bounds_index].checked = false;
  if (bounds[bounds_index].marker) bounds[bounds_index].marker.setMap(null);
  if (bounds[bounds_index].start_polyline) bounds[bounds_index].start_polyline.setMap(null);
  if (bounds[bounds_index].finish_polyline) bounds[bounds_index].finish_polyline.setMap(null);
  if (bounds[bounds_index].polygon) bounds[bounds_index].polygon.setMap(null);
}

// user clicked the 'view saved bounds' icon next to a save name
function user_view_save(bounds_index)
{
	var bounds_data = bounds_static(bounds_index);
	alert(JSON.stringify(bounds_data));
}

// prompt the user for a name and save current path to localStorage
function user_save_bounds()
{
    if(typeof(Storage) !== "undefined") {
        // Code for localStorage/sessionStorage.
		if (bounds[0].path.length == 0)
		{
			alert("You have no current bounds to save");
			return;
		}
        var save_name = prompt("Enter name for saved bounds below:", "default bounds");
        if (save_name === null) {
            return; //break out of the function early
        }
        save_bounds(save_name);
        load_saves();
        display_saves();
    } else {
        alert('Sorry, your browser does not allow saving of data (try a newer one...)');
    }
}

// user has clicked button to 'clear' the tracked items, i.e. individual buses or all buses on a route
function user_clear_tracking()
{
	track_id = '';
	track_route_id = '';
}

// the user has clicked on a track_id in an infowindow, so it should be set to be 'tracked'
function user_track_id(vehicle_id)
{
    track_id = vehicle_id;
}

// the user has clicked on a route_id in an infowindow, so it should be set to be 'tracked'
function user_track_route_id(route_id)
{
    track_route_id = route_id;
}

// generate infowindow content for a given 'position' object
// to be displayed when the user  clicks on marker for that position
function window_content(position)
{
    var route_id = position.route_id ? position.route_id : 'no_route' ;
	
    return '<p><a href="#" onclick="user_track_id('+"'"+position.id+"'"+')">'+position.id +'</a><br/>'
				+ '<a href="#" onclick="user_track_route_id('+"'"+route_id+"'"+')">'+route_id +'</a><br/>'
                + format_datetime(new Date(position.timestamp * 1000))
                + '</p>';
}


// given position strings from data.csv, create 'positions' object
// as of Oct 2015 the fields in data.csv are:
//      timestamp,id,label,route_id,trip_id,latitude,longitude,bearing,current_stop_sequence,stop_id
// which will be stored as:
//      positions['id'] = { timestamp: T, id: I, label: L ... }
function parse_positions(position_strings)
{
    // strip of the first record and parse as the position record (csv) key names
    var position_keys = position_strings[0].split(',');
    
    // COPY all previous_positions into positions
    positions = JSON.parse(JSON.stringify(previous_positions));
    
    // UPDATE 'positions' adding all new positions (and overwriting previous where appropriate)
    for (var i=1; i<position_strings.length; i++)
    {
        // split current position string into an array of constituent fields
        var position_key_strings = position_strings[i].split(',');
        // map those fields to attributes in a position object
        var position = {};
        // create position record from each string value
        for (var key_index=0; key_index<position_keys.length; key_index++)
        {
            if (position_key_strings[key_index])
            {
                position[position_keys[key_index]] = position_key_strings[key_index];
            }
        }
        // ignore position records that have no 'id' field (although they all seem to have this field)
        if (position.hasOwnProperty('id') && position.hasOwnProperty('timestamp'))
        {
            // note we create new 'position' entry for current vehicle
            // but propagate forward the existing 'status'
            var status = { color: 'gray' };
            if ( positions[position.id] && positions[position.id].status )
            {
                status = positions[position.id].status;
            }
            positions[position.id] = position;
            positions[position.id].status = status;
            positions[position.id].status.route_id = positions[position.id].route_id ? positions[position.id].route_id : 'no_route';
        }
    }    
}

// ********************************************************************************************************************
// ********************************************************************************************************************
// process_positions()
//
// Called after each data update.
//
// This is the key routine where we analyze the position of each vehicle and calculate new start/completed times
//
// iterate through ALL positions in 'positions' dictionary an process as needed
// e.g. for bounds entry / exit
// ********************************************************************************************************************
// ********************************************************************************************************************
function process_positions(positions, feed_timestamp)
{
    test_positions(positions, feed_timestamp);
    update_map(positions);
    // copy each position record into an object with key 'vehicle_id'
    for (var i=0; i<positions.length; i++)
        {
          if (positions[i].vehicle_id != null)
              {
                  previous_positions[positions[i].vehicle_id]  = positions[i];
              }
        }
}

function test_positions(positions, feed_timestamp)
{
    //
    // ** Now iterate through all 'positions' data points and update status of each point, e.g. in bounds
    //
    // calculate time range of position data
    var mintime = new Date(9999999999);
    var maxtime = new Date(0);
    var aged_count = 0;
    var positions_count = 0;

    // iterate through each of the position records we've received, tested each against each checked bounds
    for (var i=0; i<positions.length; i++ )
    {
        if (positions[i].vehicle_id != null)
        {
            var p = positions[i]; // shortcut

            p.status = {}; // use to store derived properties, like 'aged'
            positions_count++;
            
            // update mintime and maxtime (for range of times in current sample)
            if (p.timestamp < mintime) mintime = p.timestamp;
            if (p.timestamp > maxtime) maxtime = p.timestamp;

            // Check this position record against each of the bounds
            for (var bounds_index = 0; bounds_index < bounds.length; bounds_index++)
            {
                test_bounds(bounds_index, p);
            }
        }
    }
        // update page status line and heading
    var mindate_string = format_datetime(new Date(mintime*1000));
    var maxdate_string = format_datetime(new Date(maxtime*1000));
    
    document.getElementById('heading').innerHTML = '{'+mindate_string+'}..{'+maxdate_string+'}';
    document.getElementById('status').innerHTML = ' data received ' + 
                                format_datetime(new Date()) + 
                                ' for ' + positions_count+' positions ('+aged_count+' aged 2+ mins)';    
}

// check the current position record 'p' against bounds[bounds_index]
function test_bounds(bounds_index, p)
{
    // check position relative to BOUNDS
    if (bounds[bounds_index].checked &&
        bounds[bounds_index].path.length > 2 &&
        previous_positions.hasOwnProperty(p.vehicle_id))
    {
        var was_in_bounds = bounds[bounds_index].vehicles[p.vehicle_id] &&
                            bounds[bounds_index].vehicles[p.vehicle_id].in_bounds;

        var in_bounds = within_bounds(p.latitude,
                                      p.longitude,
                                      bounds[bounds_index].path,
                                      bounds[bounds_index].box);

        if ( in_bounds && ! was_in_bounds)
        {
            // BOUNDS ENTERED
            bounds[bounds_index].vehicles[p.vehicle_id] = { in_bounds: true };
            bounds_entered(bounds_index, p);
        } else if (was_in_bounds && ! in_bounds)
        {
            bounds[bounds_index].vehicles[p.vehicle_id].in_bounds = false;
            // BOUNDS EXITTED
            bounds_exitted(bounds_index, p);
        }
    }
}

// BUS has just ENTERED BOUNDS (but cound be from any angle)
function bounds_entered(bounds_index, p)
{
    // bus has just ENTERED bounds
    // write to console1
    // see if this bus crossed start line
    var start_line = [ bounds[bounds_index].path[0], bounds[bounds_index].path[1] ];
    var vehicle_path = [ { lat: previous_positions[p.vehicle_id].latitude,
                           lng: previous_positions[p.vehicle_id].latitude },
                         { lat: p.latitude, lng: p.longitude } ];
    var timestamp_delta = p.timestamp - previous_positions[p.vehicle_id].timestamp;
    
    var start_intersect = intersect(vehicle_path, start_line);
    // initialize bounds vehicle entry
    bounds[bounds_index].vehicles[p.vehicle_id].start_intersect = start_intersect ;
    if ( start_intersect.success )
    {
        // CLEAN ENTRY
        
        // calculate start_time
        
        bounds[bounds_index].vehicles[p.vehicle_id].start_timestamp = previous_positions[p.vehicle_id].timestamp + 
                                                            timestamp_delta * start_intersect.progress;
        bounds_start(bounds_index, p);
    } else {
        // EARLY ENTRY
        write_console1( format_time(new Date(p.timestamp * 1000)) + 
                    ' ' + bounds[bounds_index].name + ', ' +
                    ' Bus <a href="#" onclick="user_track_id(' + p.vehicle_id + ')">' + p.vehicle_id + '</a> ' +
                    ', route: ' + (p.route_id ? p.route_id : 'no_route') + 
                    ' (' +
                    format_time(new Date(timestamp_delta * 1000)) +
                    ')' +
                    ' early entry' +
                    '<br/>' );
    }
}

// BUS has just EXITTED bounds
function bounds_exitted(bounds_index, p)
{
    var route = p.route_id ? p.route_id : 'no_route';

    // check to see if the exit vector crosses the finish line
    var finish_line = finish_path(bounds_index);

    var vehicle_path = [ { lat: previous_positions[p.vehicle_id].latitude,
                           lng: previous_positions[p.vehicle_id].latitude },
                         { lat: p.latitude, lng: p.longitude } ];
    var timestamp_delta = p.timestamp - previous_positions[p.vehicle_id].timestamp;
    p.status.color = 'gray';
    
    var finish_intersect = intersect(vehicle_path, finish_line);
    
    bounds[bounds_index].vehicles[p.vehicle_id].finish_intersect = finish_intersect;
    
    // if success, then we have { intersect: true, position: <position of intersect point> }
    if (finish_intersect.success)
    {
        // CLEAN EXIT
        if (bounds[bounds_index].vehicles[p.vehicle_id].start_intersect.success)
        {
            // BOUNDS COMPLETED
            var finish_timestamp = previous_positions[p.vehicle_id].timestamp +
                                   timestamp_delta * finish_intersect.progress;
                                                            
            bounds[bounds_index].vehicles[p.vehicle_id].duration = finish_timestamp -
                                                                 bounds[bounds_index].vehicles[p.vehicle_id].start_timestamp;

            bounds[bounds_index].vehicles[p.vehicle_id].finish_timestamp = finish_timestamp;
            bounds_completed(bounds_index, p);
        } else {
            // EARLY EXIT (only crossed finish line, not start line)
            write_console1( format_time(new Date(p.timestamp * 1000)) + 
                            ' ' + bounds[bounds_index].name + ', ' +
                            ' Bus <a href="#" onclick="user_track_id(' + p.vehicle_id + ')">' + p.vehicle_id + '</a> ' +
                            ' (' +
                            format_time(new Date(timestamp_delta * 1000)) +
                            ')' + 
                            ' clean exit (no start)' +
                            '<br/>');
        }
    } else {
        // EXIT did NOT cross finish line
        // if the bus has exitted the bounds without intersecting finish line, delete the entry polyline
        write_console1( format_time(new Date(p.timestamp * 1000)) + 
                        ' ' + bounds[bounds_index].name + ', ' +
                        ' Bus <a href="#" onclick="user_track_id(' + p.vehicle_id + ')">' + p.vehicle_id + '</a> ' +
                        ' (' +
                        format_time(new Date(timestamp_delta * 1000)) +
                        ')' + 
                        ' early exit' +
                        '<br/>');
    }
}

// BUS has just entered bounds via startline (aka CLEAN ENTRY)
function bounds_start(bounds_index, p)
{
    var timestamp_delta = p.timestamp - previous_positions[p.vehicle_id].timestamp;
    // clean entry so set marker color to green
    p.status.color = '#66cc00';
    
    write_console1( format_time(new Date(p.timestamp * 1000)) + 
                ' ' + bounds[bounds_index].name + ', ' +
                ' Bus <a href="#" onclick="user_track_id(' + "'" + p.vehicle_id + "'" + ')">' +
                vehicle_id + '</a>' +
                ', route: ' + (p.route_id ? p.route_id : 'no_route') + 
                    ' (' +
                    format_time(new Date(timestamp_delta * 1000)) +
                    ' x ' + bounds[bounds_index].vehicles[p.vehicle_id].start_intersect.progress.toFixed(2) +
                    ')' +
                ', <font color="green">' + 'STARTED' + 
                ' ' + format_time(new Date(bounds[bounds_index].vehicles[p.vehicle_id].start_timestamp)) +
                '</font>' +
                '<br/>');
	
    // draw entry vector
    polyline_entry[vehicle_id] = new google.maps.Polyline({
        path: [ { lat: previous_positions[p.vehicle_id].latitude, lng: previous_positions[p.vehicle_id].longitude },
                { lat: p.latitude, lng: p.longitude }],
        geodesic: false,
        strokeColor: '#009900',
        strokeOpacity: 1.0,
        strokeWeight: 2,
        map: map
        });
}

// BUS has just COMPLETED transit of bounds
function bounds_completed(bounds_index, p)
{
    // GOOD RUN
    var timestamp_delta = p.timestamp - previous_positions[p.vehicle_id].timestamp;
    // Bus crossed finish line AND start line
    var op = format_datetime(new Date(p.timestamp * 1000)) + 
                    ',' + bounds[bounds_index].name +
                    ',<a href="#" onclick="user_track_id(' + p.vehicle_id + ')">' + p.vehicle_id + '</a>' +
                    ',' + (p.route_id ? p.route_id : 'no_route') + 
                    ',' +
                    format_time(new Date(timestamp_delta * 1000)) +
                    ',' + bounds[bounds_index].vehicles[p.vehicle_id].finish_intersect.progress.toFixed(2) +
                    ',<font color="green">' +
                    'COMPLETED' +
                    ',' + format_time(new Date(bounds[bounds_index].vehicles[p.vehicle_id].start_timestamp)) +
                    ',' + format_time(new Date(bounds[bounds_index].vehicles[p.vehicle_id].finish_timestamp)) +
                    ',' + format_time(new Date(bounds[bounds_index].vehicles[p.vehicle_id].duration)) +
                    '</font>' + 
                    '<br/>';
                    
    write_console1(op);
    write_console2(op);

    // draw exit vector
    polyline_exit[vehicle_id] = new google.maps.Polyline({
        path: [ { lat: previous_positions[p.vehicle_id].latitude, lng: previous_positions[p.vehicle_id].longitude },
                { lat: p.latitude, lng: p.longitude }],
        geodesic: false,
        strokeColor: '#ff3333',
        strokeOpacity: 1.0,
        strokeWeight: 2,
        map: map
        });
}

// ********************************************************************************************************************
// ********************************************************************************************************************
//
// MAP UPDATE
//

// draw the vehicle positions from the rita_feed onto the map
function map_feed(feed)
{
    for (var i=0; i< markers.length; i++)
    {
        markers[i].setMap(null);
    }
    markers = [];

    var positions = feed.entities;

    var marker_count = 0;
    for (var i=0; i < positions.length; i++)
    {
        var lat = positions[i].latitude;
        var lng = positions[i].longitude;
        if (lat < map_bounds.n && lat > map_bounds.s && lng > map_bounds.w && lng < map_bounds.e)
        {
        marker_count++;        
            var marker = new google.maps.Marker({
                position: { lat: lat, lng: lng },
                icon: {
                    path: google.maps.SymbolPath.CIRCLE,
                    scale: 4,
                    strokeColor: 'green'
                },
                map: map
            });

            markers.push(marker);
        }
    }
    //write_console1('markers: '+marker_count);
}
                                             
// Draw the positions on the map, with infowindow open if appropriate
function update_map(positions)
{

    if (!DISPLAY_UPDATE_MAP_POINTS) return;
    
    // 'delete' (setMap(null)) markers if TRAIL is false
    if (! TRAIL)
    {
        for (var i=0; i< markers.length; i++)
        {
            markers[i].setMap(null);
        }
        markers = [];
    }
    for (var i=0; i < positions.length; i++)
    {
        if (positions[i].vehicle_id != null)
        {
            var marker = new google.maps.Marker({
                position: { lat: positions[i].latitude, lng: positions[i].longitude },
                icon: {
                    path: google.maps.SymbolPath.CIRCLE,
                    scale: 4,
                    //strokeColor: positions[i].status.color
                    strokeColor: 'blue'
                },
                map: map
            });

            markers.push(marker);
            
            var infowindow_content = window_content(positions[i]);
                                            
            var open_infowindow = function(marker, content) {
                    return function() {
                      if (!marker.infowindow)
                      {
                        marker.infowindow = new google.maps.InfoWindow();
                      }
                      marker.infowindow.setContent(content);
                      marker.infowindow.open(map, marker);
                    }
                }(marker, infowindow_content );
            
            var click_infowindow = function(open_infowindow) {
                    return function() {
                      open_infowindow();
                    }
                }(open_infowindow);
                
            google.maps.event.addListener(marker, 'click', click_infowindow );
            
            // open the inforwindows for buses being 'tracked'
            if (positions[i].vehicle_id == track_id || positions[i].route_id == track_route_id )
            {
                open_infowindow();
            }
        }
    }
}

function write_console1(msg)
{
    if (!DISPLAY_UPDATE_CONSOLE1) return;
    
    var c = document.getElementById('console1');
    c.innerHTML += msg;
}

function write_console2(msg)
{
    var c = document.getElementById('console2');
    c.innerHTML += msg;
}

function debug()
{
    alert(bounds.length);
    return;
    // ,,,,{"success":false}
    var A = {lat:52.1982955933,lng:0.12819583714};            // previous_positions[p.vehicle_id].status.position
    var B = {lat:52.1980247498,lng:0.128483206034};           // status.position
    var C = {lat:52.20049890370036,lng:0.13724327087402344}; // bounds_finishpath[0]
    var D = {lat:52.1964480845274,lng:0.12093544006347656};   // bounds_finishpath[1]
    // finish line
    new google.maps.Polyline({
                        path: [A,B],
                        strokeColor: '#990000',
                        strokeOpacity: 1.0,
                        strokeWeight: 4,
                        editable: false,
                        map: map
                      });
    // exit vector
    new google.maps.Polyline({
                        path: [C,D],
                        strokeColor: '#000099',
                        strokeOpacity: 1.0,
                        strokeWeight: 4,
                        editable: false,
                        map: map
                      });
    var finish_intersect = intersect([A,B],[C,D]);
    alert(JSON.stringify(finish_intersect));
}

