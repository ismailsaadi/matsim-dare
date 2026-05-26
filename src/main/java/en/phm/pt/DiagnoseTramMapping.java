package en.phm.pt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Diagnose why pt2matsim's tram mapping sometimes falls back to straight
 * artificial lines instead of following real Metrolink infrastructure.
 *
 * Reads the mapped network + schedule, classifies every link each tram route
 * uses, identifies stop-to-stop segments that contain artificial links, and
 * writes three CSVs into {@code pt2matsim/output/diagnostics/}:
 *
 * <ul>
 *   <li>{@code tram_mapping_per_route.csv} — one row per tram route, with
 *       counts of embedded / dedicated / artificial links, total and
 *       artificial length, and the most likely cause for any artificial
 *       segments.</li>
 *   <li>{@code tram_mapping_per_segment.csv} — one row per stop-to-stop
 *       segment that uses at least one artificial link. Includes the
 *       coordinates of both stops, distance to the nearest tram-mode link
 *       from each stop, and a categorical hint (e.g. stop too far from any
 *       tram link, both stops fine -> subnetwork disconnect / one-way).</li>
 *   <li>{@code tram_mapping_per_stop.csv} — every stop on every tram route,
 *       with its attached link, nearest tram-mode link, and candidate counts
 *       inside the default 80 m and a wider 200 m radius.</li>
 * </ul>
 *
 * Classification heuristics:
 *   - Artificial link: {@code "artificial"} is in {@code allowedModes}, or the
 *     link id starts with {@code pt_} / {@code pt2matsim_} / contains
 *     {@code "artificial"} (pt2matsim's usual naming).
 *   - Dedicated tram link: id starts with {@code tram_} (from
 *     {@link PreparePTNetwork#addDedicatedTramLinkWithOsmId}).
 *   - Embedded street-running: has tram in modes but is not a dedicated
 *     {@code tram_*} link — typically a road link that {@code PreparePTNetwork}
 *     enriched with the {@code tram} mode.
 */
public final class DiagnoseTramMapping {

    private static final String NETWORK_FILE  = "pt2matsim/output/multimodal_network.xml.gz";
    private static final String SCHEDULE_FILE = "pt2matsim/output/manchester_schedule.xml.gz";
    private static final String OUT_DIR       = "pt2matsim/output/diagnostics/";

    /** pt2matsim default candidate radius around each stop. */
    private static final double CANDIDATE_RADIUS_M = 80.0;
    /** Wider radius reported for context. */
    private static final double WIDE_RADIUS_M = 200.0;

    private enum LinkKind { EMBEDDED, DEDICATED, ARTIFICIAL, OTHER }

    public static void main(String[] args) throws Exception {
        String networkFile  = args.length > 0 ? args[0] : NETWORK_FILE;
        String scheduleFile = args.length > 1 ? args[1] : SCHEDULE_FILE;
        String outDir       = args.length > 2 ? args[2] : OUT_DIR;
        new File(outDir).mkdirs();

        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkFile);
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile(scheduleFile);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network = scenario.getNetwork();
        TransitSchedule schedule = scenario.getTransitSchedule();

        // Collect tram-allowed links once. ~few thousand at most for GM, so an
        // O(stops * tramLinks) nearest-link search is fast enough without a
        // spatial index.
        List<Link> tramLinks = new ArrayList<>();
        int nEmb = 0, nDed = 0, nArt = 0;
        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains("tram") && classify(link) != LinkKind.ARTIFICIAL) continue;
            if (link.getAllowedModes().contains("tram")) tramLinks.add(link);
            switch (classify(link)) {
                case EMBEDDED   -> nEmb++;
                case DEDICATED  -> nDed++;
                case ARTIFICIAL -> nArt++;
                default -> {}
            }
        }
        System.out.printf(Locale.ROOT, "Network has %,d tram-mode links (embedded=%,d, dedicated=%,d). Artificial pt2matsim links in network: %,d%n",
                tramLinks.size(), nEmb, nDed, nArt);

        String perRoutePath   = outDir + "tram_mapping_per_route.csv";
        String perSegmentPath = outDir + "tram_mapping_per_segment.csv";
        String perStopPath    = outDir + "tram_mapping_per_stop.csv";

        int routesSeen = 0, routesWithArtificial = 0;

        try (PrintWriter perRoute   = new PrintWriter(perRoutePath);
             PrintWriter perSegment = new PrintWriter(perSegmentPath);
             PrintWriter perStop    = new PrintWriter(perStopPath)) {

            perRoute.println("transit_line,route_id,num_links,n_embedded,n_dedicated,n_artificial,n_other,"
                    + "total_length_m,artificial_length_m,artificial_share,n_stops,n_stops_far_from_tram,primary_hint");
            perSegment.println("transit_line,route_id,seg_idx,from_stop_id,to_stop_id,"
                    + "from_x,from_y,to_x,to_y,straight_dist_m,segment_link_count,n_artificial,artificial_length_m,"
                    + "from_nearest_tram_m,from_n_within_80m,to_nearest_tram_m,to_n_within_80m,hint");
            perStop.println("transit_line,route_id,stop_idx,stop_id,stop_x,stop_y,"
                    + "attached_link_id,attached_link_kind,nearest_tram_link_id,nearest_tram_link_dist_m,"
                    + "n_within_80m,n_within_200m,hint");

            for (TransitLine line : schedule.getTransitLines().values()) {
                for (TransitRoute route : line.getRoutes().values()) {
                    if (!"tram".equalsIgnoreCase(route.getTransportMode())) continue;
                    routesSeen++;
                    int artCount = diagnoseRoute(line, route, network, tramLinks,
                            perRoute, perSegment, perStop);
                    if (artCount > 0) routesWithArtificial++;
                }
            }
        }

        System.out.println("================================================================");
        System.out.printf(Locale.ROOT, "Tram routes inspected         : %,d%n", routesSeen);
        System.out.printf(Locale.ROOT, "Routes with artificial links  : %,d%n", routesWithArtificial);
        System.out.println("Per-route summary             : " + perRoutePath);
        System.out.println("Per-segment (problem only)    : " + perSegmentPath);
        System.out.println("Per-stop                      : " + perStopPath);
        System.out.println("================================================================");
        System.out.println("Sort tram_mapping_per_route.csv by artificial_share DESC to find the worst offenders,");
        System.out.println("then join with tram_mapping_per_segment.csv on (transit_line, route_id) for hints.");
    }

    /** Returns the number of artificial links used by this route. */
    private static int diagnoseRoute(TransitLine line, TransitRoute route, Network network,
                                     List<Link> tramLinks,
                                     PrintWriter perRoute, PrintWriter perSegment, PrintWriter perStop) {

        // Full link sequence of the mapped route
        List<Id<Link>> linkSeq = new ArrayList<>();
        linkSeq.add(route.getRoute().getStartLinkId());
        linkSeq.addAll(route.getRoute().getLinkIds());
        if (!route.getRoute().getStartLinkId().equals(route.getRoute().getEndLinkId())) {
            linkSeq.add(route.getRoute().getEndLinkId());
        }

        int nEmbedded = 0, nDedicated = 0, nArtificial = 0, nOther = 0;
        double totalLen = 0, artificialLen = 0;
        for (Id<Link> id : linkSeq) {
            Link link = network.getLinks().get(id);
            if (link == null) { nOther++; continue; }
            totalLen += link.getLength();
            switch (classify(link)) {
                case EMBEDDED   -> nEmbedded++;
                case DEDICATED  -> nDedicated++;
                case ARTIFICIAL -> { nArtificial++; artificialLen += link.getLength(); }
                case OTHER      -> nOther++;
            }
        }

        // Per-stop diagnosis
        List<TransitRouteStop> stops = route.getStops();
        Map<Id<TransitStopFacility>, StopInfo> stopInfo = new LinkedHashMap<>();
        int nFarStops = 0;

        for (int i = 0; i < stops.size(); i++) {
            TransitStopFacility f = stops.get(i).getStopFacility();
            Coord c = f.getCoord();

            Link nearest = null;
            double nearestDist = Double.POSITIVE_INFINITY;
            int n80 = 0, n200 = 0;
            for (Link l : tramLinks) {
                double d = distancePointToSegment(c, l);
                if (d < nearestDist) { nearestDist = d; nearest = l; }
                if (d <= CANDIDATE_RADIUS_M) n80++;
                if (d <= WIDE_RADIUS_M)      n200++;
            }
            if (nearestDist > CANDIDATE_RADIUS_M) nFarStops++;

            Id<Link> attachedId = f.getLinkId();
            Link attached = attachedId != null ? network.getLinks().get(attachedId) : null;
            LinkKind attachedKind = attached != null ? classify(attached) : LinkKind.OTHER;

            StopInfo si = new StopInfo(i, f, attachedId, attachedKind, nearest, nearestDist, n80, n200);
            stopInfo.put(f.getId(), si);

            perStop.printf(Locale.ROOT, "%s,%s,%d,%s,%.3f,%.3f,%s,%s,%s,%.2f,%d,%d,%s%n",
                    csv(line.getId()), csv(route.getId()), i, csv(f.getId()),
                    c.getX(), c.getY(),
                    attachedId == null ? "" : csv(attachedId),
                    attachedKind,
                    nearest == null ? "" : csv(nearest.getId()),
                    nearestDist, n80, n200, stopHint(si));
        }

        // Per-segment: locate each stop's attached link inside linkSeq (walking forward
        // from the previous cursor so loops in the route don't fool us).
        int[] stopPosInSeq = new int[stops.size()];
        Arrays.fill(stopPosInSeq, -1);
        int cursor = 0;
        for (int i = 0; i < stops.size(); i++) {
            Id<Link> stopLinkId = stops.get(i).getStopFacility().getLinkId();
            if (stopLinkId == null) continue;
            for (int j = cursor; j < linkSeq.size(); j++) {
                if (linkSeq.get(j).equals(stopLinkId)) {
                    stopPosInSeq[i] = j;
                    cursor = j;
                    break;
                }
            }
        }

        for (int i = 0; i + 1 < stops.size(); i++) {
            int a = stopPosInSeq[i];
            int b = stopPosInSeq[i + 1];
            if (a < 0 || b < 0 || b < a) continue;

            int segArt = 0;
            double segArtLen = 0;
            int segCount = b - a + 1;
            for (int j = a; j <= b; j++) {
                Link l = network.getLinks().get(linkSeq.get(j));
                if (l == null) continue;
                if (classify(l) == LinkKind.ARTIFICIAL) {
                    segArt++;
                    segArtLen += l.getLength();
                }
            }
            if (segArt == 0) continue;  // only report segments with artificial links

            StopInfo fromSi = stopInfo.get(stops.get(i).getStopFacility().getId());
            StopInfo toSi   = stopInfo.get(stops.get(i + 1).getStopFacility().getId());
            Coord cf = fromSi.facility.getCoord();
            Coord ct = toSi.facility.getCoord();
            double straight = Math.hypot(ct.getX() - cf.getX(), ct.getY() - cf.getY());

            perSegment.printf(Locale.ROOT,
                    "%s,%s,%d,%s,%s,%.3f,%.3f,%.3f,%.3f,%.2f,%d,%d,%.2f,%.2f,%d,%.2f,%d,%s%n",
                    csv(line.getId()), csv(route.getId()), i,
                    csv(fromSi.facility.getId()), csv(toSi.facility.getId()),
                    cf.getX(), cf.getY(), ct.getX(), ct.getY(), straight,
                    segCount, segArt, segArtLen,
                    fromSi.nearestDist, fromSi.nWithin80,
                    toSi.nearestDist,   toSi.nWithin80,
                    segmentHint(fromSi, toSi));
        }

        String primaryHint = routeHint(nArtificial, nFarStops);
        perRoute.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.3f,%d,%d,%s%n",
                csv(line.getId()), csv(route.getId()),
                linkSeq.size(), nEmbedded, nDedicated, nArtificial, nOther,
                totalLen, artificialLen,
                totalLen > 0 ? artificialLen / totalLen : 0.0,
                stops.size(), nFarStops, primaryHint);

        return nArtificial;
    }

    private static LinkKind classify(Link link) {
        Set<String> modes = link.getAllowedModes();
        if (modes.contains("artificial")) return LinkKind.ARTIFICIAL;
        String id = link.getId().toString();
        String idLower = id.toLowerCase(Locale.ROOT);
        if (idLower.startsWith("pt_") || idLower.startsWith("pt2matsim") || idLower.contains("artificial")) {
            return LinkKind.ARTIFICIAL;
        }
        if (!modes.contains("tram")) return LinkKind.OTHER;
        if (id.startsWith("tram_")) return LinkKind.DEDICATED;
        return LinkKind.EMBEDDED;
    }

    private static String stopHint(StopInfo si) {
        if (si.attachedKind == LinkKind.ARTIFICIAL) return "stop_attached_to_artificial_link";
        if (si.nearestDist > CANDIDATE_RADIUS_M)    return "no_tram_link_within_80m";
        if (si.nWithin80 < 2)                       return "only_one_tram_candidate_within_80m";
        return "ok";
    }

    private static String segmentHint(StopInfo from, StopInfo to) {
        boolean fromOk = from.nearestDist <= CANDIDATE_RADIUS_M;
        boolean toOk   = to.nearestDist   <= CANDIDATE_RADIUS_M;
        if (!fromOk && !toOk) return "both_stops_far_from_tram_links";
        if (!fromOk)          return "from_stop_far_from_tram_links";
        if (!toOk)            return "to_stop_far_from_tram_links";
        return "stops_ok_check_subnetwork_disconnect_or_oneway";
    }

    private static String routeHint(int nArtificial, int nFarStops) {
        if (nArtificial == 0) return "ok_no_artificial_links";
        if (nFarStops > 0)    return "stops_far_from_tram_links:" + nFarStops;
        return "tram_subnetwork_disconnect_or_oneway";
    }

    /** Euclidean distance from point p to the segment from->to of the link (metres in EPSG:27700). */
    private static double distancePointToSegment(Coord p, Link link) {
        Coord a = link.getFromNode().getCoord();
        Coord b = link.getToNode().getCoord();
        double ax = a.getX(), ay = a.getY();
        double bx = b.getX(), by = b.getY();
        double px = p.getX(), py = p.getY();
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0.0) return Math.hypot(px - ax, py - ay);
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private record StopInfo(int idx,
                            TransitStopFacility facility,
                            Id<Link> attachedLinkId,
                            LinkKind attachedKind,
                            Link nearestTramLink,
                            double nearestDist,
                            int nWithin80,
                            int nWithin200) {}
}
