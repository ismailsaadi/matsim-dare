package org.example.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.vehicles.*;

public class simulatePT {

    public static void main(String[] args) {

        if (args.length < 3) {
            System.out.println("Usage: java simulatePT <networkFile> <transitScheduleFile> <transitVehiclesFile> [outputDirectory]");
            System.out.println("Example: java simulatePT input/network.xml.gz input/transitSchedule.xml.gz input/transitVehicles.xml.gz output_pt/");
            System.out.println("(the three input files are the standard MATSim transit input files – if your vehicles file is incomplete, missing vehicles will be created automatically)");
            return;
        }

        String networkFile = args[0];
        String scheduleFile = args[1];
        String vehiclesFile = args[2];
        String outputDir = (args.length >= 4) ? args[3] : "output_pt_sim/";

        Config config = ConfigUtils.createConfig();

        config.network().setInputFile(networkFile);
        config.transit().setTransitScheduleFile(scheduleFile);
        config.transit().setVehiclesFile(vehiclesFile);
        config.transit().setUseTransit(true);

        config.controller().setLastIteration(0);                     // only mobsim, no replanning
        config.controller().setOutputDirectory(outputDir);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        config.controller().setDumpDataAtEnd(true);
        config.controller().setWriteEventsInterval(1);

        // uncongested / fast simulation – only PT vehicles exist
        config.qsim().setFlowCapFactor(1000.0);
        config.qsim().setStorageCapFactor(1000.0);
        config.qsim().setStartTime(0.0);
        config.qsim().setEndTime(36 * 3600.0);

        Scenario scenario = ScenarioUtils.loadScenario(config);   // loads network + schedule + vehicles automatically

        // === robust safety net – adds vehicles for any departures that are missing one + provides a fallback type if file has none ===
        Vehicles transitVehicles = scenario.getTransitVehicles();

        if (transitVehicles.getVehicleTypes().isEmpty()) {
            System.out.println("No vehicle types found in transitVehicles file → creating a reasonable default type");
            VehicleType defaultPtType = transitVehicles.getFactory().createVehicleType(Id.create("default_pt_vehicle", VehicleType.class));
            defaultPtType.setMaximumVelocity(100.0 / 3.6);
            defaultPtType.setLength(18.0);
            defaultPtType.setWidth(2.6);
            defaultPtType.setAccessTime(1.0);
            defaultPtType.setEgressTime(0.75);
            defaultPtType.setDoorOperationMode(VehicleType.DoorOperationMode.serial);
            defaultPtType.setPcuEquivalents(6.0);

            VehicleCapacity capacity = defaultPtType.getCapacity();
            capacity.setSeats(120);
            capacity.setStandingRoom(200);

            transitVehicles.addVehicleType(defaultPtType);
        }

        // this fills in any departures that do not yet have a vehicle (does nothing if all departures already have one)
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), transitVehicles).run();

        Controler controler = new Controler(scenario);
        controler.run();
    }
}