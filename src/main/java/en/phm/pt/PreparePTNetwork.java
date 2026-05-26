package en.phm.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Merges the Greater Manchester Metrolink tram network into a base street network.
 * <p>
 * The merge strategy uses OSM IDs to distinguish between:
 * <ul>
 *   <li><b>Embedded (street-running)</b> tram sections -- where the tram shares the road
 *       surface. The "tram" mode is added to the existing road link.</li>
 *   <li><b>Dedicated</b> tram sections -- where the tram runs on separate infrastructure.
 *       A new parallel link is created with a "tram_&lt;osmID&gt;" identifier.</li>
 * </ul>
 */
public class PreparePTNetwork {

    private static final String EXAMPLE = "pt2matsim/";
    private static final String EXTERNAL = "input/mito/trafficAssignment/";
    private static final String INPUT = EXAMPLE + "input/";
    private static final String OUTPUT = EXAMPLE + "output/";

    public static void main(String[] args) {
        Network baseNetwork = NetworkUtils.readNetwork(EXTERNAL + "network_base.xml");
        Network tramNetwork = NetworkUtils.readNetwork(INPUT + "greater_manchester_metrolink_matsim_network_densified.xml.gz");

        mergeTramNetworkIntoBase(baseNetwork, tramNetwork);

        NetworkUtils.writeNetwork(baseNetwork, OUTPUT + "network_base2.xml.gz");
        System.out.println("Tram network merged successfully.");
    }

    /**
     * Merges tram links into the base network using OSM ID matching.
     *
     * @param baseNetwork the street network to merge into (modified in place)
     * @param tramNetwork the tram network to merge from
     */
    public static void mergeTramNetworkIntoBase(Network baseNetwork, Network tramNetwork) {
        int addedSeparate = 0;
        int mergedEmbedded = 0;

        // Add any tram nodes not already present in the base network. Construct fresh nodes
        // via the base network's factory so they aren't still wired to the tram network.
        for (Node node : tramNetwork.getNodes().values()) {
            if (baseNetwork.getNodes().get(node.getId()) == null) {
                Node newNode = baseNetwork.getFactory().createNode(node.getId(), node.getCoord());
                baseNetwork.addNode(newNode);
            }
        }

        // Build osmID -> link index once, so the per-tram-link lookup is O(1) instead of O(N).
        Map<Long, Link> baseByOsmId = new HashMap<>();
        for (Link link : baseNetwork.getLinks().values()) {
            Object o = link.getAttributes().getAttribute("osmID");
            if (o == null) continue;
            long id = (o instanceof Number) ? ((Number) o).longValue() : Long.parseLong(o.toString());
            baseByOsmId.putIfAbsent(id, link);
        }

        // Process each tram link
        for (Link tramLink : tramNetwork.getLinks().values()) {
            Object tramOsmObj = tramLink.getAttributes().getAttribute("osmID");

            // Fallback for links without an OSM ID (should be rare)
            if (tramOsmObj == null) {
                addDedicatedTramLink(baseNetwork, tramLink, "tram_fallback_");
                addedSeparate++;
                continue;
            }

            long tramOsmId = (tramOsmObj instanceof Number)
                    ? ((Number) tramOsmObj).longValue()
                    : Long.parseLong(tramOsmObj.toString());

            // Search for a base network link sharing the same OSM ID
            Link existingLink = baseByOsmId.get(tramOsmId);

            if (existingLink != null) {
                // Embedded/street-running: add "tram" mode to the existing road link
                Set<String> modes = new HashSet<>(existingLink.getAllowedModes());
                boolean modeAdded = modes.add("tram");
                existingLink.setAllowedModes(modes);

                // Copy tram-specific attributes (colour, operator, route_ref) if not already present
                tramLink.getAttributes().getAsMap().forEach((key, value) -> {
                    if (!key.equals("osmID") && existingLink.getAttributes().getAttribute(key.toString()) == null) {
                        existingLink.getAttributes().putAttribute(key.toString(), value);
                    }
                });

                if (modeAdded) mergedEmbedded++;
            } else {
                // Dedicated track: create a new separate tram link
                addDedicatedTramLinkWithOsmId(baseNetwork, tramLink, tramOsmId);
                addedSeparate++;
            }
        }

        System.out.println("  Embedded/street-running sections (added tram mode): " + mergedEmbedded);
        System.out.println("  Dedicated sections (added separate link):           " + addedSeparate);
        System.out.println("  Total tram links processed:                         " + (addedSeparate + mergedEmbedded));
    }

    /** Creates a dedicated tram link with a unique tram_osmID identifier. */
    private static void addDedicatedTramLinkWithOsmId(Network baseNetwork, Link tramLink, long osmId) {
        Node fromNode = baseNetwork.getNodes().get(tramLink.getFromNode().getId());
        Node toNode = baseNetwork.getNodes().get(tramLink.getToNode().getId());

        Id<Link> newLinkId = Id.createLinkId("tram_" + osmId);

        // Handle unlikely ID collisions
        int suffix = 2;
        while (baseNetwork.getLinks().containsKey(newLinkId)) {
            newLinkId = Id.createLinkId("tram_" + osmId + "_" + suffix);
            suffix++;
        }

        Link newLink = baseNetwork.getFactory().createLink(newLinkId, fromNode, toNode);
        copyLinkProperties(tramLink, newLink);
        tramLink.getAttributes().getAsMap().forEach((k, v) -> newLink.getAttributes().putAttribute(k.toString(), v));
        baseNetwork.addLink(newLink);
    }

    /** Creates a dedicated tram link using a custom ID prefix (fallback for links without OSM ID). */
    private static void addDedicatedTramLink(Network baseNetwork, Link tramLink, String idPrefix) {
        Node fromNode = baseNetwork.getNodes().get(tramLink.getFromNode().getId());
        Node toNode = baseNetwork.getNodes().get(tramLink.getToNode().getId());

        Id<Link> newLinkId = Id.createLinkId(idPrefix + tramLink.getId());
        Link newLink = baseNetwork.getFactory().createLink(newLinkId, fromNode, toNode);
        copyLinkProperties(tramLink, newLink);
        tramLink.getAttributes().getAsMap().forEach((k, v) -> newLink.getAttributes().putAttribute(k.toString(), v));
        baseNetwork.addLink(newLink);
    }

    /** Copies physical link properties (length, speed, capacity, lanes, modes) from source to target. */
    private static void copyLinkProperties(Link source, Link target) {
        target.setLength(source.getLength());
        target.setFreespeed(source.getFreespeed());
        target.setCapacity(source.getCapacity());
        target.setNumberOfLanes(source.getNumberOfLanes());
        target.setAllowedModes(source.getAllowedModes());
    }
}
