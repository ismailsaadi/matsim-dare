package en.phm.pt;

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
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads a MATSim multimodal network and exports the PT subnetwork (links where
 * {@code bus} and/or {@code tram} are allowed) as a single LineString layer in a
 * GeoPackage for QGIS visualisation.
 *
 * Usage: java ExportPTNetworkToGpkg [networkFile] [outputGpkg]
 */
public final class ExportPTNetworkToGpkg {

    private static final String DEFAULT_NETWORK = "output/multimodal_network.xml.gz";
    private static final String DEFAULT_OUTPUT  = "output/pt_network.gpkg";
    private static final String LAYER_NAME      = "pt_links";

    private static final int EPSG = 27700;

    private static final GeometryFactory GF = new GeometryFactory();

    public static void main(String[] args) throws Exception {
        String networkFile = args.length > 0 ? args[0] : DEFAULT_NETWORK;
        String outputGpkg  = args.length > 1 ? args[1] : DEFAULT_OUTPUT;

        System.out.println("Reading network: " + networkFile);
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile);

        SimpleFeatureType type = buildFeatureType(LAYER_NAME);
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type);

        List<SimpleFeature> features = new ArrayList<>();
        int busOnly = 0, tramOnly = 0, both = 0;

        for (Link link : network.getLinks().values()) {
            Set<String> modes = link.getAllowedModes();
            boolean hasBus  = modes.contains("bus");
            boolean hasTram = modes.contains("tram");
            if (!hasBus && !hasTram) continue;

            if (hasBus && hasTram) both++;
            else if (hasBus)       busOnly++;
            else                   tramOnly++;

            LineString geom = GF.createLineString(new Coordinate[]{
                    toJts(link.getFromNode().getCoord()),
                    toJts(link.getToNode().getCoord())
            });

            fb.add(geom);
            fb.add(link.getId().toString());
            fb.add(link.getFromNode().getId().toString());
            fb.add(link.getToNode().getId().toString());
            fb.add(String.join(",", new TreeSet<>(modes)));
            fb.add(hasBus);
            fb.add(hasTram);
            fb.add(link.getLength());
            fb.add(link.getFreespeed());
            fb.add(link.getCapacity());
            fb.add(link.getNumberOfLanes());
            features.add(fb.buildFeature(null));
        }

        File outFile = new File(outputGpkg);
        if (outFile.exists() && !outFile.delete()) {
            throw new RuntimeException("Could not delete existing " + outputGpkg);
        }
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();

        try (GeoPackage gpkg = new GeoPackage(outFile)) {
            gpkg.init();
            FeatureEntry entry = new FeatureEntry();
            entry.setTableName(LAYER_NAME);
            entry.setSrid(EPSG);
            entry.setGeometryColumn("geom");
            entry.setGeometryType(Geometries.LINESTRING);
            gpkg.add(entry, new ListFeatureCollection(type, features));
            gpkg.createSpatialIndex(entry);
        }

        System.out.println("================================================================");
        System.out.printf("Total links in network : %,d%n", network.getLinks().size());
        System.out.printf("PT links written       : %,d%n", features.size());
        System.out.printf("  bus only             : %,d%n", busOnly);
        System.out.printf("  tram only            : %,d%n", tramOnly);
        System.out.printf("  bus + tram           : %,d%n", both);
        System.out.println("Output: " + outputGpkg);
        System.out.println("================================================================");
    }

    private static SimpleFeatureType buildFeatureType(String name) {
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName(name);
        try {
            b.setCRS(CRS.decode("EPSG:" + EPSG, true));
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode EPSG:" + EPSG, e);
        }
        b.add("geom",         LineString.class);
        b.add("link_id",      String.class);
        b.add("from_node",    String.class);
        b.add("to_node",      String.class);
        b.add("modes",        String.class);
        b.add("has_bus",      Boolean.class);
        b.add("has_tram",     Boolean.class);
        b.add("length_m",     Double.class);
        b.add("freespeed",    Double.class);
        b.add("capacity",     Double.class);
        b.add("lanes",        Double.class);
        b.setDefaultGeometry("geom");
        return b.buildFeatureType();
    }

    private static Coordinate toJts(Coord c) {
        return new Coordinate(c.getX(), c.getY());
    }
}
