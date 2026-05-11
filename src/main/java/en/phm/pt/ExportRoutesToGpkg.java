package en.phm.pt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.Geometries;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Export each agent's selected plan to a GeoPackage for QGIS visualisation.
 *
 *   - One GPKG layer per person, table named after the person id (sanitised).
 *   - Each feature is one leg of the selected plan, with a "type" column:
 *       access     : network-routed walk/bike before the first PT leg of a trip
 *       in_vehicle : PT leg, drawn as a straight line between access and egress stop
 *       egress     : network-routed walk/bike after the last PT leg of a trip
 *       transfer   : network-routed walk/bike between two PT legs (rare)
 *   - Teleported short hops (route type = "generic", e.g. "non_network_walk") are
 *     skipped; they have no meaningful link geometry. Set INCLUDE_TELEPORTS = true
 *     to render them as straight from-link to-link segments instead.
 *   - PT in-vehicle geometry is the straight line between the access and egress
 *     TransitStopFacility coordinates (per the user's request).
 */
public final class ExportRoutesToGpkg {

    private static final String NETWORK_FILE  = "D:/Downloads/multimodal_network.xml.gz";
    private static final String SCHEDULE_FILE = "D:/Downloads/manchester_schedule.xml.gz";
    private static final String PLANS_FILE    = "output_pt_simulation/ITERS/it.0/0.plans.xml.gz";
    private static final String OUTPUT_GPKG   = "D:/Downloads/routes.gpkg";

    private static final int    EPSG          = 27700;
    /** Include teleported short hops (non_network_walk and other route type="generic" legs).
     *  Drawn as straight lines between link centroids. Pure 0-distance bookkeeping legs
     *  (start link == end link, distance == 0) are skipped regardless of this flag. */
    private static final boolean INCLUDE_TELEPORTS = true;

    private static final GeometryFactory GF = new GeometryFactory();
    private static final ObjectMapper JSON  = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(NETWORK_FILE);
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile(SCHEDULE_FILE);
        config.plans().setInputFile(PLANS_FILE);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = scenario.getNetwork();
        TransitSchedule schedule = scenario.getTransitSchedule();

        File outFile = new File(OUTPUT_GPKG);
        if (outFile.exists() && !outFile.delete()) {
            throw new RuntimeException("Could not delete existing " + OUTPUT_GPKG);
        }
        outFile.getParentFile().mkdirs();

        int totalLayers = 0;
        int totalFeatures = 0;

        SimpleFeatureType activityType = buildActivityFeatureType("activities");
        SimpleFeatureBuilder activityBuilder = new SimpleFeatureBuilder(activityType);
        List<SimpleFeature> allActivities = new ArrayList<>();

        try (GeoPackage gpkg = new GeoPackage(outFile)) {
            gpkg.init();

            for (Person person : scenario.getPopulation().getPersons().values()) {
                Plan plan = person.getSelectedPlan();
                if (plan == null) continue;

                String layerName = sanitise(person.getId().toString());
                SimpleFeatureType type = buildFeatureType(layerName);

                List<SimpleFeature> features = buildFeaturesForPerson(person, plan, type, network, schedule);
                if (features.isEmpty()) {
                    System.out.printf("  %-20s : skipped (no drawable legs)%n", person.getId());
                } else {
                    FeatureEntry entry = new FeatureEntry();
                    entry.setTableName(layerName);
                    entry.setSrid(EPSG);
                    entry.setGeometryColumn("geom");
                    entry.setGeometryType(Geometries.LINESTRING);

                    ListFeatureCollection collection = new ListFeatureCollection(type, features);
                    gpkg.add(entry, collection);
                    gpkg.createSpatialIndex(entry);

                    totalLayers++;
                    totalFeatures += features.size();
                    System.out.printf("  %-20s : %d features%n", layerName, features.size());
                }

                allActivities.addAll(buildActivityFeatures(person, plan, activityBuilder));
            }

            if (!allActivities.isEmpty()) {
                FeatureEntry actEntry = new FeatureEntry();
                actEntry.setTableName("activities");
                actEntry.setSrid(EPSG);
                actEntry.setGeometryColumn("geom");
                actEntry.setGeometryType(Geometries.POINT);
                gpkg.add(actEntry, new ListFeatureCollection(activityType, allActivities));
                gpkg.createSpatialIndex(actEntry);
                totalLayers++;
                totalFeatures += allActivities.size();
                System.out.printf("  %-20s : %d features%n", "activities", allActivities.size());
            }
        }

        System.out.println("================================================================");
        System.out.printf("Wrote %d layers / %d features to %s%n", totalLayers, totalFeatures, OUTPUT_GPKG);
        System.out.println("================================================================");
    }

    // ---------------------------------------------------------------------
    // Trip splitting and leg classification
    // ---------------------------------------------------------------------

    private static List<SimpleFeature> buildFeaturesForPerson(
            Person person, Plan plan, SimpleFeatureType type,
            Network network, TransitSchedule schedule) {

        // Split plan into trips on real activity boundaries. "Interaction" sub-activities
        // (pt interaction, walk interaction) stay inside the current trip.
        List<List<Leg>> trips = new ArrayList<>();
        List<Leg> current = new ArrayList<>();
        boolean started = false;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity a) {
                if (!a.getType().contains("interaction")) {
                    if (started && !current.isEmpty()) {
                        trips.add(current);
                        current = new ArrayList<>();
                    }
                    started = true;
                }
            } else if (pe instanceof Leg leg) {
                if (started) current.add(leg);
            }
        }
        if (!current.isEmpty()) trips.add(current);

        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);
        List<SimpleFeature> features = new ArrayList<>();
        int globalLegIdx = 0;

        for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
            List<Leg> trip = trips.get(tripIdx);
            int firstPt = -1, lastPt = -1;
            for (int i = 0; i < trip.size(); i++) {
                if ("pt".equals(trip.get(i).getMode())) {
                    if (firstPt == -1) firstPt = i;
                    lastPt = i;
                }
            }

            for (int i = 0; i < trip.size(); i++) {
                Leg leg = trip.get(i);
                String legType;
                if ("pt".equals(leg.getMode())) legType = "in_vehicle";
                else if (firstPt == -1 || i < firstPt)  legType = "access";
                else if (i > lastPt)                    legType = "egress";
                else                                    legType = "transfer";

                SimpleFeature f = buildLegFeature(fb, person, tripIdx, globalLegIdx,
                        legType, leg, network, schedule);
                if (f != null) {
                    features.add(f);
                    globalLegIdx++;
                }
            }
        }
        return features;
    }

    // ---------------------------------------------------------------------
    // Single-leg feature
    // ---------------------------------------------------------------------

    private static SimpleFeature buildLegFeature(
            SimpleFeatureBuilder fb, Person person, int tripIdx, int legIdx,
            String legType, Leg leg, Network network, TransitSchedule schedule) {

        if (leg.getRoute() == null) return null;
        String routeType = leg.getRoute().getRouteType();

        LineString geom;
        Integer numLinks = null;
        String transitLineId = null;
        String transitRouteId = null;

        if ("default_pt".equals(routeType)) {
            // PT leg — straight line between access and egress stop facility coordinates
            try {
                JsonNode json = JSON.readTree(leg.getRoute().getRouteDescription());
                String accessStr = json.path("accessFacilityId").asText(null);
                String egressStr = json.path("egressFacilityId").asText(null);
                if (accessStr == null || egressStr == null) return null;

                TransitStopFacility access = schedule.getFacilities()
                        .get(Id.create(accessStr, TransitStopFacility.class));
                TransitStopFacility egress = schedule.getFacilities()
                        .get(Id.create(egressStr, TransitStopFacility.class));
                if (access == null || egress == null) return null;

                geom = GF.createLineString(new Coordinate[]{
                        toJts(access.getCoord()), toJts(egress.getCoord())
                });
                transitLineId  = json.path("transitLineId").asText(null);
                transitRouteId = json.path("transitRouteId").asText(null);
            } catch (Exception e) {
                System.err.println("Failed to parse pt route description: " + e.getMessage());
                return null;
            }
        } else if ("links".equals(routeType) && leg.getRoute() instanceof NetworkRoute nr) {
            // Network-routed walk/bike — concatenate link from-to segments
            geom = buildNetworkLineString(nr, network);
            if (geom == null) return null;
            numLinks = 1 + nr.getLinkIds().size()
                    + (nr.getStartLinkId().equals(nr.getEndLinkId()) ? 0 : 1);
        } else if (INCLUDE_TELEPORTS && "generic".equals(routeType)) {
            // Short teleport hop (non_network_walk, walk-with-generic-route).
            // Skip pure bookkeeping legs (zero distance, same link) — they have no spatial extent.
            if (leg.getRoute().getDistance() <= 0.0
                    && leg.getRoute().getStartLinkId().equals(leg.getRoute().getEndLinkId())) {
                return null;
            }
            Link from = network.getLinks().get(leg.getRoute().getStartLinkId());
            Link to   = network.getLinks().get(leg.getRoute().getEndLinkId());
            if (from == null || to == null) return null;
            Coordinate c1 = toJts(centroid(from));
            Coordinate c2 = toJts(centroid(to));
            if (c1.equals2D(c2)) return null;
            geom = GF.createLineString(new Coordinate[]{c1, c2});
        } else {
            return null;
        }

        fb.add(geom);
        fb.add(person.getId().toString());
        fb.add(tripIdx);
        fb.add(legIdx);
        fb.add(legType);
        fb.add(leg.getMode());
        fb.add(formatDeparture(leg.getDepartureTime().orElse(Double.NaN)));
        fb.add((int) leg.getTravelTime().orElse(0.0));
        fb.add(leg.getRoute().getDistance());
        fb.add(transitLineId);
        fb.add(transitRouteId);
        fb.add(numLinks);

        return fb.buildFeature(null);
    }

    private static LineString buildNetworkLineString(NetworkRoute route, Network network) {
        List<Id<Link>> ids = new ArrayList<>();
        ids.add(route.getStartLinkId());
        ids.addAll(route.getLinkIds());
        if (!route.getStartLinkId().equals(route.getEndLinkId())) {
            ids.add(route.getEndLinkId());
        }

        List<Coordinate> coords = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Link link = network.getLinks().get(ids.get(i));
            if (link == null) {
                System.err.println("Link not found in network: " + ids.get(i));
                return null;
            }
            if (i == 0) coords.add(toJts(link.getFromNode().getCoord()));
            coords.add(toJts(link.getToNode().getCoord()));
        }
        if (coords.size() < 2) return null;
        return GF.createLineString(coords.toArray(new Coordinate[0]));
    }

    // ---------------------------------------------------------------------
    // Activities (point layer)
    // ---------------------------------------------------------------------

    private static List<SimpleFeature> buildActivityFeatures(Person person, Plan plan,
                                                             SimpleFeatureBuilder fb) {
        List<SimpleFeature> out = new ArrayList<>();
        int idx = 0;
        for (PlanElement pe : plan.getPlanElements()) {
            if (!(pe instanceof Activity a)) continue;
            if (a.getCoord() == null) continue;
            boolean isReal = !a.getType().contains("interaction");

            Point p = GF.createPoint(toJts(a.getCoord()));
            fb.add(p);
            fb.add(person.getId().toString());
            fb.add(idx);
            fb.add(a.getType());
            fb.add(isReal);
            fb.add(formatDeparture(a.getEndTime().orElse(Double.NaN)));
            out.add(fb.buildFeature(null));
            idx++;
        }
        return out;
    }

    private static SimpleFeatureType buildActivityFeatureType(String name) {
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName(name);
        try {
            b.setCRS(CRS.decode("EPSG:" + EPSG, true));
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode EPSG:" + EPSG, e);
        }
        b.add("geom",      Point.class);
        b.add("person_id", String.class);
        b.add("act_idx",   Integer.class);
        b.add("type",      String.class);
        b.add("is_real",   Boolean.class);
        b.add("end_time",  String.class);
        return b.buildFeatureType();
    }

    // ---------------------------------------------------------------------
    // Schema
    // ---------------------------------------------------------------------

    private static SimpleFeatureType buildFeatureType(String name) {
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName(name);
        try {
            b.setCRS(CRS.decode("EPSG:" + EPSG, true));
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode EPSG:" + EPSG, e);
        }
        b.add("geom",            LineString.class);
        b.add("person_id",       String.class);
        b.add("trip_idx",        Integer.class);
        b.add("leg_idx",         Integer.class);
        b.add("type",            String.class);
        b.add("mode",            String.class);
        b.add("dep_time",        String.class);
        b.add("trav_time_s",     Integer.class);
        b.add("distance_m",      Double.class);
        b.add("transit_line_id", String.class);
        b.add("transit_route_id",String.class);
        b.add("num_links",       Integer.class);
        b.setDefaultGeometry("geom");
        return b.buildFeatureType();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Coordinate toJts(Coord c) {
        return new Coordinate(c.getX(), c.getY());
    }

    private static Coord centroid(Link l) {
        double x = 0.5 * (l.getFromNode().getCoord().getX() + l.getToNode().getCoord().getX());
        double y = 0.5 * (l.getFromNode().getCoord().getY() + l.getToNode().getCoord().getY());
        return new Coord(x, y);
    }

    private static String formatDeparture(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return null;
        int s = (int) seconds;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String sanitise(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) cleaned = "p_" + cleaned;
        return cleaned;
    }
}
