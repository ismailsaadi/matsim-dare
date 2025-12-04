package org.example.pt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.utils.CreateVehiclesForSchedule;

import java.util.Random;

public class simulatePT {

    public static void main(String[] args) {

        if (args.length < 3) {
            System.out.println("Usage: java simulatePT <network.xml.gz> <transitSchedule.xml.gz> <transitVehicles.xml.gz> [outputDir]");
            return;
        }

        String networkFile = args[0];
        String scheduleFile = args[1];
        String vehiclesFile = args[2];
        String outputDir = args.length >= 4 ? args[3] : "output_pt_walk_manchester/";

        Config config = ConfigUtils.createConfig();

        config.network().setInputFile(networkFile);
        config.transit().setTransitScheduleFile(scheduleFile);
        config.transit().setVehiclesFile(vehiclesFile);
        config.transit().setUseTransit(true);

        config.controller().setLastIteration(20);
        config.controller().setOutputDirectory(outputDir);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        config.controller().setWriteEventsInterval(20);
        config.controller().setWritePlansInterval(20);

        // fast sim
        config.qsim().setFlowCapFactor(1000);
        config.qsim().setStorageCapFactor(1000);
        config.qsim().setEndTime(36 * 3600);

        // important: proper access/egress walk routing
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.walkConstantTimeToLink);
        Scenario scenario = ScenarioUtils.loadScenario(config);

        // create missing transit vehicles (safety)
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();

        // ============================
        // 10 DUMMY PERSONS – WALK + PT ONLY
        // ============================
        Population pop = scenario.getPopulation();
        PopulationFactory pf = pop.getFactory();
        Random rnd = new Random(2025);

        Coord[] locations = {
                new Coord(-2.2426, 53.4808), // Piccadilly
                new Coord(-2.255,  53.487),  // Salford
                new Coord(-2.284,  53.466),  // Trafford
                new Coord(-2.225,  53.474),  // Shudehill
                new Coord(-2.347,  53.387),  // Altrincham
                new Coord(-2.145,  53.479),  // Victoria
                new Coord(-2.295,  53.462),  // Old Trafford
                new Coord(-2.238,  53.510),  // Cheetham Hill
                new Coord(-2.180,  53.447),  // Didsbury
                new Coord(-2.320,  53.425)   // Wythenshawe
        };

        for (int i = 0; i < 10; i++) {
            Person person = pf.createPerson(Id.createPersonId("mcr_pt_" + i));
            Plan plan = pf.createPlan();
            person.addPlan(plan);
            pop.addPerson(person);

            Coord home = locations[i];
            Coord dest = locations[(i + 4) % 10]; // go somewhere else in GM

            Activity h1 = pf.createActivityFromCoord("home", home);
            h1.setEndTime(7*3600 + 30*60 + rnd.nextInt(1800)); // 07:30 ±30 min
            plan.addActivity(h1);

            Leg ptOut = pf.createLeg("pt");
            plan.addLeg(ptOut);

            Activity work = pf.createActivityFromCoord("work", dest);
            work.setMaximumDuration(9 * 3600);
            plan.addActivity(work);

            Leg ptBack = pf.createLeg("pt");
            plan.addLeg(ptBack);

            Activity h2 = pf.createActivityFromCoord("home", home);
            plan.addActivity(h2);
        }

        new PopulationWriter(pop).write(outputDir + "dummy_population.xml.gz");

        // ============================
        // PRINT ACCESS/EGRESS LINKS AT THE END
        // ============================
        Controller controller = new Controler(scenario);
        // CORRECTED: Use addControlerListener with a ShutdownListener

        controller.addControlerListener(new ShutdownListener() {
            @Override
            public void notifyShutdown(ShutdownEvent event) {
                System.out.println("\n=== FINAL ACCESS & EGRESS LINKS USED ===");
                for (Person p : pop.getPersons().values()) {
                    for (PlanElement pe : p.getSelectedPlan().getPlanElements()) {
                        if (pe instanceof Leg leg) {
                            String mode = leg.getMode();
                            if ("access_walk".equals(mode) || "egress_walk".equals(mode)) {
                                String arrow = "access_walk".equals(mode) ? "→" : "←";
                                System.out.printf(" %s %-12s  %s → %s%n",
                                        arrow, p.getId(),
                                        leg.getRoute().getStartLinkId(),
                                        leg.getRoute().getEndLinkId());
                            }
                        }
                    }
                }
                System.out.println("=== Simulation finished – see folder: " + outputDir + " ===\n");
            }
        });

        controller.run();
    }
}