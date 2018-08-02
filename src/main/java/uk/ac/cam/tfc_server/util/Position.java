package uk.ac.cam.tfc_server.util;
    
import java.util.ArrayList;

import io.vertx.core.json.JsonObject;

// Position simply stores a lat/long/timestamp tuple
// and provides some utility methods, such as distance from another Position.
public class Position {
    public double lat;
    public double lng;
    public Long ts;

    public Position()
    {
        lat = 0.0;
        lng = 0.0;
        ts = 0L;
    }

    public Position(JsonObject p)
    {
        lat = p.getDouble("lat");
        lng = p.getDouble("lng");
        ts  = p.getLong("ts",0L);
    }
    
    public Position(double init_lat, double init_lng)
    {
        this(init_lat, init_lng, 0L);
    }

    public Position(double init_lat, double init_lng, Long init_ts)
    {
        lat = init_lat;
        lng = init_lng;
        ts = init_ts;
    }

    public String toString()
    {
        return "{ \"lat\": " + String.valueOf(lat) + "," +
            "\"lng\": " + String.valueOf(lng) + "," +
            "\"ts\": " + String.valueOf(ts) +
            "}";
    }

    // return a copy of this position object (so lat/lng can be modified)
    public Position copy()
    {
        return new Position(lat, lng, ts);
    }

    public JsonObject toJsonObject()
    {
        JsonObject jo = new JsonObject();
        jo.put("lat", lat);
        jo.put("lng", lng);
        jo.put("ts", ts);
        return jo;
    }
    
    // Return distance in m between positions p1 and p2.
    // lat/longs in e.g. p1.lat etc
    public double distance(Position p) {
        //double R = 6378137.0; // Earth's mean radius in meter
        double R = 6380000.0; // Earth's radius at Lat 52 deg in meter
        double dLat = Math.toRadians(p.lat - lat);
        double dLong = Math.toRadians(p.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(p.lat)) *
                Math.sin(dLong / 2) * Math.sin(dLong / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double d = R * c;
        return d; // returns the distance in meter
    };

    // return true if this Position is INSIDE the rectangle sw..ne.
    // http://stackoverflow.com/questions/13950062/checking-if-a-longitude-latitude-coordinate-resides-inside-a-complex-polygon-in
    public boolean inside_box(Position sw, Position ne) 
    {
        // this assumes the box doesn't cross lng +/- 180...
        return (lat < ne.lat && lat > sw.lat && lng > sw.lng && lng < ne.lng);
    }

    // Return true if this Position is INSIDE the polygon (clockwise ArrayList of Positions)
    // The fast algorithm is to count the number of times a line North from this point
    // intersects an edge of the polygon. Odd # of intersections => inside. (try it on paper..)
    public boolean inside(ArrayList<Position> polygon) 
    {
        // First we'll do a bounding box test, as an optimization:
        Position sw = polygon.get(0).copy(); // we'll find the bottom-left corner in 'sw'
        Position ne = polygon.get(0).copy(); // and the top-right corner in 'ne'
        for (int i=1; i<polygon.size(); i++)
        {
            Position vertex = polygon.get(i);
            sw.lat = Math.min(sw.lat, vertex.lat);
            sw.lng = Math.min(sw.lng, vertex.lng);
            ne.lat = Math.max(ne.lat, vertex.lat);
            ne.lng = Math.max(ne.lng, vertex.lng);
        }
        if (!inside_box(sw, ne))
        {
            return false;
        }

        // ok... so we're inside the bounding box so let's do the full check
        Position lastPoint = polygon.get(polygon.size() - 1);
        boolean isInside = false;
        double x = lng;
        for (int i=0; i<polygon.size(); i++)
        {
            Position point = polygon.get(i);
            double x1 = lastPoint.lng;
            double x2 = point.lng;
            double dx = x2 - x1;

            if (Math.abs(dx) > 180.0)
            {
                // we have, most likely, just jumped the dateline.  Normalise the numbers.
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
                double grad = (point.lat - lastPoint.lat) / dx;
                double intersectAtLat = lastPoint.lat + ((x - x1) * grad);

                if (intersectAtLat > lat)
                isInside = !isInside;
            }
            lastPoint = point;
        }

        return isInside;
    }

}
